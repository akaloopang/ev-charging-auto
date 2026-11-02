package com.akaloopang.evauto.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.akaloopang.evauto.EvAutoApp

/**
 * 시동 ON → 헤드유닛 부팅 → 이 수신기가 깨어나 감시를 시작한다.
 * 감시는 시작하자마자 '시동 끄기 직전 상태'와 지금을 비교해 밤사이 아파트 충전을 기록한다.
 * 사용자가 앱을 열지 않아도 된다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as EvAutoApp).monitor.start()
    }
}
