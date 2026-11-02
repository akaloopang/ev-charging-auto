package com.akaloopang.evauto.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.session.NaviChargingStation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalDateTime

/**
 * Pleos NaviHelper 연결.
 *
 *  - 목적지·경유지가 충전소이면 이름·정격 출력·충전 계획(도착 SOC → 목표 SOC)을 기억한다.
 *    충전이 끝났을 때 사업자·출력 구간 판정에 쓰고([stationNear]), 가는 길에 예상 충전비를 보여 준다([plan]).
 *  - 목적지 도착을 알린다([arrivals]) → 이번 주행 비용 안내.
 *  - 내 기록 속 충전소로 길안내를 요청한다([routeTo]).
 */
class NaviWatcher(private val context: Context) {

    private var helper: NaviHelper? = null
    @Volatile private var connected = false

    @Volatile private var station: NaviChargingStation? = null
    @Volatile private var updatedAt: LocalDateTime? = null

    private val _plan = MutableStateFlow<NaviChargingPlan?>(null)
    /** 지금 내비가 안내 중인 충전소와 충전 계획 (충전소로 가는 중이 아니면 null) */
    val plan: StateFlow<NaviChargingPlan?> = _plan.asStateFlow()

    private val _arrivals = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 목적지에 도착함 (도착지 이름) */
    val arrivals: SharedFlow<String> = _arrivals.asSharedFlow()

    /** 마지막 길안내 요청이 실패한 이유 (시연 화면에 그대로 보여 준다) */
    @Volatile var lastRouteError: String? = null
        private set

    private val _status = MutableStateFlow("내비 연결 전")
    val status: StateFlow<String> = _status.asStateFlow()

    private val listener = object : NaviHelperEventListener {
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val points = listOf(drivingInfo.destination) + drivingInfo.waypoints
            val charger = points.firstOrNull { it.isChargingStation }
            if (charger == null) {
                _plan.value = null
                return
            }
            val cp = charger.chargingPlan
            // DrivingState.targetSoc는 충전 계획이 없을 때 10% 같은 값(도착 최소 잔량으로 보임)이 와서
            // 충전 목표로는 50% 이상일 때만 쓴다
            val target = cp?.endSoc?.takeIf { it > 0 } ?: charger.targetSoc.takeIf { it >= 50 }
            val s = NaviChargingStation(
                name = charger.name,
                maxChargingPowerKw = charger.maxChargingPower,
                chargerCount = charger.chargerCount,
                targetSoc = target,
            )
            station = s
            updatedAt = LocalDateTime.now()
            if (_plan.value?.station?.name != s.name) {
                Log.i(TAG, "충전소 안내: ${s.name} ${s.maxChargingPowerKw}kW 계획 ${cp?.startSoc}→${cp?.endSoc} 목표 ${charger.targetSoc} 도착잔량 ${charger.remainDestinationChargingValue}")
            }
            _plan.value = NaviChargingPlan(
                station = s,
                // 충전 계획의 시작 SOC가 없으면(충전이 필요 없다고 본 경로) 목적지 도착 예상 잔량을 쓴다
                arrivalSoc = cp?.startSoc?.takeIf { it > 0 } ?: charger.remainDestinationChargingValue.takeIf { it in 1..100 },
                endSoc = target,
                recommended = charger.isRecommendedChargingStation,
            )
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _plan.value = null
            _arrivals.tryEmit(destinationArrivedInfo.name)
        }

        override fun onRouteCancelled() {
            _plan.value = null
        }

        override fun onRouteEnded() {
            _plan.value = null
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $errorCode")
            _status.value = "내비 오류: $errorCode"
        }
    }

    fun start() {
        runCatching {
            val h = NaviHelper(context.applicationContext)
            helper = h
            h.initialize(
                onConnected = {
                    h.addListener(listener)
                    connected = true
                    _status.value = "내비 연결됨"
                },
                onFailed = { t ->
                    Log.w(TAG, "NaviHelper 연결 실패", t)
                    _status.value = "내비 연결 실패"
                },
            )
        }.onFailure {
            Log.w(TAG, "NaviHelper 시작 실패", it)
            _status.value = "내비 사용 불가"
        }
    }

    fun stop() {
        runCatching {
            helper?.removeListener(listener)
            helper?.release()
        }
        helper = null
        connected = false
    }

    /**
     * 시연: 내비가 이 충전소로 안내 중인 것처럼 기억한다 (에뮬레이터 내비는 최근 목적지에 충전소 정보를 주지 않는다).
     * 충전이 끝나면 실제와 똑같이 이 이름·정격 출력으로 기록한다.
     */
    fun demoStation(s: NaviChargingStation?) {
        station = s
        updatedAt = s?.let { LocalDateTime.now() }
    }

    /** [at] 근처(최근 3시간 안)에 내비가 알려준 충전소. 오래된 정보는 쓰지 않는다. */
    fun stationNear(at: LocalDateTime): NaviChargingStation? {
        val s = station ?: return null
        val t = updatedAt ?: return null
        return if (Duration.between(t, at).abs() <= Duration.ofHours(3)) s else null
    }

    /**
     * 길안내 요청 (NaviHelper.requestRoute). 내비가 연결돼 있지 않으면 false.
     * 좌표는 내 충전 기록에 남은 충전소 좌표 — 기기 안에서 내비로만 넘긴다.
     */
    fun routeTo(lat: Double, lon: Double, name: String, address: String? = null): Boolean {
        val h = helper ?: run { lastRouteError = "내비 SDK 없음"; return false }
        if (!connected) { lastRouteError = "내비 연결 전"; return false }
        lastRouteError = null
        return runCatching {
            // RouteInfo(latitude, longitude, displayLatitude, displayLongitude, poiName, poiId, poiSubId, address,
            //           routeOption, poiProvider, isMobileRequest)
            h.requestRoute(
                RouteInfo(lat, lon, null, null, name, "", "0", address ?: "", RouteOption.RECOMMENDED, "", false),
            )
            true
        }.getOrElse {
            Log.w(TAG, "길안내 요청 실패", it)
            lastRouteError = "${it.javaClass.simpleName}: ${it.message ?: ""}".take(300)
            false
        }
    }

    companion object {
        private const val TAG = "NaviWatcher"
    }
}
