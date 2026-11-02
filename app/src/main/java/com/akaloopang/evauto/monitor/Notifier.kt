package com.akaloopang.evauto.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.session.RecordDraft
import com.akaloopang.evauto.ui.Format
import com.akaloopang.evauto.ui.MainActivity

/** 충전이 기록됐다는 알림 — 시동을 켠 직후 '어젯밤 충전'을 알려준다. 확인은 정차 중 앱에서. */
class Notifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "충전 기록", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun recorded(draft: RecordDraft) {
        val r = draft.record
        val whenText = if (r.car?.source == RecordSource.CAR_INFERRED) "시동이 꺼져 있던 동안" else "방금"
        val amount = if (r.paymentAmount > 0) " · 추정 ${Format.won(r.paymentAmount)}" else " · 단가를 설정하면 금액이 계산돼요"
        val text = "$whenText ${r.provider} ${Format.kwh(r.kWh)}$amount"
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(if (draft.placeDecision.needsConfirm) "충전을 기록했어요 — 확인해 주세요" else "충전을 기록했어요")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(r.id.hashCode(), n)
        }
    }

    companion object {
        private const val CHANNEL = "charge_records"
    }
}
