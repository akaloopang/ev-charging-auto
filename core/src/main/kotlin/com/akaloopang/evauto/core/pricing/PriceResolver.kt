package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.model.PowerTier

/** 단가를 어디서 가져왔는가 — 화면에 그대로 보여줘 사용자가 믿을 수 있는 정도를 알게 한다 */
enum class PriceSource(val key: String, val label: String, val rank: Int) {
    NONE("none", "단가 없음", 0),
    OFFICIAL_TARIFF("official", "공시 안내가", 1),
    /** 같은 사업자·같은 출력 구간의 다른 충전소에서 확인한 단가 — 이 충전소도 같은지 물어본다 */
    OTHER_STATION("other_station", "같은 사업자 다른 충전소 단가", 2),
    MY_SETTING("setting", "내 설정 단가", 3),
    STATION_CONFIRMED("station", "이 충전소 확인 단가", 4);

    companion object {
        fun fromKey(raw: String?): PriceSource? = entries.firstOrNull { it.key == raw }
    }
}

/** 출력 구간 추정. 정격(내비·충전소 정보)을 알면 정확, 차가 받은 최대 출력만 알면 하한값. */
data class TierEstimate(val tier: PowerTier?, val exact: Boolean) {
    companion object {
        fun of(ratedKw: Double?, observedPeakKw: Double?, chargeType: ChargeType?): TierEstimate {
            PowerClassifier.tierFromRatedKw(ratedKw)?.let { return TierEstimate(it, exact = true) }
            val peak = observedPeakKw
            if (peak != null && peak > 0) {
                if (peak <= PowerClassifier.AC_OBSERVED_CEILING_KW) return TierEstimate(PowerTier.UNDER_30, exact = true)
                if (peak >= PowerClassifier.ULTRA_FLOOR_KW) return TierEstimate(PowerTier.OVER_200, exact = true)
                // 정격 ≥ 관측 최대 출력 → 관측값 구간은 하한
                return TierEstimate(PowerClassifier.tierFromRatedKw(peak), exact = false)
            }
            return when (chargeType) {
                ChargeType.AC -> TierEstimate(PowerTier.UNDER_30, exact = true)
                ChargeType.ULTRA -> TierEstimate(PowerTier.OVER_200, exact = true)
                else -> TierEstimate(null, exact = false)
            }
        }
    }
}

data class PriceQuery(
    val provider: String,
    val place: PlaceKind,
    val chargeType: ChargeType?,
    val tier: TierEstimate,
    val station: StationRef?,
)

data class ResolvedPrice(
    val unitPrice: Double?,
    val source: PriceSource,
    val tier: PowerTier?,
    /** 공용 충전소인데 이 충전소에서 확인한 단가가 아직 없음 → '영수증 단가 1회 입력' 안내 */
    val askStationPrice: Boolean,
    /** 확인 단가를 받은 뒤 사업자 공시 요금이 갱신됨 → 다음 영수증으로 다시 확인 권장 */
    val possiblyOutdated: Boolean,
    /** 확인 단가를 등록할 때 저장해 둔 사업자 (내비 없이 와도 사업자를 알 수 있게) */
    val stationOperator: String? = null,
    /** [PriceSource.OTHER_STATION]일 때 단가를 빌려 온 충전소와 확인한 날 ("지난번 ○○점 420원") */
    val refStation: StationRef? = null,
    val refDate: java.time.LocalDate? = null,
)

/**
 * 단가 결정 — 이 앱의 금액 정확도는 여기서 결정된다.
 *
 *  1. 이 충전소에서 확인한 단가 (영수증·충전기 화면에서 1회 입력) — 현장 단가 그대로라 가장 정확
 *  2. 내가 설정한 단가 (아파트 단가, 멤버십 계약 단가 등)
 *  3. 같은 사업자·같은 출력 구간의 다른 충전소에서 확인한 단가 (가장 최근) — 실제로 낸 값이라 공시가보다 가깝다.
 *     충전소마다 다를 수 있어 "지난번 ○○점처럼 420원으로 할까요?"라고 물어 확인받는다.
 *  4. 공시 안내가 (무공해차 통합누리집 사업자 × 용량 5구간, 회원/비회원)
 *  5. 없음 → 금액은 비워 두고 단가 입력을 안내 (추측하지 않는다)
 *
 * 2~5는 모두 '이 충전소 단가가 맞는지' 확인을 기다린다([ResolvedPrice.askStationPrice]).
 *
 * 어떤 경우에도 영수증 금액이 들어오면 그 금액이 최종값이다([AmountPolicy]).
 */
class PriceResolver(
    private val settings: PriceSettings,
    private val book: StationPriceBook,
    private val tariff: TariffTable?,
    private val useMemberTariff: Boolean = true,
) {
    fun resolve(q: PriceQuery): ResolvedPrice {
        val tier = q.tier.tier
        val isPublic = q.place != PlaceKind.HOME

        // 1. 충전소 확인 단가
        q.station?.let { st ->
            book.find(st, tier, q.chargeType, q.tier.exact)?.let { sp ->
                val updated = tariff?.find(st.operator ?: q.provider)?.updated
                return ResolvedPrice(
                    unitPrice = sp.unitPrice,
                    source = PriceSource.STATION_CONFIRMED,
                    tier = sp.tier ?: tier,
                    askStationPrice = false,
                    possiblyOutdated = !sp.isFree && updated != null && updated.isAfter(sp.confirmedOn),
                    stationOperator = sp.station.operator,
                )
            }
        }

        // 2. 내 설정 단가 (관리비 청구 아파트는 여기서 끝 — 관리비 고지서 정산으로 확정)
        settings.getUnitPrice(q.provider, q.chargeType, tier)?.let {
            return ResolvedPrice(it, PriceSource.MY_SETTING, tier, askStationPrice = isPublic && q.station != null, possiblyOutdated = false)
        }
        if (q.provider == ProviderCatalog.APARTMENT) {
            return ResolvedPrice(null, PriceSource.NONE, tier, askStationPrice = false, possiblyOutdated = false)
        }

        // 3. 같은 사업자 다른 충전소에서 확인한 단가 (같은 출력 구간만)
        if (isPublic && q.station != null) {
            val operator = q.station.operator ?: q.provider.takeIf { it != ProviderCatalog.OTHER }
            book.findSameOperator(q.station, operator, tier, q.tier.exact)?.let { sp ->
                val updated = tariff?.find(operator ?: q.provider)?.updated
                return ResolvedPrice(
                    unitPrice = sp.unitPrice,
                    source = PriceSource.OTHER_STATION,
                    tier = sp.tier ?: tier,
                    askStationPrice = true,
                    possiblyOutdated = updated != null && updated.isAfter(sp.confirmedOn),
                    refStation = sp.station,
                    refDate = sp.confirmedOn,
                )
            }
        }

        // 4. 공시 안내가
        if (tier != null) {
            val operator = q.station?.operator ?: q.provider
            tariff?.price(operator, tier, useMemberTariff, allowHigherTier = !q.tier.exact)?.let {
                return ResolvedPrice(it, PriceSource.OFFICIAL_TARIFF, tier, askStationPrice = isPublic, possiblyOutdated = false)
            }
        }

        // 5. 없음
        return ResolvedPrice(null, PriceSource.NONE, tier, askStationPrice = isPublic, possiblyOutdated = false)
    }
}
