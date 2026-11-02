package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.ChargingRecord
import java.time.LocalDateTime

/** 절감액을 계산할 수 없는 이유 — 이유마다 사용자가 할 일이 다르다. */
enum class SavingsIssue { INVALID_SETTINGS, NOT_ENOUGH_ODOMETER, ODOMETER_NOT_INCREASING, NO_CHARGES_IN_RANGE }

data class SavingsResult(
    val available: Boolean,
    val issue: SavingsIssue? = null,
    val distanceKm: Double = 0.0,
    val kWh: Double = 0.0,
    val evCost: Double = 0.0,
    val gasCost: Double = 0.0,
    val savings: Double = 0.0,
    val efficiencyKmPerKwh: Double? = null,
    val fromDate: LocalDateTime? = null,
    val toDate: LocalDateTime? = null,
    val gasEfficiencyUsed: Double = 0.0,
    val gasPriceUsed: Double = 0.0,
    val excludedCount: Int = 0,
    val excludedKm: Double = 0.0,
) {
    val efficiencyReliable: Boolean
        get() = efficiencyKmPerKwh == null || efficiencyKmPerKwh <= MAX_PLAUSIBLE_EFFICIENCY

    /** 주행 1km당 충전비(원/km) */
    val evCostPerKm: Double? get() = if (available && distanceKm > 0) evCost / distanceKm else null
    val gasCostPerKm: Double? get() = if (available && distanceKm > 0) gasCost / distanceKm else null

    companion object {
        fun unavailable(issue: SavingsIssue) = SavingsResult(available = false, issue = issue)
    }
}

/**
 * 주행거리 + 충전 기록 → 실주행 전비 · 내연기관차 대비 절감액.
 *   절감액 = 주행거리 ÷ 내연기관 연비 × 연료 가격 − 실제 충전 결제액
 */
object SavingsCalculator {
    fun compute(
        records: List<ChargingRecord>,
        gasEfficiency: Double,
        gasPrice: Double,
        windows: List<OdoSegment>? = null,
    ): SavingsResult {
        if (gasEfficiency <= 0 || gasPrice <= 0) return SavingsResult.unavailable(SavingsIssue.INVALID_SETTINGS)
        val odoCount = records.count { it.odometer != null && it.odometer > 0 }
        if (odoCount < 2) return SavingsResult.unavailable(SavingsIssue.NOT_ENOUGH_ODOMETER)

        val ws = windows ?: OdoSegments.windows(records)
        if (ws.isEmpty()) return SavingsResult.unavailable(SavingsIssue.ODOMETER_NOT_INCREASING)
        val charged = ws.filter { it.kWh > 0 }
        if (charged.isEmpty()) return SavingsResult.unavailable(SavingsIssue.NO_CHARGES_IN_RANGE)

        val good = ws.filter { it.plausible }
        val excluded = ws.filter { !it.plausible }
        val used = good.ifEmpty { charged }

        val distanceKm = used.sumOf { it.distanceKm }
        val kWh = used.sumOf { it.kWh }
        val evCost = used.sumOf { it.payment }
        val gasCost = distanceKm / gasEfficiency * gasPrice

        return SavingsResult(
            available = true,
            distanceKm = distanceKm,
            kWh = kWh,
            evCost = evCost,
            gasCost = gasCost,
            savings = gasCost - evCost,
            efficiencyKmPerKwh = if (kWh > 0) distanceKm / kWh else null,
            fromDate = used.first().start.date,
            toDate = used.last().end.date,
            gasEfficiencyUsed = gasEfficiency,
            gasPriceUsed = gasPrice,
            excludedCount = if (good.isNotEmpty()) excluded.size else 0,
            excludedKm = if (good.isNotEmpty()) excluded.sumOf { it.distanceKm } else 0.0,
        )
    }
}
