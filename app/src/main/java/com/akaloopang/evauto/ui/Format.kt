package com.akaloopang.evauto.ui

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.RecordSource
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {
    private val wonFmt = DecimalFormat("#,##0")
    private val kwhFmt = DecimalFormat("#,##0.00")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun won(v: Double): String = wonFmt.format(v) + "원"
    fun kwh(v: Double): String = kwhFmt.format(v) + "kWh"
    /** 단위 없이 숫자만 (큰 숫자 옆에 단위를 작게 붙일 때) */
    fun wonNumber(v: Double): String = wonFmt.format(v)
    fun kwhNumber(v: Double): String = DecimalFormat("#,##0.0").format(v)
    fun km(v: Double): String = wonFmt.format(v) + "km"

    fun date(d: LocalDateTime): String {
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return "${d.monthValue}/${d.dayOfMonth}($dow) ${d.format(timeFmt)}"
    }

    fun status(r: ChargingRecord): String = when (r.amountStatus) {
        AmountStatus.ESTIMATED -> "추정"
        AmountStatus.BILL_SETTLED -> "관리비 정산"
        AmountStatus.RECEIPT -> "영수증"
    }

    /** '어젯밤 아파트 완속'처럼 사람이 읽는 제목 */
    fun title(r: ChargingRecord): String {
        val speed = r.chargeType?.speedName ?: ""
        val prefix = if (r.car?.source == RecordSource.CAR_INFERRED) "시동 꺼진 동안 " else ""
        return "$prefix${r.provider} $speed".trim()
    }
}
