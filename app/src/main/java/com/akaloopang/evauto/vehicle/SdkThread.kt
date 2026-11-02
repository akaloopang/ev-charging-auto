package com.akaloopang.evauto.vehicle

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * Pleos SDK(Vehicle·NaviHelper·STT/TTS) 초기화 전용 스레드.
 *
 * SDK 초기화는 클래스 로딩·kotlin-reflect·서비스 연결로 무겁다(에뮬레이터에서 메인 스레드가 10초 넘게 묶여 ANR 위험).
 * 메인 스레드 대신 Looper가 있는 전용 스레드에서 초기화한다 — SDK가 내부에서 Handler를 만들어도 동작한다.
 * 리스너 콜백은 StateFlow만 갱신하므로 어느 스레드에서 와도 된다.
 */
object SdkThread {
    private val thread = HandlerThread("pleos-sdk").apply { start() }
    val handler = Handler(thread.looper)
    val dispatcher = handler.asCoroutineDispatcher("pleos-sdk")
}
