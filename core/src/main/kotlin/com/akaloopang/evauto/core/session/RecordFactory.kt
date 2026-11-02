package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.AmountPolicy
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceResolver
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.ResolvedPrice
import com.akaloopang.evauto.core.pricing.RoundingRule
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.pricing.TierEstimate
import com.akaloopang.evauto.core.pricing.TierPlanner
import com.akaloopang.evauto.core.pricing.TierRange
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/** 내비게이션이 알려준 충전소 정보 (NaviHelper DrivingState에서 옮긴 값) */
data class NaviChargingStation(
    val name: String,
    /** 충전기 정격 최대 출력(kW). 0이면 모름 */
    val maxChargingPowerKw: Int,
    val chargerCount: Int = 0,
    val targetSoc: Int? = null,
)

/** 차량 관련 설정 */
data class CarSettings(
    /** 차량이 배터리 용량을 안 알려줄 때 쓰는 값 (kWh) */
    val batteryCapacityKwh: Double? = null,
    val home: HomeZone? = null,
    /** 집 충전기의 사업자. 관리비 청구면 '아파트', 사업자 충전기면 그 사업자명 */
    val homeProvider: String = ProviderCatalog.APARTMENT,
    val targetSoc: Int = 80,
    val roundingRule: RoundingRule = RoundingRule.HALF_UP_1,
    /** 공시 안내가를 회원가로 볼지(기본) 비회원가로 볼지 */
    val useMemberTariff: Boolean = true,
    /** 처음 설정(집 충전 방식·아파트 단가)을 마쳤거나 '나중에'를 눌렀는가 */
    val setupDone: Boolean = false,
    /** 집에서 충전하는가 (아니면 아파트 단가 안내를 띄우지 않는다) */
    val homeCharging: Boolean = true,
    /** 예상 충전비·충전 기록·주행 비용·월간 브리핑을 말로 알려 줄지 */
    val voiceAnnounce: Boolean = true,
)

/** 금액 계산에 필요한 단가 정보 묶음 */
data class PricingContext(
    val settings: PriceSettings,
    val book: StationPriceBook = StationPriceBook(),
    val tariff: TariffTable? = null,
    val useMemberTariff: Boolean = true,
) {
    val resolver: PriceResolver get() = PriceResolver(settings, book, tariff, useMemberTariff)
}

data class RecordDraft(
    val record: ChargingRecord,
    val placeDecision: PlaceDecision,
    val estimate: EnergyEstimator.Estimate?,
    val price: ResolvedPrice,
    /** 공용 충전소면 그 식별 정보 (확인 단가 등록에 쓰고, 기록에는 키만 남긴다) */
    val station: StationRef?,
) {
    /** 단가를 몰라 금액을 비워 둠 */
    val missingUnitPrice: Boolean get() = price.unitPrice == null
}

/**
 * 감지된 충전 세션 → '추정' 상태의 충전 기록.
 */
object RecordFactory {

    fun build(
        session: ChargeSession,
        pricing: PricingContext,
        car: CarSettings,
        history: List<ChargingRecord>,
        navi: NaviChargingStation? = null,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ): RecordDraft {
        val ratedKw = navi?.maxChargingPowerKw?.takeIf { it > 0 }?.toDouble()
        // 차가 충전 출력을 알려 주지 않으면 SOC 상승 속도로 구한 평균 출력을 최대 출력의 하한으로 쓴다 (완속으로 불가능한 값일 때만)
        val averageKw = if (session.peakKw == null) fastAverageKw(session, car.batteryCapacityKwh) else null
        val peakKw = session.peakKw ?: averageKw
        val observedType = PowerClassifier.decide(ratedKw, peakKw)
        val place = PlaceClassifier.classify(
            session, car.home, observedType, navi?.name,
            homeHasDc = pricing.settings.apartmentDcUnitPrice != null,
        )
        // 집 충전으로 판정됐는데 출력 정보가 없으면(앱이 꺼져 있던 야간 충전) 완속으로 본다
        val chargeType: ChargeType? = observedType ?: if (place.place == PlaceKind.HOME) ChargeType.AC else null
        val tier = TierEstimate.of(ratedKw, peakKw, chargeType)

        val provider = when (place.place) {
            PlaceKind.HOME -> car.homeProvider
            PlaceKind.PUBLIC -> ProviderCatalog.findInStationName(navi?.name, pricing.settings.providers)
                ?: pricing.tariff?.findOperatorInName(navi?.name)
                ?: ProviderCatalog.OTHER
            PlaceKind.UNKNOWN -> ProviderCatalog.OTHER
        }
        // 공용 충전소만 식별 정보를 만든다 (집 위치는 설정의 집 위치로 충분)
        val station = if (place.place == PlaceKind.PUBLIC) {
            StationRef.of(navi?.name, session.bestLocation, provider.takeIf { it != ProviderCatalog.OTHER })
        } else null

        val resolved = pricing.resolver.resolve(PriceQuery(provider, place.place, chargeType, tier, station))
        // 내비 없이 왔어도 이 충전소를 확인한 적이 있으면 그때의 사업자를 쓴다
        val finalProvider = if (provider == ProviderCatalog.OTHER) resolved.stationOperator ?: provider else provider

        val loss = LossCalibrator.factorFor(chargeType, history)
        val estimate = EnergyEstimator.estimate(session, loss, car.batteryCapacityKwh)
        val kWh = estimate?.billedKwh?.let { AmountPolicy.round2(it) } ?: 0.0
        val amount = AmountPolicy.estimateAmount(kWh, resolved.unitPrice, car.roundingRule)

        val minutes = if (session.source == RecordSource.CAR_LIVE) {
            Duration.between(session.start, session.end).toMinutes().toInt().takeIf { it > 0 }
        } else null // 앱이 꺼져 있던 충전은 주차 시간 ≠ 충전 시간

        val record = ChargingRecord(
            id = idGenerator(),
            date = session.start,
            provider = finalProvider,
            kWh = kWh,
            unitPrice = resolved.unitPrice ?: 0.0,
            paymentAmount = amount ?: 0.0,
            chargeType = chargeType,
            chargeSpeedKw = ratedKw,
            chargingMinutes = minutes,
            odometer = session.odometerStart,
            memo = navi?.name,
            car = CarMeta(
                source = session.source,
                amountStatus = AmountStatus.ESTIMATED,
                socStart = session.socStart?.roundToInt(),
                socEnd = session.socEnd?.roundToInt(),
                endDate = session.end,
                estimatedKwh = kWh,
                estimatedAmount = amount,
                batteryKwh = estimate?.batteryKwh?.let { AmountPolicy.round2(it) },
                observedPeakKw = peakKw?.let { AmountPolicy.round2(it) },
                peakFromAverage = averageKw != null,
                place = place.place,
                confirmed = false,
                lowConfidence = session.lowConfidence,
                stationKey = station?.key,
                priceSource = resolved.source,
                tier = resolved.tier,
                tierExact = tier.exact,
                askStationPrice = resolved.askStationPrice,
                priceMaybeOutdated = resolved.possiblyOutdated,
                priceRef = resolved.refStation?.displayName,
                priceRefDate = resolved.refDate,
            ),
        )
        return RecordDraft(record, place, estimate, resolved, station?.copy(operator = station.operator ?: resolved.stationOperator))
    }

    /**
     * 배터리에 들어간 에너지 ÷ 충전 시간 = 평균 출력(kW, 배터리 측). 최대 출력은 평균 이상이다.
     * 완속(AC)으로는 나올 수 없는 값([FAST_AVERAGE_KW] 초과)일 때만 돌려준다 — 낮은 평균은 주차 시간이 섞였을 수 있어
     * 완속이라는 근거가 되지 못한다. 충전 출력을 알려 주지 않는 차종에서 급속을 집 완속으로 오판하지 않게 한다.
     */
    fun fastAverageKw(session: ChargeSession, settingCapacityKwh: Double?): Double? {
        val cap = settingCapacityKwh?.takeIf { it > 0 } ?: session.batteryCapacityKwh ?: return null
        val rise = (session.socEnd ?: return null) - (session.socStart ?: return null)
        val hours = Duration.between(session.start, session.end).toMillis() / 3_600_000.0
        if (rise <= 0 || hours <= 0 || cap <= 0) return null
        return (cap * rise / 100.0 / hours).takeIf { it > FAST_AVERAGE_KW }
    }

    /** 이 평균 출력을 넘으면 완속(국내 최대 11kW 온보드 차저)으로는 불가능 */
    const val FAST_AVERAGE_KW = 18.0

    /**
     * 사업자·충전방식·단가 설정·충전소 확인 단가가 바뀌었을 때 '추정' 기록의 금액만 다시 계산한다.
     * 영수증·관리비 정산으로 확정된 기록은 금액을 절대 건드리지 않는다.
     */
    fun reprice(
        record: ChargingRecord,
        pricing: PricingContext,
        rule: RoundingRule,
        station: StationRef? = null,
        provider: String = record.provider,
        chargeType: ChargeType? = record.chargeType,
    ): ChargingRecord {
        if (!record.isEstimated) return record.copy(provider = provider, chargeType = chargeType)
        val meta = record.car
        val place = meta?.place ?: PlaceKind.UNKNOWN
        val tier = TierEstimate.of(record.chargeSpeedKw, meta?.observedPeakKw, chargeType).let { est ->
            // 사용자가 충전방식을 바꿨으면 그 방식으로 다시 추정, 아니면 저장된 구간 유지
            if (chargeType == record.chargeType && meta?.tier != null) TierEstimate(meta.tier, meta.tierExact) else est
        }
        val st = station?.let { it.copy(operator = provider.takeIf { p -> p != ProviderCatalog.OTHER } ?: it.operator) }
        val resolved = pricing.resolver.resolve(PriceQuery(provider, place, chargeType, tier, st))
        val amount = AmountPolicy.estimateAmount(record.kWh, resolved.unitPrice, rule)
        return record.copy(
            provider = provider,
            chargeType = chargeType,
            unitPrice = resolved.unitPrice ?: 0.0,
            paymentAmount = amount ?: 0.0,
            car = meta?.copy(
                estimatedAmount = amount,
                priceSource = resolved.source,
                tier = resolved.tier,
                tierExact = tier.exact,
                askStationPrice = resolved.askStationPrice,
                priceMaybeOutdated = resolved.possiblyOutdated,
                priceRef = resolved.refStation?.displayName,
                priceRefDate = resolved.refDate,
            ),
        )
    }

    /**
     * 처음 간 충전소에서 영수증(또는 충전기 화면)의 단가를 1회 입력 → 그 충전소·구간의 확인 단가로 저장.
     * 다음부터 같은 충전소에서는 자동으로 이 단가를 쓴다.
     *
     * @param tiers 어느 구간 칸에 넣을지 ([TierPlanner]로 정한 것 또는 사용자가 고른 것). null이면 기록의 구간 하나에만.
     */
    fun learnStationPrice(
        book: StationPriceBook,
        record: ChargingRecord,
        station: StationRef,
        unitPrice: Double,
        on: LocalDate,
        tiers: Collection<PowerTier>? = null,
    ): StationPriceBook {
        val st = station.copy(operator = operatorOf(record, station))
        return if (tiers.isNullOrEmpty()) book.learn(st, record.car?.tier, record.chargeType, unitPrice, on)
        else book.learn(st, tiers, unitPrice, on)
    }

    /**
     * 되물을 수 없는 입력(영수증)용: [TierPlanner]가 구간을 하나로 정할 때만 그 칸에 저장한다.
     * 갈리면 저장하지 않는다 — 다른 구간 칸을 잘못 채우면 다음 방문 때 단가를 다시 묻지 않게 되기 때문.
     * 기록의 구간도 단가로 알아낸 만큼 바로잡아 돌려준다.
     */
    fun learnStationPriceAuto(
        book: StationPriceBook,
        record: ChargingRecord,
        station: StationRef,
        unitPrice: Double,
        on: LocalDate,
        pricing: PricingContext,
    ): Pair<StationPriceBook, ChargingRecord> {
        val fill = planTiers(record, station, unitPrice, pricing, spoken = null) as? TierPlanner.Plan.Fill
            ?: return book to record
        return learnStationPrice(book, record, station, unitPrice, on, fill.tiers) to
            refineTier(record, fill.tiers, exact = false)
    }

    /** 방금 충전 기록에 들어온 단가가 어느 구간 칸인지 (말·영수증 공용) */
    fun planTiers(
        record: ChargingRecord,
        station: StationRef?,
        unitPrice: Double,
        pricing: PricingContext,
        spoken: SpokenSpeed?,
    ): TierPlanner.Plan {
        val meta = record.car
        val car = meta?.tier?.let { TierEstimate(it, meta.tierExact) }
            ?: TierEstimate.of(record.chargeSpeedKw, meta?.observedPeakKw, record.chargeType).takeIf { it.tier != null }
        val entry = pricing.tariff?.find(operatorOf(record, station))
        return TierPlanner.plan(unitPrice, spoken, car, entry, pricing.useMemberTariff)
    }

    /**
     * 차가 하한값만 알던 기록의 구간을, 단가(또는 말한 출력)로 알아낸 구간으로 올린다.
     * 예: 관측 최대 85kW(50kW 이상만 확실) + 채비 430원 → 100kW 이상 충전기였다.
     */
    fun refineTier(record: ChargingRecord, base: Collection<PowerTier>, exact: Boolean): ChargingRecord {
        val meta = record.car ?: return record
        if (meta.tierExact || base.isEmpty()) return record
        val low = meta.tier
        val inRange = base.filter { low == null || it.ordinal >= low.ordinal }.sortedBy { it.ordinal }
        if (inRange.isEmpty()) return record
        return record.copy(car = meta.copy(tier = inRange.first(), tierExact = exact && inRange.size == 1))
    }

    private fun operatorOf(record: ChargingRecord, station: StationRef?): String? =
        record.provider.takeIf { it != ProviderCatalog.OTHER } ?: station?.operator

    /** 확인 단가 출처를 사람이 읽는 문구로 */
    fun priceLabel(record: ChargingRecord): String = when (record.car?.priceSource) {
        PriceSource.STATION_CONFIRMED -> when {
            record.isFree -> "무료 충전소"
            record.car?.priceMaybeOutdated == true -> "이 충전소 확인 단가 · 요금 변경 확인 필요"
            else -> "이 충전소 확인 단가"
        }
        PriceSource.MY_SETTING -> "내 설정 단가"
        PriceSource.OTHER_STATION -> record.car?.priceRef?.let { "지난번 $it 단가" } ?: "같은 사업자 다른 충전소 단가"
        PriceSource.OFFICIAL_TARIFF -> "공시 안내가" + (record.car?.tier?.let { t ->
            // 차가 하한값만 알면 '50kW 이상(추정)'처럼 범위로 보여 준다
            if (record.car?.tierExact == true) " · ${t.label}"
            else " · " + TierRange.label(PowerTier.entries.filter { it.ordinal >= t.ordinal }) + "(추정)"
        } ?: "")
        PriceSource.NONE, null -> "단가 없음"
    }
}
