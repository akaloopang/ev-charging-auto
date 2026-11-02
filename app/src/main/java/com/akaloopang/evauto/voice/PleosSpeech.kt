package com.akaloopang.evauto.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos SpeechToText / TextToSpeech SDK 래퍼.
 *
 * ON_DEVICE 모드를 쓴다 — 네트워크·앱 등록 없이 차량 안에서 인식·합성한다(공모전 시연 환경에서도 동작).
 * 콜백은 메인 스레드로 넘겨 준다.
 */
class PleosSpeech(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _sttReady = MutableStateFlow(false)
    val sttReady: StateFlow<Boolean> = _sttReady.asStateFlow()
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** 말로 입력을 받는 중 (안내 음성이 끼어들지 않게) */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    @Volatile private var stt: SpeechToText? = null
    @Volatile private var tts: TextToSpeech? = null

    // 현재 요청의 콜백
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onListenError: ((String) -> Unit)? = null
    private var onSpoken: (() -> Unit)? = null
    private var finalDelivered = false
    private var lastText = ""

    /**
     * 듣기 시간 제한. 말이 없거나 마이크 입력이 없어(에뮬레이터 등) 엔진이 끝을 못 내도 마이크를 계속 켜 두지 않는다.
     * 중간 결과가 있으면 그것을 최종으로, 없으면 오류로 넘겨 화면 버튼으로 이어 가게 한다.
     */
    private val listenTimeout = Runnable {
        if (finalDelivered) return@Runnable
        if (lastText.isNotBlank()) deliverFinal(lastText)
        else {
            finalDelivered = true
            _busy.value = false
            runCatching { stt?.stop() }
            onListenError?.invoke("말씀을 듣지 못했어요")
        }
    }

    private val resultListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            lastText = stt
            main.post {
                if (completed) deliverFinal(stt) else onPartial?.invoke(stt)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            // 완료 결과 없이 끝나면 마지막 중간 결과를 최종으로 본다
            main.post { deliverFinal(lastText) }
        }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "STT 오류: $errorType")
            main.post {
                if (!finalDelivered) {
                    finalDelivered = true
                    _busy.value = false
                    main.removeCallbacks(listenTimeout)
                    onListenError?.invoke("음성을 인식하지 못했어요")
                }
            }
        }
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() {
            main.post { onSpoken?.also { onSpoken = null }?.invoke() }
        }
        override fun onStop() {
            main.post { onSpoken?.also { onSpoken = null }?.invoke() }
        }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "TTS 오류: $errorType")
            main.post { onSpoken?.also { onSpoken = null }?.invoke() }
        }
    }

    fun init() {
        if (stt == null) runCatching {
            val s = SpeechToText(app, SttMode.ON_DEVICE)
            stt = s
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    s.addListener(resultListener)
                    runCatching { s.setLocale(Locale.KOREA) }
                    _sttReady.value = true
                }
                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                    _sttReady.value = false
                }
            })
        }.onFailure { Log.w(TAG, "STT 사용 불가", it) }

        if (tts == null) runCatching {
            val t = TextToSpeech(app, TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    t.addEventListener(ttsListener)
                    runCatching { t.setLocale(Locale.KOREA) }
                    _ttsReady.value = true
                }
                override fun onFailure(errorType: TtsError) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                    _ttsReady.value = false
                }
            })
        }.onFailure { Log.w(TAG, "TTS 사용 불가", it) }
    }

    /** 읽어 주기. TTS를 못 쓰면 바로 [onDone] */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = tts
        if (t == null || !_ttsReady.value) { onDone(); return }
        onSpoken = onDone
        runCatching { t.speak(text) }.onFailure {
            Log.w(TAG, "speak 실패", it)
            onSpoken = null
            onDone()
        }
    }

    /** 한 번 듣기. 최종 결과 하나만 [onFinal]로 전달 */
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        val s = stt
        if (s == null || !_sttReady.value) { onError("음성 인식을 쓸 수 없어요"); return }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onListenError = onError
        finalDelivered = false
        _busy.value = true
        lastText = ""
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
        runCatching { s.request() }.onFailure {
            Log.w(TAG, "request 실패", it)
            finalDelivered = true
            _busy.value = false
            onError("음성 인식을 시작하지 못했어요")
        }
    }

    fun stopAll() {
        main.removeCallbacks(listenTimeout)
        runCatching { stt?.stop() }
        runCatching { tts?.stop() }
        onSpoken = null
        finalDelivered = true
        _busy.value = false
    }

    fun release() {
        stopAll()
        runCatching { stt?.removeListener(resultListener); stt?.release() }
        runCatching { tts?.removeEventListener(ttsListener); tts?.release() }
        stt = null
        tts = null
        _sttReady.value = false
        _ttsReady.value = false
    }

    private fun deliverFinal(text: String) {
        if (finalDelivered) return
        finalDelivered = true
        _busy.value = false
        main.removeCallbacks(listenTimeout)
        runCatching { stt?.stop() }
        onFinal?.invoke(text.trim())
    }

    companion object {
        private const val TAG = "PleosSpeech"
        /** 단가 한마디("삼백팔십오 원")를 말하기에 충분한 시간 */
        private const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
