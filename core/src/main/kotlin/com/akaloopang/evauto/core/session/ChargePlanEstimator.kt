package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.AmountPolicy
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TierEstimate
import kotlin.math.roundToInt

/**
 * 내비게이션이 짠 충전 계획 (NaviHelper DrivingState에서 옮긴 값).
 * 목적지·경유지가 충전소이면 내비가 도착 SOC와 충전 목표 SOC를 알려 준다.
 */
data class NaviChargingPlan(
    val station: NaviChargingStation,
    /** 충전소 도착 예상 SOC(%) — ChargingPlan.startSoc. 0이면 모름 */
    val arrivalSoc: Int? = null,
    /** 충전 목표 SOC(%) — ChargingPlan.endSoc, 없으면 DrivingState.targetSoc */
    val endSoc: Int? = null,
    /** 내비가 추천한 충전소인가 */
    val recommended: Boolean = false,
)

/** 가는 충전소에서 충전하면 얼마쯤 나올까 — 출발 전에 미리 알려 준다 */
data class ChargeCostEstimate(
    val stationName: String,
    val provider: String,
    val chargeType: ChargeType?,
    val tier: PowerTier?,
    val fromSoc: Double,
    val toSoc: Double,
    /** 도착 SOC를 내비가 계산했는가 (아니면 지금 SOC 기준이라 실제로는 조금 더 나온다) */
    val arrivalFromNavi: Boolean,
    /** 과금 기준 충전량(kWh, 손실 포함). 배터리 용량을 모르면 null */
    val kWh: Double?,
    val unitPrice: Double?,
    val priceSource: PriceSource,
    val amount: Double?,
    /** [PriceSource.OTHER_STATION]이면 단가를 빌려 온 충전소 */
    val refStation: StationRef? = null,
) {
    val socGain: Double get() = toSoc - fromSoc
}

/**
 * 충전 계획 → 예상 충전비.
 *
 *  - 충전량 = (목표 SOC − 도착 SOC) × 배터리 용량 × 손실계수(영수증으로 학습한 값)
 *  - 단가 = 충전 기록과 같은 순서([com.akaloopang.evauto.core.pricing.PriceResolver]):
 *    이 충전소 확인 단가 > 내 설정 > 같은 사업자 다른 충전소 > 공시 안내가
 *  - 단가를 모르면 금액은 비워 둔다(추측하지 않는다)
 */
object ChargePlanEstimator {

    fun estimate(
        plan: NaviChargingPlan,
        currentSoc: Double?,
        capacityKwh: Double?,
        car: CarSettings,
        pricing: PricingContext,
        history: List<ChargingRecord>,
    ): ChargeCostEstimate? {
        val arrival = plan.arrivalSoc?.takeIf { it in 1..99 }?.toDouble()
        val from = arrival ?: currentSoc?.takeIf { it in 0.0..100.0 } ?: return null
        val to = (plan.endSoc?.takeIf { it in 1..100 }
            ?: plan.station.targetSoc?.takeIf { it in 1..100 }
            ?: car.targetSoc).toDouble()
        if (to - from < MIN_GAIN) return null // 충전할 게 거의 없음

        val name = plan.station.name
        val rated = plan.station.maxChargingPowerKw.takeIf { it > 0 }?.toDouble()
        val type = PowerClassifier.decide(rated, null)
        val tier = TierEstimate.of(rated, null, type)
        val provider = ProviderCatalog.findInStationName(name, pricing.settings.providers)
            ?: pricing.tariff?.findOperatorInName(name)
            ?: ProviderCatalog.OTHER
        val station = StationRef.of(name, null, provider.takeIf { it != ProviderCatalog.OTHER })
        val resolved = pricing.resolver.resolve(PriceQuery(provider, PlaceKind.PUBLIC, type, tier, station))

        // 사용자가 설정한 용량 > 차량이 알려 준 용량 (기록 계산과 같은 순서)
        val capacity = car.batteryCapacityKwh?.takeIf { it > 0 } ?: capacityKwh?.takeIf { it > 0 }
        val loss = LossCalibrator.factorFor(type, history)
        val kWh = capacity?.let { AmountPolicy.round2((to - from) / 100.0 * it * loss) }
        val amount = if (resolved.unitPrice == 0.0) 0.0 // 무료 충전소
        else kWh?.let { AmountPolicy.estimateAmount(it, resolved.unitPrice, car.roundingRule) }

        return ChargeCostEstimate(
            stationName = name,
            provider = if (provider == ProviderCatalog.OTHER) resolved.stationOperator ?: provider else provider,
            chargeType = type,
            tier = resolved.tier ?: tier.tier,
            fromSoc = from,
            toSoc = to,
            arrivalFromNavi = arrival != null,
            kWh = kWh,
            unitPrice = resolved.unitPrice,
            priceSource = resolved.source,
            amount = amount,
            refStation = resolved.refStation,
        )
    }

    /**
     * 충전소로 가는 중인데 예상 충전비를 낼 수 없을 때 화면에 보여 줄 이유.
     * (예: 배터리가 이미 목표보다 높음 — 내비가 충전 계획을 세우지 않은 경로)
     */
    fun skipReason(plan: NaviChargingPlan, currentSoc: Double?, car: CarSettings): String {
        val arrival = plan.arrivalSoc?.takeIf { it in 1..99 }
        val from = arrival?.toDouble() ?: currentSoc?.takeIf { it in 0.0..100.0 }
        val to = plan.endSoc?.takeIf { it in 1..100 } ?: plan.station.targetSoc?.takeIf { it in 1..100 } ?: car.targetSoc
        return when {
            from == null -> "배터리 정보를 기다리고 있어요."
            to - from < MIN_GAIN -> (if (arrival != null) "도착 때 배터리 ${arrival}% 예상이라" else "지금 배터리가 ${from.roundToInt()}%라") +
                " 목표 ${to}%까지 채울 필요가 없어요."
            else -> "충전량을 계산하려면 배터리 용량이 필요해요. '차량·계산'에서 넣어 주세요."
        }
    }

    /** 이보다 적게 채우면(%p) 안내하지 않는다 */
    const val MIN_GAIN = 3.0
}
