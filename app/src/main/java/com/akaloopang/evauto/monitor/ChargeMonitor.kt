package com.akaloopang.evauto.monitor

import android.util.Log
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.vehicle.StationNamer
import com.akaloopang.evauto.core.pricing.TierEstimate
import com.akaloopang.evauto.core.session.ActiveSession
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.ChargeSessionTracker
import com.akaloopang.evauto.core.session.ChargeSessionTracker.Event
import com.akaloopang.evauto.core.session.GapReconciler
import com.akaloopang.evauto.core.session.LiveMeter
import com.akaloopang.evauto.core.session.LossCalibrator
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.session.VehicleSnapshot
import com.akaloopang.evauto.data.Repository
import com.akaloopang.evauto.vehicle.NaviWatcher
import com.akaloopang.evauto.vehicle.SdkThread
import com.akaloopang.evauto.vehicle.VehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * 충전 감지의 중심.
 *
 *  1. 시작할 때: 저장해 둔 '마지막으로 본 상태'와 지금 상태를 비교한다 → 앱이 꺼져 있던 동안
 *     (밤새 아파트 충전)의 충전을 찾아 '추정' 기록으로 남긴다. 진행 중 세션이 있었다면 마무리한다.
 *  2. 실행 중: 차량 상태가 바뀔 때마다 상태 머신에 넣어 충전 시작·진행·종료를 판정한다.
 *  3. 매 스냅샷을 '마지막으로 본 상태'로 저장한다 → 시동을 끄면 그 값이 다음 비교의 기준이 된다.
 */
class ChargeMonitor(
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val source: VehicleSource,
    private val navi: NaviWatcher,
    private val notifier: Notifier,
    /** 내비 없이 간 충전소의 GPS → 주소 (기기가 지원할 때) */
    private val namer: StationNamer? = null,
    /** 충전을 기록했을 때 (음성 안내). 두 번째 값: 앱이 꺼져 있던 동안의 충전을 시동 켜며 찾았는가 */
    private val onRecorded: (ChargingRecord, Boolean) -> Unit = { _, _ -> },
) {
    /** 충전 중 화면에 보여줄 실시간 미터 (충전 중이 아니면 null) */
    private val _live = MutableStateFlow<LiveMeter.Reading?>(null)
    val live: StateFlow<LiveMeter.Reading?> = _live.asStateFlow()

    /**
     * 공용 충전이 끝났는데 그 충전소 확인 단가가 없을 때, 화면이 말로 단가를 물어보도록 기록 id를 띄운다.
     * (앱이 앞에 떠 있을 때만 화면이 반응한다. 한 번 보여 주면 [voicePromptShown]으로 지운다)
     */
    private val _voicePrompt = MutableStateFlow<String?>(null)
    val voicePrompt: StateFlow<String?> = _voicePrompt.asStateFlow()

    fun voicePromptShown() {
        _voicePrompt.value = null
    }

    /** 실시간 미터가 쓰는 단가의 출처 (화면에 '공시 안내가' 등으로 표시) */
    private val _livePriceSource = MutableStateFlow(PriceSource.NONE)
    val livePriceSource: StateFlow<PriceSource> = _livePriceSource.asStateFlow()

    /** 마지막으로 받은 차량 상태와 연결 상태 (개발용 화면 — 에뮬레이터에서 어떤 값이 들어오는지 확인) */
    private val _lastSnapshot = MutableStateFlow<VehicleSnapshot?>(null)
    val lastSnapshot: StateFlow<VehicleSnapshot?> = _lastSnapshot.asStateFlow()
    val connection: StateFlow<String> get() = source.connection

    private val tracker = ChargeSessionTracker()
    private val lock = Mutex()
    private var job: Job? = null
    private var started = false
    private var lastPersisted: VehicleSnapshot? = null

    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            // SDK 초기화는 무거워 메인 스레드를 막지 않게 전용 스레드에서 (끝나야 현재 상태를 읽을 수 있다)
            withContext(SdkThread.dispatcher) {
                runCatching { source.start() }.onFailure { Log.w(TAG, "차량 신호 시작 실패", it) }
                runCatching { navi.start() }.onFailure { Log.w(TAG, "내비 연결 시작 실패", it) }
            }
            reconcileOnStart()
            // 차량 값이 한동안 안 바뀌어도(충전 종료 신호를 못 주는 차종) 세션이 끝나도록 1분마다 현재 상태를 넣는다
            launch {
                while (isActive) {
                    delay(60_000)
                    if (tracker.active != null) lock.withLock { onSnapshot(source.current(timeoutMs = 0)) }
                }
            }
            source.snapshots.collect { s -> lock.withLock { onSnapshot(s) } }
        }
    }

    fun stop() {
        job?.cancel()
        source.stop()
        navi.stop()
        started = false
    }

    /**
     * 개발용 시연: '시동 끄기'. 감시를 멈추고, 시동 끈 시각을 [hoursAgo]시간 전으로 옮긴다.
     * 에뮬레이터에서는 밤새(수 시간) 기다릴 수 없어서, 몇 분 사이 SOC를 올리면 급속으로 판정된다(SOC 상승 속도).
     * 실제 야간 충전처럼 긴 공백을 만들어 아파트 완속 흐름을 보여 준다. 디버그 빌드 화면에서만 쓴다.
     */
    suspend fun demoIgnitionOff(hoursAgo: Long = 8) {
        stop()
        lock.withLock {
            val st = repo.monitor.value
            val last = st.lastSeen ?: return@withLock
            val back = last.copy(time = java.time.LocalDateTime.now().minusHours(hoursAgo))
            lastPersisted = back
            repo.saveMonitor(st.copy(lastSeen = back, active = null))
        }
        _live.value = null
    }

    /** 개발용 시연: '시동 켜기' — 앱이 다시 살아난 것처럼 시동 끄기 직전 상태와 비교한다 */
    fun demoIgnitionOn() = start()

    /** 앱(또는 부팅 수신기)이 살아났을 때 한 번 */
    private suspend fun reconcileOnStart() = lock.withLock {
        val state = repo.monitor.value
        // 느린 헤드유닛·에뮬레이터에서는 SDK 연결 후 첫 값까지 시간이 걸린다
        val now = source.current(timeoutMs = 20_000)
        _lastSnapshot.value = now
        // 이미 기록으로 남긴 세션이 저장 상태에 남아 있으면(기록 직후 앱이 죽은 경우) 다시 마무리하지 않는다 — 이중 기록 방지
        val active = state.active?.takeUnless { a ->
            val start = a.start.truncatedTo(ChronoUnit.SECONDS)
            repo.records.value.any { it.car?.source == RecordSource.CAR_LIVE && it.date.truncatedTo(ChronoUnit.SECONDS) == start }
        }
        tracker.restore(active, state.lastSeen)

        if (active != null) {
            // 충전 도중 앱이 꺼졌다가 살아남
            handle(tracker.resumeAfterRestart(now))
        } else {
            GapReconciler.reconcile(state.lastSeen, now)?.let { record(it) }
            // 비교가 끝났으니 이후 실시간 판정의 기준은 지금 상태
            tracker.restore(null, now)
        }
        persist(now, force = true)
    }

    private suspend fun onSnapshot(s: VehicleSnapshot) {
        _lastSnapshot.value = s
        val events = tracker.onSnapshot(s)
        handle(events)
        // 세션 시작·종료는 바로 저장한다 (안 그러면 재시작 때 끝난 세션을 다시 마무리해 이중 기록된다)
        persist(s, force = events.any { it !is Event.Progress })
    }

    private suspend fun handle(events: List<Event>) {
        for (e in events) when (e) {
            is Event.Started, is Event.Progress -> updateLive(e)
            is Event.Completed -> {
                _live.value = null
                record(e.session)
            }
            is Event.Discarded -> _live.value = null
        }
    }

    private fun updateLive(e: Event) {
        val (active, latest) = when (e) {
            is Event.Started -> e.active to null
            is Event.Progress -> e.active to e.latest
            else -> return
        }
        val car = repo.car.value
        val pricing = repo.pricing()
        val station = navi.stationNear(active.start)
        val rated = station?.maxChargingPowerKw?.takeIf { it > 0 }?.toDouble()
        val type = PowerClassifier.decide(rated, active.peakKw)
        val provider = ProviderCatalog.findInStationName(station?.name, pricing.settings.providers)
            ?: pricing.tariff?.findOperatorInName(station?.name)
            ?: ProviderCatalog.OTHER
        val stationRef = StationRef.of(station?.name, active.location ?: active.lastKnownLocation, provider)
        // 충전 중에도 '이 충전소 확인 단가 > 내 설정 > 공시 안내가' 순으로 단가를 정한다
        val resolved = pricing.resolver.resolve(
            PriceQuery(provider, PlaceKind.PUBLIC, type, TierEstimate.of(rated, active.peakKw, type), stationRef),
        )
        val loss = LossCalibrator.factorFor(type, repo.records.value)
        val snap = latest ?: VehicleSnapshot(time = active.start, socPercent = active.socLatest)
        _live.value = LiveMeter.read(
            active, snap, resolved.unitPrice, loss, car.batteryCapacityKwh,
            targetSoc = station?.targetSoc ?: car.targetSoc,
            rounding = car.roundingRule,
        )
        _livePriceSource.value = resolved.source
    }

    private suspend fun record(session: ChargeSession) {
        val draft = RecordFactory.build(
            session = session,
            pricing = repo.pricing(),
            car = repo.car.value,
            history = repo.records.value,
            navi = navi.stationNear(session.start),
        )
        // 충전소는 GPS + 이름으로 남긴다. 내비 이름이 없으면 위치로 주소를 찾아 붙인다
        val station = draft.station?.let { st ->
            if (st.name != null || st.address != null) st
            else st.location?.let { namer?.address(it) }?.let { st.copy(address = it) } ?: st
        }
        repo.add(draft.record, station)
        if (draft.record.car?.askStationPrice == true && draft.station != null) _voicePrompt.value = draft.record.id
        if (draft.placeDecision.place == PlaceKind.HOME && draft.placeDecision.needsConfirm) {
            repo.saveMonitor(repo.monitor.value.copy(homeCandidate = session.bestLocation))
        }
        notifier.recorded(draft)
        runCatching { onRecorded(draft.record, session.source != RecordSource.CAR_LIVE) }
        Log.i(TAG, "충전 기록: ${draft.record.provider} ${draft.record.kWh}kWh (${session.source})")
    }

    /**
     * 마지막으로 본 상태 저장. 매번 쓰면 저장장치를 혹사하므로 의미 있는 변화나 1분 경과 때만.
     * 위치가 없는 스냅샷(지하주차장)이면 직전 위치를 이어서 남긴다 → 시동 끄기 직전 위치 보존.
     */
    private fun persist(s: VehicleSnapshot, force: Boolean = false) {
        // SOC를 못 받은 스냅샷으로 기준값을 덮어쓰면 다음 비교가 불가능해진다 → 저장하지 않는다
        if (s.socPercent == null) return
        val prev = lastPersisted ?: repo.monitor.value.lastSeen
        val snap = s.copy(
            location = s.location ?: prev?.location,
            odometerKm = s.odometerKm ?: prev?.odometerKm,
        )
        val due = force || prev == null ||
            prev.socPercent != snap.socPercent ||
            prev.odometerKm != snap.odometerKm ||
            prev.chargeState != snap.chargeState ||
            prev.ignitionOn != snap.ignitionOn ||
            repo.monitor.value.active?.start != tracker.active?.start ||
            Duration.between(prev.time, snap.time) >= Duration.ofMinutes(1)
        if (!due) return
        lastPersisted = snap
        val active: ActiveSession? = tracker.active
        repo.saveMonitor(repo.monitor.value.copy(lastSeen = snap, active = active))
    }

    companion object {
        private const val TAG = "ChargeMonitor"
    }
}
