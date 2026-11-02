package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.RecordSource
import kotlin.math.abs

/**
 * 아파트 충전 누락 방지의 핵심.
 *
 * 앱은 차량 상태를 받을 때마다 "마지막으로 본 상태(lastSeen)"를 저장한다. 시동을 끄면
 * 그 값이 시동 끄기 직전 상태가 된다. 다음에 앱이 다시 살아나면(시동 ON·부팅) 지금 상태와
 * 비교한다.
 *
 *  - 주행거리가 그대로인데 SOC가 올랐다 → 그 사이 충전이 있었다 (확실)
 *  - 주행거리도 늘었는데 SOC가 올랐다 → 충전은 확실히 있었지만, 주행으로 쓴 만큼
 *    충전량이 적게 잡힌다 (하한값, lowConfidence)
 *  - SOC가 거의 그대로거나 줄었다 → 충전 없음 (회생제동·센서 흔들림은 [minSocRise]로 거른다)
 *
 * 충전 중 앱이 실행되지 않아도 되므로, 밤새 꽂아 두는 아파트 완속 충전을 놓치지 않는다.
 */
object GapReconciler {

    data class Config(
        /** 이 이상 올라야 충전으로 본다 (%p). SOC가 정수라 1%p 흔들림은 무시 */
        val minSocRise: Double = 2.0,
        /** 이 이내의 주행거리 변화는 '주행 없음'으로 본다 (km) */
        val maxOdometerDriftKm: Double = 1.0,
    )

    fun reconcile(
        lastSeen: VehicleSnapshot?,
        now: VehicleSnapshot,
        config: Config = Config(),
    ): ChargeSession? {
        if (lastSeen == null) return null
        val socBefore = lastSeen.socPercent ?: return null
        val socNow = now.socPercent ?: return null
        if (!now.time.isAfter(lastSeen.time)) return null
        val rise = socNow - socBefore
        if (rise < config.minSocRise) return null

        val odoBefore = lastSeen.odometerKm
        val odoNow = now.odometerKm
        val drove = odoBefore == null || odoNow == null || abs(odoNow - odoBefore) > config.maxOdometerDriftKm

        return ChargeSession(
            source = RecordSource.CAR_INFERRED,
            start = lastSeen.time,
            end = now.time,
            socStart = socBefore,
            socEnd = socNow,
            odometerStart = odoBefore,
            odometerEnd = odoBefore,
            peakKw = null,
            integratedKwh = null,
            integrationCoverage = 0.0,
            // 지하주차장에서는 위치가 안 잡히므로 시동 끄기 직전 위치를 쓴다
            location = null,
            lastKnownLocation = lastSeen.location ?: now.location,
            batteryCapacityKwh = now.batteryCapacityKwh ?: lastSeen.batteryCapacityKwh,
            lowConfidence = drove,
        )
    }
}
