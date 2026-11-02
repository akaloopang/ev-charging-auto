package com.akaloopang.evauto

import android.app.Application
import android.content.pm.PackageManager
import com.akaloopang.evauto.data.Repository
import com.akaloopang.evauto.monitor.ChargeMonitor
import com.akaloopang.evauto.monitor.DriveAssistant
import com.akaloopang.evauto.publicdata.PublicDataClient
import com.akaloopang.evauto.monitor.Notifier
import com.akaloopang.evauto.vehicle.DrivingRestriction
import com.akaloopang.evauto.vehicle.NaviWatcher
import com.akaloopang.evauto.vehicle.NoVehicleSource
import com.akaloopang.evauto.vehicle.PleosVehicleSource
import com.akaloopang.evauto.vehicle.SdkThread
import com.akaloopang.evauto.vehicle.StationNamer
import com.akaloopang.evauto.voice.PleosSpeech
import com.akaloopang.evauto.voice.VoiceActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EvAutoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repo: Repository
        private set
    lateinit var monitor: ChargeMonitor
        private set
    lateinit var driving: DrivingRestriction
        private set
    lateinit var speech: PleosSpeech
        private set
    lateinit var voiceActions: VoiceActions
        private set
    /** Pleos NaviHelper — 충전소 정보·충전 계획·길안내 */
    lateinit var navi: NaviWatcher
        private set
    /** 예상 충전비·이번 주행 비용·음성 안내 */
    lateinit var assistant: DriveAssistant
        private set
    /** 공공데이터(충전소 정보·빈 충전기)·오피넷 유가 — 키가 있을 때만 */
    lateinit var publicData: PublicDataClient
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(filesDir).also { it.load() }
        // android.car가 없는 기기(일반 폰 AVD)에서는 차량 신호 없이 화면만 동작
        val source = if (isCar()) PleosVehicleSource(this) else NoVehicleSource
        navi = NaviWatcher(this)
        monitor = ChargeMonitor(
            scope = appScope,
            repo = repo,
            source = source,
            navi = navi,
            notifier = Notifier(this),
            namer = StationNamer(this),
            onRecorded = { record, whileAway -> assistant.onRecorded(record, whileAway) },
        )
        driving = DrivingRestriction(this).also { it.start() }
        // 음성 SDK 초기화도 무거워 전용 스레드에서 (준비되면 sttReady/ttsReady가 켜진다)
        speech = PleosSpeech(this).also { sp -> SdkThread.handler.post { sp.init() } }
        voiceActions = VoiceActions(repo) { assistant.batteryNow() }
        assistant = DriveAssistant(this, appScope, repo, source, navi, speech, monitor.lastSnapshot).also { it.start() }
        publicData = PublicDataClient(this, repo)
    }

    /** 차량(AAOS)인가: 자동차 기능 + android.car 라이브러리가 모두 있어야 한다 */
    private fun isCar(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            runCatching { Class.forName("android.car.Car") }.isSuccess
}
