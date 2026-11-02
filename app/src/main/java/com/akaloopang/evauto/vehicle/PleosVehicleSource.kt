package com.akaloopang.evauto.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.akaloopang.evauto.core.session.ChargeState
import com.akaloopang.evauto.core.session.DriveInfo
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.VehicleSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Pleos Vehicle SDK + AAOS Vehicle Property + 위치를 합쳐 [VehicleSnapshot]을 만든다.
 *
 *  - Pleos Vehicle SDK: SOC(정수 %), 누적 주행거리(km) — 3rd party 앱에 열린 공식 경로
 *    + 주행 정보: 주행가능거리, 이번 주행 거리·시간, 전비(최근·충전 이후·누적), 배터리 용량 → [drive]
 *  - AAOS CarPropertyManager: 충전 상태, 충전 포트 연결, 순간 충전 출력, 배터리 용량·잔량(Wh), 시동 상태
 *    차종마다 지원 여부가 달라서 config가 없거나 권한이 없으면 조용히 건너뛴다(그 값은 null).
 *  - 배터리 잔량(Wh)과 용량(Wh)을 모두 받으면 SOC를 소수점까지 계산해 정수 SOC보다 정확하게 쓴다.
 */
class PleosVehicleSource(private val context: Context) : VehicleSource {

    private data class Partial(
        val socInt: Int? = null,
        val levelWh: Double? = null,
        val capacityWh: Double? = null,
        val odometerKm: Double? = null,
        val chargeState: ChargeState? = null,
        val portConnected: Boolean? = null,
        val chargeRateKw: Double? = null,
        val ignitionOn: Boolean? = null,
        val location: GeoPoint? = null,
    ) {
        /**
         * 잔량(Wh)÷용량(Wh)이 정수 SOC와 맞으면 소수점까지 쓰고, 어긋나면(잔량 값을 안 주는 차종이 기본값을 내는 경우 등)
         * Pleos SDK의 SOC를 믿는다.
         */
        val soc: Double?
            get() {
                val fine = if (levelWh != null && capacityWh != null && capacityWh > 0) levelWh / capacityWh * 100.0 else null
                val coarse = socInt?.toDouble()
                return when {
                    fine == null -> coarse
                    coarse == null -> fine
                    abs(fine - coarse) <= SOC_AGREE -> fine
                    else -> coarse
                }
            }

        /**
         * @param trustState·trustPort 충전 상태·포트 연결 값이 실제로 바뀌는 것을 본 적이 있을 때만 판정에 쓴다.
         *   한 번도 안 바뀐 값은 차종·에뮬레이터의 고정 기본값일 수 있어(예: 포트 '분리'로 고정) SOC 판정을 막지 않게 null로 넘긴다.
         */
        fun toSnapshot(now: LocalDateTime, trustState: Boolean, trustPort: Boolean) = VehicleSnapshot(
            time = now,
            socPercent = soc,
            odometerKm = odometerKm,
            chargeState = chargeState.takeIf { trustState },
            portConnected = portConnected.takeIf { trustPort },
            chargeRateKw = chargeRateKw,
            ignitionOn = ignitionOn,
            location = location,
            batteryCapacityKwh = capacityWh?.let { it / 1000.0 },
        )
    }

    private val state = MutableStateFlow(Partial())
    private val _snapshots = MutableSharedFlow<VehicleSnapshot>(replay = 1, extraBufferCapacity = 16)
    override val snapshots: SharedFlow<VehicleSnapshot> = _snapshots.asSharedFlow()

    private val _drive = MutableStateFlow(DriveInfo())
    override val drive: StateFlow<DriveInfo> = _drive.asStateFlow()

    private var sdkStatus = "Vehicle SDK 시작 전"
    private var carStatus = "차량 서비스 연결 중"
    private var sigStatus = ""
    private val _connection = MutableStateFlow("$sdkStatus · $carStatus")
    override val connection: StateFlow<String> = _connection.asStateFlow()

    private fun status(sdk: String? = null, car: String? = null, signal: String? = null) {
        if (sdk != null) sdkStatus = sdk
        if (car != null) carStatus = car
        if (signal != null) sigStatus = signal
        _connection.value = listOf(sdkStatus, carStatus, sigStatus).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * 충전 상태·포트 연결이 '바뀌는 것'을 한 번이라도 본 프로퍼티. 그 전에는 고정 기본값일 수 있어 판정에 쓰지 않는다.
     * 실제 차에서는 처음 충전기를 꽂는 순간 확인되고, 이후 재시작해도 기억한다.
     */
    private val prefs = context.getSharedPreferences("vehicle_signals", Context.MODE_PRIVATE)
    private val verified: MutableSet<String> =
        java.util.Collections.synchronizedSet(prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty().toMutableSet())

    private fun snapshotOf(p: Partial) =
        p.toSnapshot(LocalDateTime.now(), trustState = SIG_STATE in verified, trustPort = SIG_PORT in verified)

    /** 값이 실제로 바뀌었으면(둘 다 값이 있고 서로 다름) 그 신호를 믿는다 */
    private fun observe(key: String, old: Any?, new: Any?) {
        if (old == null || new == null || old == new) return
        if (verified.add(key)) {
            prefs.edit().putStringSet(KEY_VERIFIED, verified.toSet()).apply()
            Log.i(TAG, "$key 값 변화 확인 — 이제 충전 판정에 씁니다")
            status(signal = signalStatus())
        }
    }

    private fun signalStatus(): String {
        val p = state.value
        val raw = "상태 ${p.chargeState ?: "-"}, 포트 ${p.portConnected ?: "-"}"
        val trusted = listOfNotNull("상태".takeIf { SIG_STATE in verified }, "포트".takeIf { SIG_PORT in verified })
        return if (trusted.isEmpty()) "충전 신호($raw) 변화 확인 전 → SOC·주행거리로 판정"
        else "충전 신호($raw) 사용: ${trusted.joinToString("·")}"
    }

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var lastEmitted: VehicleSnapshot? = null
    /** 차량 서비스가 한 번 끊겼다 (다시 연결되면 Vehicle SDK도 새로 붙는다) */
    @Volatile private var carLost = false

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            if (value != null) change { it.copy(socInt = value) }
        }
        override fun onFailed(e: Exception) = log("SOC 리스너 실패", e)
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            if (value != null) change { it.copy(odometerKm = value.toDouble()) }
        }
        override fun onFailed(e: Exception) = log("주행거리 리스너 실패", e)
    }

    // ── 주행 정보 리스너 (차종이 지원하지 않으면 등록이 실패하거나 값이 오지 않는다 — 그 값은 null로 둔다) ──
    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(value: Float?) {
            if (value != null) _drive.update { it.copy(rangeKm = rangeKmOf(value.toDouble())) }
        }
        override fun onFailed(e: Exception) = log("주행가능거리 리스너 실패", e)
    }
    private val tripDistanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            if (value != null) _drive.update { it.copy(tripKm = value.toDouble()) }
        }
        override fun onFailed(e: Exception) = log("이번 주행 거리 리스너 실패", e)
    }
    private val tripTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) {
            if (value != null) _drive.update { it.copy(tripTime = value) }
        }
        override fun onFailed(e: Exception) = log("이번 주행 시간 리스너 실패", e)
    }
    private fun efficiencyListener(name: String, set: (DriveInfo, Double) -> DriveInfo) = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(value: Float?) {
            if (value != null) _drive.update { set(it, value.toDouble()) }
        }
        override fun onFailed(e: Exception) = log("$name 리스너 실패", e)
    }
    private val currentEffListener = efficiencyListener("최근 전비") { d, v -> d.copy(currentEfficiencyRaw = v) }
    private val sinceChargeEffListener = efficiencyListener("충전 이후 전비") { d, v -> d.copy(sinceChargeEfficiencyRaw = v) }
    private val cumulativeEffListener = efficiencyListener("누적 전비") { d, v -> d.copy(cumulativeEfficiencyRaw = v) }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = applyProperty(value)
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "프로퍼티 오류 propId=$propId zone=$zone")
        }
    }

    private val locationListener = LocationListener { loc: Location ->
        change { it.copy(location = GeoPoint(loc.latitude, loc.longitude)) }
    }

    override fun start() {
        startPleos()
        startCarProperties()
        startLocation()
    }

    override fun stop() {
        runCatching {
            vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(socListener)
            vehicle?.getOdometer()?.unregisterDistanceDriven(odometerListener)
            unregisterDrive()
            vehicle?.release()
        }.onFailure { log("Vehicle SDK 해제 실패", it) }
        vehicle = null
        runCatching { propertyManager?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        propertyManager = null
        car = null
        runCatching { locationManager()?.removeUpdates(locationListener) }
        // 멈춘 동안의 값은 모른다 — 다시 시작하면 새 값을 받을 때까지 기다리게 비운다
        // (안 비우면 재시작 직후 비교가 멈추기 전 값으로 이뤄져 꺼진 동안의 충전을 놓친다)
        state.value = Partial()
        lastEmitted = null
    }

    override suspend fun current(timeoutMs: Long): VehicleSnapshot {
        withTimeoutOrNull(timeoutMs) { state.first { it.soc != null && it.odometerKm != null } }
        return snapshotOf(state.value)
    }

    // ── Pleos Vehicle SDK ─────────────────────────────────
    /** 차량 서비스가 재시작되면 SDK 연결도 끊기므로 새로 만들어 다시 구독한다 */
    private fun restartPleos() {
        runCatching {
            vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(socListener)
            vehicle?.getOdometer()?.unregisterDistanceDriven(odometerListener)
            unregisterDrive()
            vehicle?.release()
        }
        vehicle = null
        startPleos()
    }

    private fun startPleos() {
        runCatching {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            val ev = v.getEvBattery()
            val odo = v.getOdometer()
            ev.registerEvBatteryStateOfCharge(socListener)
            odo.registerDistanceDriven(odometerListener)
            // 리스너는 변화가 있을 때만 오므로 현재 값을 한 번 읽어 둔다
            ev.getEvBatteryStateOfCharge(
                onComplete = { v2: Int? -> if (v2 != null) change { it.copy(socInt = v2) } },
                onFailed = { e: Exception -> log("SOC 조회 실패", e) },
            )
            odo.getCurrentDistanceDriven(
                onComplete = { km: Float? -> if (km != null) change { it.copy(odometerKm = km.toDouble()) } },
                onFailed = { e: Exception -> log("주행거리 조회 실패", e) },
            )
            startDrive(v)
            status(sdk = "Vehicle SDK(SOC·주행거리·주행 정보) 구독")
        }.onFailure {
            log("Vehicle SDK 시작 실패", it)
            status(sdk = "Vehicle SDK 시작 실패: ${it.javaClass.simpleName}")
        }
    }

    /** 주행가능거리는 AAOS RANGE_REMAINING처럼 미터로 온다 (에뮬레이터 130000 = 차 화면 130km) */
    private fun rangeKmOf(meters: Double): Double = meters / 1000.0

    /** 주행 정보 구독. 항목마다 따로 — 하나가 미지원이어도 나머지는 받는다 */
    private fun startDrive(v: Vehicle) {
        val ev = v.getEvBattery()
        val odo = v.getOdometer()
        fun num(x: Any?): Double? = (x as? Number)?.toDouble()
        fun tryOn(name: String, block: () -> Unit) = runCatching(block).onFailure { log("$name 사용 불가", it) }
        tryOn("주행가능거리") {
            odo.registerRangeRemainingListener(rangeListener)
            odo.getRangeRemainingDistance(
                onComplete = { x -> num(x)?.let { m -> _drive.update { it.copy(rangeKm = rangeKmOf(m)) } } },
                onFailed = { e: Exception -> log("주행가능거리 조회 실패", e) },
            )
        }
        tryOn("이번 주행 거리") {
            odo.registerTripCurrentDrivingDistance(tripDistanceListener)
            odo.getTripCurrentDrivingDistance(
                onComplete = { x -> num(x)?.let { km -> _drive.update { it.copy(tripKm = km) } } },
                onFailed = { e: Exception -> log("이번 주행 거리 조회 실패", e) },
            )
        }
        tryOn("이번 주행 시간") { odo.registerTripCurrentDrivingTime(tripTimeListener) }
        tryOn("최근 전비") {
            ev.registerCurrentEvEfficiency(currentEffListener)
            ev.getCurrentEvEfficiency(
                onComplete = { x -> num(x)?.let { e -> _drive.update { it.copy(currentEfficiencyRaw = e) } } },
                onFailed = { e: Exception -> log("최근 전비 조회 실패", e) },
            )
        }
        tryOn("충전 이후 전비") { ev.registerSinceChargeEvEfficiency(sinceChargeEffListener) }
        tryOn("누적 전비") { ev.registerCumulativeEvEfficiency(cumulativeEffListener) }
        tryOn("배터리 용량") {
            ev.getEvBatteryCapacity(
                // Wh로 주는 차종과 kWh로 주는 차종이 있어 크기로 구분한다
                onComplete = { x -> num(x)?.let { c -> if (c > 1000) c / 1000.0 else c }?.takeIf { it in 10.0..250.0 }
                    ?.let { kWh -> _drive.update { it.copy(capacityKwh = kWh) } } },
                onFailed = { e: Exception -> log("배터리 용량 조회 실패", e) },
            )
        }
    }

    private fun unregisterDrive() {
        val ev = vehicle?.getEvBattery()
        val odo = vehicle?.getOdometer()
        runCatching { odo?.unregisterRangeRemainingListener(rangeListener) }
        runCatching { odo?.unregisterTripCurrentDrivingDistance(tripDistanceListener) }
        runCatching { odo?.unregisterTripCurrentDrivingTime(tripTimeListener) }
        runCatching { ev?.unregisterCurrentEvEfficiency(currentEffListener) }
        runCatching { ev?.unregisterSinceChargeEvEfficiency(sinceChargeEffListener) }
        runCatching { ev?.unregisterCumulativeEvEfficiency(cumulativeEffListener) }
    }

    // ── AAOS Vehicle Property ─────────────────────────────
    @SuppressLint("InlinedApi")
    private val properties = intArrayOf(
        VehiclePropertyIds.EV_CHARGE_STATE,                    // API 33+
        VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED,
        VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
        VehiclePropertyIds.EV_BATTERY_LEVEL,
        VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY,
        VehiclePropertyIds.IGNITION_STATE,
    )

    /**
     * 차량 서비스 연결. 기다리지 않고(메인 스레드를 막지 않음) 준비되면 콜백으로 받는다.
     * 차량 서비스가 재시작돼도(에뮬레이터 VHAL 재시작 등) 앱이 강제 종료되지 않고, 다시 준비되면 구독을 다시 건다.
     * (리스너 없이 Car.createCar(context)를 쓰면 차량 서비스가 죽을 때 앱 프로세스도 함께 종료된다)
     */
    @SuppressLint("NewApi")
    private fun startCarProperties() {
        runCatching {
            car = if (Build.VERSION.SDK_INT >= 30) {
                Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c: Car, ready: Boolean ->
                    if (ready) {
                        onCarReady(c)
                        if (carLost) {
                            carLost = false
                            Log.i(TAG, "차량 서비스 다시 연결됨 — Vehicle SDK 구독을 다시 겁니다")
                            SdkThread.handler.post { restartPleos() }
                        }
                    } else {
                        carLost = true
                        propertyManager = null
                        status(car = "차량 서비스 끊김 — 재연결 대기")
                        Log.w(TAG, "차량 서비스 연결 끊김 — 다시 연결되면 구독을 다시 겁니다")
                    }
                }
            } else {
                Car.createCar(context.applicationContext)?.also { onCarReady(it) }
            }
        }.onFailure {
            log("CarPropertyManager 시작 실패", it)
            status(car = "차량 프로퍼티 사용 불가: ${it.javaClass.simpleName}")
        }
    }

    private fun onCarReady(c: Car) {
        runCatching {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            val subscribed = mutableListOf<String>()
            for (id in properties) {
                if (pm.getCarPropertyConfig(id) == null) continue // 이 차종은 미지원
                runCatching {
                    // 현재 값 먼저 읽고, 변화를 구독한다
                    applyProperty(pm.getProperty<Any>(id, GLOBAL_AREA))
                    pm.registerCallback(propertyCallback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    subscribed += VehiclePropertyIds.toString(id)
                }.onFailure { log("프로퍼티 $id 사용 불가(권한·미지원)", it) }
            }
            val list = subscribed.ifEmpty { listOf("없음 — SOC·주행거리로 판정") }.joinToString()
            Log.i(TAG, "차량 프로퍼티 구독: $list")
            status(car = "차량 프로퍼티: $list")
        }.onFailure { log("차량 프로퍼티 구독 실패", it) }
    }

    @SuppressLint("InlinedApi")
    private fun applyProperty(v: CarPropertyValue<*>) {
        val value = v.value ?: return
        when (v.propertyId) {
            VehiclePropertyIds.EV_CHARGE_STATE -> {
                val cs = when ((value as Number).toInt()) {
                    1 -> ChargeState.CHARGING
                    2 -> ChargeState.FULLY_CHARGED
                    3 -> ChargeState.NOT_CHARGING
                    4 -> ChargeState.ERROR
                    else -> ChargeState.UNKNOWN
                }
                observe(SIG_STATE, state.value.chargeState, cs)
                change { it.copy(chargeState = cs) }
                status(signal = signalStatus())
            }
            VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> {
                val connected = value as Boolean
                observe(SIG_PORT, state.value.portConnected, connected)
                change { it.copy(portConnected = connected) }
                status(signal = signalStatus())
            }
            // mW, 양수 = 충전 중
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> change {
                it.copy(chargeRateKw = ((value as Number).toDouble() / 1_000_000.0).coerceAtLeast(0.0))
            }
            VehiclePropertyIds.EV_BATTERY_LEVEL -> change { it.copy(levelWh = (value as Number).toDouble()) }
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY -> change { it.copy(capacityWh = (value as Number).toDouble()) }
            // 0 UNDEFINED, 1 LOCK, 2 OFF, 3 ACC, 4 ON, 5 START
            VehiclePropertyIds.IGNITION_STATE -> change { it.copy(ignitionOn = (value as Number).toInt() >= 4) }
        }
    }

    // ── 위치 ──────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = locationManager() ?: return
        runCatching {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) change { it.copy(location = GeoPoint(last.latitude, last.longitude)) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 50f, locationListener, Looper.getMainLooper())
        }.onFailure { log("위치 사용 불가(권한 없음)", it) }
    }

    private fun locationManager() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // ── 변화 반영 · 내보내기 ─────────────────────────────────
    private fun change(block: (Partial) -> Partial) {
        state.update(block)
        val snap = snapshotOf(state.value)
        if (shouldEmit(lastEmitted, snap)) {
            lastEmitted = snap
            Log.d(TAG, "상태 soc=${snap.socPercent} odo=${snap.odometerKm} charge=${snap.chargeState} port=${snap.portConnected} kW=${snap.chargeRateKw}")
            _snapshots.tryEmit(snap)
        }
    }

    /** 상태가 바뀌었거나, 충전 출력만 바뀐 경우는 10초에 한 번, 그 외에는 60초에 한 번 */
    private fun shouldEmit(prev: VehicleSnapshot?, s: VehicleSnapshot): Boolean {
        if (prev == null) return true
        val seconds = java.time.Duration.between(prev.time, s.time).seconds
        val important = prev.chargeState != s.chargeState ||
            prev.portConnected != s.portConnected ||
            prev.ignitionOn != s.ignitionOn ||
            differs(prev.socPercent, s.socPercent, 0.5) ||
            differs(prev.odometerKm, s.odometerKm, 0.1) ||
            (prev.location == null) != (s.location == null)
        if (important) return true
        if (differs(prev.chargeRateKw, s.chargeRateKw, 0.5) && seconds >= 10) return true
        return seconds >= 60
    }

    private fun differs(a: Double?, b: Double?, eps: Double): Boolean =
        (a == null) != (b == null) || (a != null && b != null && abs(a - b) >= eps)

    private fun log(msg: String, t: Throwable) {
        Log.w(TAG, msg, t)
    }

    companion object {
        private const val TAG = "PleosVehicleSource"
        private const val GLOBAL_AREA = 0
        /** 잔량÷용량 SOC와 정수 SOC가 이만큼(%p) 안이면 같은 값으로 본다 */
        private const val SOC_AGREE = 1.5
        private const val KEY_VERIFIED = "verified"
        private const val SIG_STATE = "EV_CHARGE_STATE"
        private const val SIG_PORT = "EV_CHARGE_PORT_CONNECTED"
    }
}
