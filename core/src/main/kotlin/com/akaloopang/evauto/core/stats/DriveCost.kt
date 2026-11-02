package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.ChargingRecord
import java.time.LocalDate
import kotlin.math.roundToInt

/** 이번 주행에 든 전기 요금 */
data class TripCost(
    val distanceKm: Double,
    val efficiencyKmPerKwh: Double,
    val kWh: Double,
    val unitPrice: Double,
    /** 원 단위 반올림 */
    val cost: Int,
    /** 같은 거리를 내연기관차로 달렸다면 (연비·연료 가격 설정이 있을 때) */
    val gasCost: Int? = null,
) {
    val savings: Int? get() = gasCost?.let { it - cost }
}

/**
 * 주행 비용 — 차량이 알려 주는 주행거리·전비 + 내가 실제로 낸 충전 단가.
 *
 *  - 단가는 최근 충전들의 '낸 돈 ÷ 충전량'(kWh 가중 평균) — 공시가가 아니라 내 실제 평균이다.
 *  - 전비는 차량 값(트립 전비)을 먼저 쓰고, 없으면 충전 기록의 주행거리로 구한 실주행 전비를 쓴다.
 */
object DriveCost {

    /**
     * 최근 [days]일 충전의 kWh 가중 평균 단가(원/kWh). 그 기간에 없으면 전체 기록. 단가 있는 기록이 없으면 null.
     * 무료 충전소(회사 등)에서 넣은 전기도 0원으로 평균에 넣는다 — 실제로 낸 돈 기준이니까.
     */
    fun averageUnitPrice(records: Iterable<ChargingRecord>, today: LocalDate, days: Long = 90): Double? {
        val priced = records.filter { ChargingStats.priced(it) || (it.isFree && it.kWh > 0) }
        val recent = priced.filter { !it.date.toLocalDate().isBefore(today.minusDays(days)) }
        val use = recent.ifEmpty { priced }
        val kWh = use.sumOf { it.kWh }
        return if (kWh > 0) use.sumOf { it.paymentAmount } / kWh else null
    }

    /** 충전 기록(계기판)으로 구한 실주행 전비(km/kWh) — 최근 [windows]개 정상 구간 */
    fun recordEfficiency(records: Iterable<ChargingRecord>, windows: Int = 6): Double? {
        val ws = OdoSegments.windows(records.toList()).filter { it.plausible }.takeLast(windows)
        val kWh = ws.sumOf { it.kWh }
        return if (kWh > 0) ws.sumOf { it.distanceKm } / kWh else null
    }

    /**
     * 차량이 준 전비 값을 km/kWh로. 차종에 따라 km/kWh 또는 kWh/100km로 올 수 있어 범위로 구분한다.
     *  - 2~10 → km/kWh 그대로 (전기차 실전비 범위)
     *  - 10 초과 ~ 40 → kWh/100km로 보고 환산 (100 ÷ 값)
     *  - 그 외(0, 센서 기본값 등) → 쓰지 않는다
     */
    fun fromVehicle(raw: Double?): Double? {
        val v = raw ?: return null
        return when {
            v in MIN_EFFICIENCY..MAX_PLAUSIBLE_EFFICIENCY -> v
            v > MAX_PLAUSIBLE_EFFICIENCY && v <= 40.0 -> 100.0 / v
            else -> null
        }
    }

    fun trip(
        distanceKm: Double,
        efficiencyKmPerKwh: Double?,
        unitPrice: Double?,
        gasEfficiency: Double? = null,
        gasPrice: Double? = null,
    ): TripCost? {
        if (distanceKm < MIN_TRIP_KM) return null
        val eff = efficiencyKmPerKwh?.takeIf { it in MIN_EFFICIENCY..MAX_PLAUSIBLE_EFFICIENCY } ?: return null
        val price = unitPrice?.takeIf { it > 0 } ?: return null
        val kWh = distanceKm / eff
        val gas = if (gasEfficiency != null && gasEfficiency > 0 && gasPrice != null && gasPrice > 0) {
            (distanceKm / gasEfficiency * gasPrice).roundToInt()
        } else null
        return TripCost(distanceKm, eff, kWh, price, (kWh * price).roundToInt(), gas)
    }

    /** 1km 달리는 데 드는 전기 요금(원/km) */
    fun costPerKm(efficiencyKmPerKwh: Double?, unitPrice: Double?): Double? {
        val eff = efficiencyKmPerKwh?.takeIf { it in MIN_EFFICIENCY..MAX_PLAUSIBLE_EFFICIENCY } ?: return null
        val price = unitPrice?.takeIf { it > 0 } ?: return null
        return price / eff
    }

    const val MIN_EFFICIENCY = 2.0
    /** 이보다 짧은 주행은 안내하지 않는다 */
    const val MIN_TRIP_KM = 1.0
}
