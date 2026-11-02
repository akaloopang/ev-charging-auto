package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.session.VehicleSnapshot
import java.time.LocalDateTime

internal fun t(s: String): LocalDateTime = LocalDateTime.parse(s)

internal fun rec(
    id: String,
    date: String,
    kWh: Double,
    pay: Double,
    provider: String = "아파트",
    odo: Double? = null,
    status: AmountStatus? = null,
    type: ChargeType? = ChargeType.AC,
    batteryKwh: Double? = null,
) = ChargingRecord(
    id = id,
    date = t(date),
    provider = provider,
    kWh = kWh,
    unitPrice = if (kWh > 0) pay / kWh else 0.0,
    paymentAmount = pay,
    chargeType = type,
    odometer = odo,
    car = status?.let { CarMeta(source = RecordSource.CAR_INFERRED, amountStatus = it, batteryKwh = batteryKwh) },
)

internal fun snap(
    time: String,
    soc: Double? = null,
    odo: Double? = null,
    state: com.akaloopang.evauto.core.session.ChargeState? = null,
    port: Boolean? = null,
    rate: Double? = null,
    lat: Double? = null,
    lon: Double? = null,
    capacity: Double? = null,
) = VehicleSnapshot(
    time = t(time),
    socPercent = soc,
    odometerKm = odo,
    chargeState = state,
    portConnected = port,
    chargeRateKw = rate,
    location = if (lat != null && lon != null) com.akaloopang.evauto.core.session.GeoPoint(lat, lon) else null,
    batteryCapacityKwh = capacity,
)

/** 서울 성북구 근처 임의 좌표 (테스트용) */
internal const val HOME_LAT = 37.5894
internal const val HOME_LON = 127.0167
