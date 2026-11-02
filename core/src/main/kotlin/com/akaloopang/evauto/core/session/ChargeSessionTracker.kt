package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.RecordSource
import java.time.Duration
import java.time.LocalDateTime

/**
 * 앱이 실행 중일 때 차량 상태를 받아 충전 시작·진행·종료를 판정하는 상태 머신.
 *
 * 판정 근거(우선순위):
 *  1. 차량이 직접 알려주는 충전 상태(EV_CHARGE_STATE)·포트 연결·충전 출력
 *  2. 그 값을 못 받는 차종이면, 주행거리 변화 없이 SOC가 오르는 것
 *
 * 앱이 꺼진 동안의 충전은 [GapReconciler]가 맡는다.
 */
class ChargeSessionTracker(
    /** 충전 신호가 끊긴 뒤 이만큼 지나면 종료로 본다 (상태값을 못 받는 차종용) */
    private val endGrace: Duration = Duration.ofMinutes(3),
    /** 이 간격보다 먼 두 샘플 사이는 적분하지 않는다 (앱이 멈췄던 구간) */
    private val maxIntegrationGap: Duration = Duration.ofMinutes(5),
    /** 이보다 적게 오르고 적분량도 작으면 '꽂았다 뺀 것'으로 보고 버린다 */
    private val minMeaningfulSocRise: Double = 1.0,
    private val minMeaningfulKwh: Double = 0.5,
) {
    sealed interface Event {
        data class Started(val active: ActiveSession) : Event
        data class Progress(val active: ActiveSession, val latest: VehicleSnapshot) : Event
        data class Completed(val session: ChargeSession) : Event
        /** 너무 작아서 기록하지 않은 세션 */
        data class Discarded(val active: ActiveSession) : Event
    }

    var active: ActiveSession? = null
        private set

    private var last: VehicleSnapshot? = null
    private var lastLocation: GeoPoint? = null

    /** 저장해 둔 진행 중 세션으로 복원 (앱 재시작) */
    fun restore(active: ActiveSession?, lastSeen: VehicleSnapshot?) {
        this.active = active
        this.last = lastSeen
        this.lastLocation = lastSeen?.location ?: active?.lastKnownLocation
    }

    fun onSnapshot(s: VehicleSnapshot): List<Event> {
        val prev = last
        if (s.location != null) lastLocation = s.location
        val events = mutableListOf<Event>()
        val cur = active
        if (cur == null) {
            startIfCharging(prev, s)?.let { active = it; events += Event.Started(it) }
        } else {
            events += update(cur, prev, s)
        }
        last = s
        return events
    }

    private fun startIfCharging(prev: VehicleSnapshot?, s: VehicleSnapshot): ActiveSession? {
        if (s.reportsCharging) {
            return ActiveSession(
                start = s.time, socStart = s.socPercent, odometerStart = s.odometerKm,
                lastChargingTime = s.time, socLatest = s.socPercent, odometerLatest = s.odometerKm,
                peakKw = s.chargeRateKw?.takeIf { it > 0 }, integratedKwh = 0.0, integratedMinutes = 0.0,
                location = s.location, lastKnownLocation = s.location ?: lastLocation,
                batteryCapacityKwh = s.batteryCapacityKwh, stateReported = true,
            )
        }
        // 상태값을 못 받는 차종: 주행 없이 SOC가 1% 이상 오르면 충전으로 본다.
        if (prev == null || s.reportsNotCharging) return null
        val socNow = s.socPercent ?: return null
        val socPrev = prev.socPercent ?: return null
        if (socNow - socPrev < 1.0) return null
        if (!sameOdometer(prev.odometerKm, s.odometerKm)) return null
        if (Duration.between(prev.time, s.time) > Duration.ofMinutes(30)) return null
        return ActiveSession(
            start = prev.time, socStart = socPrev, odometerStart = prev.odometerKm,
            lastChargingTime = s.time, socLatest = socNow, odometerLatest = s.odometerKm,
            peakKw = s.chargeRateKw?.takeIf { it > 0 }, integratedKwh = 0.0, integratedMinutes = 0.0,
            location = s.location ?: prev.location, lastKnownLocation = s.location ?: prev.location ?: lastLocation,
            batteryCapacityKwh = s.batteryCapacityKwh ?: prev.batteryCapacityKwh, stateReported = false,
        )
    }

    private fun update(cur: ActiveSession, prev: VehicleSnapshot?, s: VehicleSnapshot): List<Event> {
        var a = cur
        // 주행이 시작됐으면 이 샘플의 SOC는 이미 주행으로 줄어든 값이라 세션에 넣지 않는다.
        val drove = !sameOdometer(a.odometerStart, s.odometerKm, toleranceKm = 0.2)
        if (drove) return finish(a, endTime = a.lastChargingTime, socEndOverride = null)
        // 출력 적분 (사다리꼴). 앱이 멈췄던 긴 공백은 건너뛴다.
        if (prev != null && prev.chargeRateKw != null && s.chargeRateKw != null) {
            val gap = Duration.between(prev.time, s.time)
            if (!gap.isNegative && gap <= maxIntegrationGap) {
                val hours = gap.toMillis() / 3_600_000.0
                a = a.copy(
                    integratedKwh = a.integratedKwh + (prev.chargeRateKw + s.chargeRateKw) / 2.0 * hours,
                    integratedMinutes = a.integratedMinutes + hours * 60,
                )
            }
        }
        val rate = s.chargeRateKw
        a = a.copy(
            socLatest = s.socPercent ?: a.socLatest,
            odometerLatest = s.odometerKm ?: a.odometerLatest,
            peakKw = if (rate != null && rate > (a.peakKw ?: 0.0)) rate else a.peakKw,
            location = a.location ?: s.location,
            batteryCapacityKwh = s.batteryCapacityKwh ?: a.batteryCapacityKwh,
            stateReported = a.stateReported || s.chargeState == ChargeState.CHARGING,
        )

        return when {
            s.reportsCharging -> {
                a = a.copy(lastChargingTime = s.time)
                active = a
                listOf(Event.Progress(a, s))
            }
            s.reportsNotCharging -> finish(a, endTime = s.time, socEndOverride = s.socPercent)
            else -> {
                // 상태값이 없는 차종: SOC가 오르는 동안은 충전 중으로 본다
                val rising = prev?.socPercent != null && s.socPercent != null && s.socPercent > prev.socPercent
                if (rising) a = a.copy(lastChargingTime = s.time)
                if (Duration.between(a.lastChargingTime, s.time) > endGrace) {
                    finish(a, endTime = a.lastChargingTime, socEndOverride = null)
                } else {
                    active = a
                    listOf(Event.Progress(a, s))
                }
            }
        }
    }

    /** 진행 중 세션을 끝낸다. 주행해서 끝난 경우 SOC는 주행 전 마지막 값(socLatest)을 쓴다. */
    private fun finish(a: ActiveSession, endTime: LocalDateTime, socEndOverride: Double?): List<Event> {
        active = null
        val socEnd = socEndOverride ?: a.socLatest
        val rise = if (a.socStart != null && socEnd != null) socEnd - a.socStart else null
        if ((rise == null || rise < minMeaningfulSocRise) && a.integratedKwh < minMeaningfulKwh) {
            return listOf(Event.Discarded(a))
        }
        val totalMinutes = Duration.between(a.start, endTime).toMillis() / 60_000.0
        val coverage = if (totalMinutes > 0) (a.integratedMinutes / totalMinutes).coerceIn(0.0, 1.0) else 0.0
        return listOf(
            Event.Completed(
                ChargeSession(
                    source = RecordSource.CAR_LIVE,
                    start = a.start, end = endTime,
                    socStart = a.socStart, socEnd = socEnd,
                    odometerStart = a.odometerStart, odometerEnd = a.odometerStart,
                    peakKw = a.peakKw,
                    integratedKwh = a.integratedKwh.takeIf { it > 0 },
                    integrationCoverage = coverage,
                    location = a.location, lastKnownLocation = a.lastKnownLocation,
                    batteryCapacityKwh = a.batteryCapacityKwh,
                ),
            ),
        )
    }

    /**
     * 앱이 재시작됐는데 저장된 진행 중 세션이 있고, 지금은 충전이 끝나 있는 경우를 마무리한다.
     * 앱이 꺼진 사이 충전이 계속됐을 수 있으므로 지금 SOC를 종료값으로 쓴다.
     * 그 사이 주행이 있었다면 지금 SOC는 주행으로 줄어든 값이라 하한값이 된다.
     */
    fun resumeAfterRestart(now: VehicleSnapshot): List<Event> {
        val a = active ?: return emptyList()
        if (now.reportsCharging) return onSnapshot(now)
        val drove = !sameOdometer(a.odometerStart, now.odometerKm, toleranceKm = 1.0)
        val socEnd = listOfNotNull(a.socLatest, now.socPercent).maxOrNull()
        val events = finish(a, endTime = now.time, socEndOverride = socEnd)
        last = now
        return events.map { e ->
            if (e is Event.Completed) Event.Completed(e.session.copy(lowConfidence = drove, integrationCoverage = 0.0))
            else e
        }
    }

    private fun sameOdometer(a: Double?, b: Double?, toleranceKm: Double = 0.1): Boolean =
        a == null || b == null || kotlin.math.abs(b - a) <= toleranceKm
}
