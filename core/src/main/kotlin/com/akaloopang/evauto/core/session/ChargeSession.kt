package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.RecordSource
import java.time.Duration
import java.time.LocalDateTime

/**
 * 감지된 충전 한 건 (기록으로 저장되기 전 단계).
 */
data class ChargeSession(
    val source: RecordSource,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val socStart: Double?,
    val socEnd: Double?,
    val odometerStart: Double?,
    val odometerEnd: Double?,
    /** 차량이 받은 최대 충전 출력(kW) */
    val peakKw: Double? = null,
    /** 충전 출력을 시간 적분한 배터리 측 에너지(kWh). 출력 값을 못 받으면 null */
    val integratedKwh: Double? = null,
    /** 적분이 세션 시간의 몇 %를 덮었는가 (0~1). 앱이 중간에 꺼졌으면 낮다 */
    val integrationCoverage: Double = 0.0,
    /** 충전 중 잡힌 위치 */
    val location: GeoPoint? = null,
    /** 시동 끄기 직전(지하주차장 진입 전) 마지막으로 잡힌 위치 */
    val lastKnownLocation: GeoPoint? = null,
    val batteryCapacityKwh: Double? = null,
    /** 앱이 꺼진 사이 주행이 끼어 있는 등 충전량이 하한값일 수 있음 */
    val lowConfidence: Boolean = false,
) {
    val socRise: Double? get() = if (socStart != null && socEnd != null) socEnd - socStart else null
    val duration: Duration get() = Duration.between(start, end)
    val bestLocation: GeoPoint? get() = location ?: lastKnownLocation
}

/** 충전 중인 세션의 진행 상태. 앱이 재시작돼도 이어가도록 저장한다. */
data class ActiveSession(
    val start: LocalDateTime,
    val socStart: Double?,
    val odometerStart: Double?,
    val lastChargingTime: LocalDateTime,
    val socLatest: Double?,
    val odometerLatest: Double?,
    val peakKw: Double?,
    val integratedKwh: Double,
    val integratedMinutes: Double,
    val location: GeoPoint?,
    val lastKnownLocation: GeoPoint?,
    val batteryCapacityKwh: Double?,
    /** 차량이 충전 상태를 직접 알려준 적이 있는가(아니면 SOC 상승으로 추정한 세션) */
    val stateReported: Boolean,
)
