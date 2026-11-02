package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.stats.DriveCost

/**
 * 주행 정보 (Pleos Vehicle SDK Odometer·EvBattery). 차종이 지원하지 않으면 null.
 * 전비 원시값은 차종에 따라 km/kWh 또는 kWh/100km라서 [DriveCost.fromVehicle]로 읽는다.
 */
data class DriveInfo(
    /** 주행가능거리(km) */
    val rangeKm: Double? = null,
    /** 이번 주행 거리(km) — 시동을 켤 때마다 0부터 */
    val tripKm: Double? = null,
    /** 이번 주행 시간(차량이 주는 단위 그대로) */
    val tripTime: Int? = null,
    /** 최근 전비(원시값) */
    val currentEfficiencyRaw: Double? = null,
    /** 충전 이후 전비(원시값) */
    val sinceChargeEfficiencyRaw: Double? = null,
    /** 누적 전비(원시값) */
    val cumulativeEfficiencyRaw: Double? = null,
    /** 배터리 용량(kWh) — SDK getEvBatteryCapacity */
    val capacityKwh: Double? = null,
) {
    /** 이번 주행 비용 계산에 쓸 전비(km/kWh): 최근 → 충전 이후 → 누적 순 */
    val efficiencyKmPerKwh: Double?
        get() = DriveCost.fromVehicle(currentEfficiencyRaw)
            ?: DriveCost.fromVehicle(sinceChargeEfficiencyRaw)
            ?: DriveCost.fromVehicle(cumulativeEfficiencyRaw)

    val isEmpty: Boolean get() = this == DriveInfo()
}
