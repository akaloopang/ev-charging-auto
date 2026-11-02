package com.akaloopang.evauto.core.session

import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** AAOS EV_CHARGE_STATE 값과 같은 의미 */
enum class ChargeState { UNKNOWN, CHARGING, FULLY_CHARGED, NOT_CHARGING, ERROR }

data class GeoPoint(val lat: Double, val lon: Double) {
    /** 하버사인 거리(m) */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

/**
 * 한 시점의 차량 상태. 값이 없으면(차종 미지원·조회 실패) null.
 * 차량 SDK 의존성이 없도록 core에서는 이 형태로만 다룬다.
 */
data class VehicleSnapshot(
    val time: LocalDateTime,
    /** 배터리 잔량(%) — Vehicle SDK는 정수로 준다 */
    val socPercent: Double? = null,
    /** 누적 주행거리(km) */
    val odometerKm: Double? = null,
    val chargeState: ChargeState? = null,
    val portConnected: Boolean? = null,
    /** 순간 충전 출력(kW, 배터리 측). 충전 중이 아니면 0 */
    val chargeRateKw: Double? = null,
    val ignitionOn: Boolean? = null,
    /** 위치. 지하주차장처럼 못 잡으면 null */
    val location: GeoPoint? = null,
    /** 배터리 용량(kWh) — 차량이 알려주면 */
    val batteryCapacityKwh: Double? = null,
) {
    /** 차량이 "지금 충전 중"이라고 직접 알려주는가 */
    val reportsCharging: Boolean
        get() = chargeState == ChargeState.CHARGING ||
            (chargeState == null || chargeState == ChargeState.UNKNOWN) &&
            portConnected == true && (chargeRateKw ?: 0.0) > MIN_CHARGE_RATE_KW

    /** 차량이 "충전 중이 아니다"라고 직접 알려주는가 */
    val reportsNotCharging: Boolean
        get() = chargeState == ChargeState.FULLY_CHARGED ||
            chargeState == ChargeState.NOT_CHARGING ||
            portConnected == false

    companion object {
        const val MIN_CHARGE_RATE_KW = 0.3
    }
}
