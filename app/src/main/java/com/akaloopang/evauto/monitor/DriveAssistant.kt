package com.akaloopang.evauto.monitor

import android.content.Context
import android.util.Log
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.session.ChargeCostEstimate
import com.akaloopang.evauto.core.session.ChargePlanEstimator
import com.akaloopang.evauto.core.session.DriveInfo
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.session.VehicleSnapshot
import com.akaloopang.evauto.core.stats.DriveCost
import com.akaloopang.evauto.core.stats.TripCost
import com.akaloopang.evauto.core.voice.Announcements
import com.akaloopang.evauto.core.voice.BatteryNow
import com.akaloopang.evauto.data.Repository
import com.akaloopang.evauto.vehicle.NaviWatcher
import com.akaloopang.evauto.vehicle.VehicleSource
import com.akaloopang.evauto.voice.PleosSpeech
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.resume

/**
 * 운전 중 도우미 — Pleos NaviHelper·Vehicle·TextToSpeech를 묶는다.
 *
 *  - 예상 충전비: 내비가 충전소로 안내 중이면 충전 계획(도착 SOC → 목표 SOC) × 그 충전소 단가 → 화면 + 한 번 읽어 주기
 *  - 이번 주행 비용: 이번 주행 거리 ÷ 전비 × 내가 실제로 낸 평균 단가 → 화면, 목적지 도착 때 읽어 주기
 *  - 충전 기록 안내: 충전이 끝나 기록하면(밤사이 충전은 시동을 켤 때) 읽어 주기
 *  - 월간 브리핑: 달이 바뀐 뒤 처음 시동을 켜면 지난달 충전비·전월 대비·절감액
 *
 * 말하기는 설정 '음성 안내'가 켜져 있고 TTS가 준비됐을 때만. 말로 단가를 입력하는 중이면 끼어들지 않는다.
 */
class DriveAssistant(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val source: VehicleSource,
    private val navi: NaviWatcher,
    private val speech: PleosSpeech,
    private val snapshots: StateFlow<VehicleSnapshot?>,
    /** TTS 호출 스레드 (테스트에서 바꿔 끼운다) */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val _expected = MutableStateFlow<ChargeCostEstimate?>(null)
    /** 지금 내비로 가는 충전소의 예상 충전비 */
    val expected: StateFlow<ChargeCostEstimate?> = _expected.asStateFlow()

    private val _trip = MutableStateFlow<TripCost?>(null)
    private val _skipReason = MutableStateFlow<String?>(null)
    /** 충전소로 가는 중인데 예상 충전비가 없을 때 그 이유 (배터리가 이미 목표보다 높음 등) */
    val skipReason: StateFlow<String?> = _skipReason.asStateFlow()

    /** 이번 주행 비용 (주행 거리를 아는 차종에서만) */
    val trip: StateFlow<TripCost?> = _trip.asStateFlow()

    /** 시연용으로 넣은 충전 계획 (내비가 없는 에뮬레이터에서 화면·음성 확인) */
    private val demoPlan = MutableStateFlow<NaviChargingPlan?>(null)

    val drive: StateFlow<DriveInfo> get() = source.drive

    /** 지금 안내 중인 충전소와 충전 계획 (시연용 계획이 있으면 그것을 먼저 — 시연 버튼은 일부러 누른 것이니까) */
    val plan: StateFlow<NaviChargingPlan?> =
        combine(navi.plan, demoPlan) { real, demo -> demo ?: real }.stateIn(scope, SharingStarted.Eagerly, null)

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val speakLock = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true

        // 예상 충전비 — 계획·SOC·단가가 바뀔 때마다 다시 계산
        scope.launch {
            combine(
                plan,
                snapshots, source.drive, repo.records,
                combine(repo.book, repo.price, repo.car) { b, p, c -> Triple(b, p, c) },
            ) { plan, snap, drive, records, _ -> Triple(plan, snap?.socPercent to (snap?.batteryCapacityKwh ?: drive.capacityKwh), records) }
                .collect { (plan, socCap, records) ->
                    val e = plan?.let {
                        runCatching {
                            ChargePlanEstimator.estimate(it, socCap.first, socCap.second, repo.car.value, repo.pricing(), records)
                        }.getOrNull()
                    }
                    _expected.value = e
                    _skipReason.value = if (plan != null && e == null) ChargePlanEstimator.skipReason(plan, socCap.first, repo.car.value) else null
                }
        }

        // 새 충전소로 안내가 시작되면 예상 충전비를 한 번 읽어 준다 (계획이 자리 잡도록 잠깐 기다린 뒤)
        scope.launch {
            var announcedFor: String? = null
            plan.collect { plan ->
                val name = plan?.station?.name
                if (name == null) { announcedFor = null; return@collect }
                if (name == announcedFor) return@collect
                announcedFor = name
                delay(PLAN_SETTLE_MS)
                _expected.value?.takeIf { it.stationName == name }?.let { announce(Announcements.chargePlan(it, batteryNow())) }
            }
        }

        // 이번 주행 비용
        scope.launch {
            combine(source.drive, repo.records, repo.price) { d, records, p -> Triple(d, records, p) }
                .collect { (d, records, p) ->
                    val km = d.tripKm
                    _trip.value = if (km == null) null else DriveCost.trip(
                        distanceKm = km,
                        efficiencyKmPerKwh = d.efficiencyKmPerKwh ?: DriveCost.recordEfficiency(records),
                        unitPrice = DriveCost.averageUnitPrice(records, LocalDate.now()),
                        gasEfficiency = p.effectiveGasEfficiency,
                        gasPrice = p.effectiveGasPrice,
                    )
                }
        }

        // 목적지 도착 → 이번 주행 비용
        scope.launch {
            navi.arrivals.collect {
                _trip.value?.let { t -> announce("도착했어요. " + Announcements.trip(t)) }
            }
        }

        // 월간 브리핑 (시동을 켠 뒤 다른 안내와 겹치지 않게 조금 기다린다)
        scope.launch {
            delay(BRIEF_DELAY_MS)
            briefIfNewMonth()
        }
    }

    /** 충전 기록 직후 (ChargeMonitor가 부른다) */
    fun onRecorded(record: ChargingRecord, whileAway: Boolean) {
        scope.launch {
            // 시동 직후에는 주행가능거리가 조금 늦게 올 수 있어 잠깐 기다린다
            val range = if (whileAway) withTimeoutOrNull(RANGE_WAIT_MS) { source.drive.first { it.rangeKm != null } }?.rangeKm else null
            announce(Announcements.recorded(record, whileAway, batteryNow().copy(rangeKm = range ?: source.drive.value.rangeKm)))
        }
    }

    /** 지금 배터리(SOC·주행가능거리) — 충전량·금액을 읽어 줄 때 "약 ○킬로미터 갈 수 있어요"를 붙인다 */
    fun batteryNow(): BatteryNow = BatteryNow(snapshots.value?.socPercent?.let { kotlin.math.round(it).toInt() }, source.drive.value.rangeKm)

    /** 이번 주행 비용을 지금 읽어 주기 (화면 타일을 눌렀을 때) */
    fun speakTrip() {
        val t = _trip.value ?: return
        scope.launch { announce(Announcements.trip(t), force = true) }
    }

    fun speakExpected() {
        val e = _expected.value ?: return
        scope.launch { announce(Announcements.chargePlan(e, batteryNow()), force = true) }
    }

    /** 달이 바뀌었으면 지난달 브리핑. 기록이 없던 달이면 조용히 넘어간다 */
    suspend fun briefIfNewMonth(now: YearMonth = YearMonth.now()) {
        val last = prefs.getString(KEY_BRIEF_MONTH, null)
        if (last == now.toString()) return
        prefs.edit().putString(KEY_BRIEF_MONTH, now.toString()).apply()
        if (last == null) return // 설치 후 첫 달은 브리핑할 지난달이 없다
        briefing(now.minusMonths(1))?.let { announce(it) }
    }

    fun briefing(month: YearMonth): String? {
        val p = repo.price.value
        return Announcements.monthlyBriefing(repo.records.value, month, p.effectiveGasEfficiency, p.effectiveGasPrice)
    }

    /** 시연: 지난달 브리핑을 지금 듣기 */
    fun demoBriefing() {
        val m = YearMonth.now().minusMonths(1)
        val text = briefing(m) ?: briefing(YearMonth.now()) ?: "아직 브리핑할 충전 기록이 없어요."
        scope.launch { announce(text, force = true) }
    }

    /** 시연: 내비가 충전소로 안내하는 상황을 흉내 낸다 (null이면 해제) */
    fun demoPlan(plan: NaviChargingPlan?) {
        demoPlan.value = plan
        navi.demoStation(plan?.station)
    }

    /**
     * 읽어 주기. [force]가 아니면 '음성 안내' 설정을 따른다.
     * TTS가 아직 준비 중이면(시동 직후) 잠깐 기다리고, 말로 입력하는 중이면 넘어간다.
     */
    suspend fun announce(text: String, force: Boolean = false) {
        if (!force && !repo.car.value.voiceAnnounce) return
        if (withTimeoutOrNull(TTS_WAIT_MS) { speech.ttsReady.first { it } } == null) {
            Log.i(TAG, "TTS 준비 안 됨 — 안내 생략: $text")
            return
        }
        if (speech.busy.value) {
            Log.i(TAG, "음성 입력 중 — 안내 생략: $text")
            return
        }
        speakLock.withLock {
            withContext(mainDispatcher) {
                suspendCancellableCoroutine { cont -> speech.speak(text) { if (cont.isActive) cont.resume(Unit) } }
            }
        }
    }

    companion object {
        private const val TAG = "DriveAssistant"
        private const val KEY_BRIEF_MONTH = "briefMonth"
        private const val PLAN_SETTLE_MS = 4_000L
        private const val RANGE_WAIT_MS = 3_000L
        private const val BRIEF_DELAY_MS = 25_000L
        private const val TTS_WAIT_MS = 20_000L
    }
}
