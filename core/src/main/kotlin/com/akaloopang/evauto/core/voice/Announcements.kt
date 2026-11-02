package com.akaloopang.evauto.core.voice

import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.session.ChargeCostEstimate
import com.akaloopang.evauto.core.stats.ChargingStats
import com.akaloopang.evauto.core.stats.OdoSegments
import com.akaloopang.evauto.core.stats.PeriodStat
import com.akaloopang.evauto.core.stats.TripCost
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 차에서 읽어 줄 안내 문장 (Pleos TextToSpeech).
 * 운전 중에 듣는 말이라 한 문장에 숫자 두세 개까지만 넣는다. 화면 문구와 같은 계산을 쓴다.
 */
object Announcements {

    fun won(v: Double): String = String.format(Locale.KOREA, "%,d원", v.roundToLong())
    fun won(v: Int): String = String.format(Locale.KOREA, "%,d원", v)
    private fun kwh(v: Double): String = if (v >= 10) "${v.roundToInt()}킬로와트시" else String.format(Locale.US, "%.1f킬로와트시", v)
    private fun pct(v: Double): String = "${v.roundToInt()}퍼센트"

    /**
     * 가는 충전소 예상 충전비 — 충전량·금액과 다 채우면 갈 수 있는 거리까지.
     * @param battery 지금 배터리(SOC·주행가능거리). 있으면 "다 채우면 약 ○킬로미터 갈 수 있어요"를 덧붙인다
     */
    fun chargePlan(e: ChargeCostEstimate, battery: BatteryNow? = null): String {
        val head = "${e.stationName}에서 ${pct(e.toSoc)}까지 채우면"
        val range = battery?.rangeAt(e.toSoc)?.roundToInt()?.let { "다 채우면 약 ${it}킬로미터 갈 수 있어요." }
        val energy = e.kWh?.takeIf { it > 0 }?.let { kwh(it) }
        val body = when {
            e.amount == 0.0 && e.unitPrice == 0.0 -> "무료 충전소로 가요. ${e.stationName}에서는 ${pct(e.toSoc)}까지 채워도 0원이에요."
            e.amount != null -> {
                val basis = when (e.priceSource) {
                    PriceSource.STATION_CONFIRMED -> "지난번 이 충전소 단가로"
                    PriceSource.OTHER_STATION -> "같은 사업자 다른 충전소 단가로"
                    PriceSource.MY_SETTING -> "내 설정 단가로"
                    PriceSource.OFFICIAL_TARIFF -> "공시 요금으로"
                    PriceSource.NONE -> ""
                }
                val what = if (energy != null) "약 $energy 들어가고 약 ${won(e.amount)}" else "약 ${won(e.amount)}"
                "$head $what 나올 것 같아요. $basis 계산했어요.".replace("  ", " ").trim()
            }
            energy != null -> "$head 약 $energy 들어가요. 이 충전소 단가는 아직 몰라서 충전 후에 한 번만 알려 주세요."
            else -> "$head 이 충전소 단가는 아직 몰라요. 충전 후에 한 번만 알려 주세요."
        }
        return if (range != null) "$body $range" else body
    }

    /**
     * 충전 기록 직후 안내 — 충전량·금액과 지금 배터리·주행가능거리.
     * @param whileAway 앱이 꺼져 있던 동안의 충전(밤사이 아파트 충전 등)을 시동을 켜며 찾은 경우
     * @param battery 지금 배터리. SOC는 기록의 충전 끝 SOC를 먼저 쓴다
     */
    fun recorded(r: ChargingRecord, whileAway: Boolean, battery: BatteryNow? = null): String {
        val place = when {
            r.car?.place == PlaceKind.HOME -> "집에서"
            r.memo != null -> "${r.memo}에서"
            else -> "${r.provider}에서"
        }
        val lead = if (whileAway) "꺼져 있던 동안 $place 충전한 것을 찾았어요." else "충전이 끝났어요."
        val energy = if (r.kWh > 0) kwh(r.kWh) else null
        val body = when {
            r.isFree && energy != null -> "$lead $energy, 무료 충전소라 0원으로 기록했어요."
            r.paymentAmount > 0 && energy != null -> "$lead $energy, 약 ${won(r.paymentAmount)}으로 기록했어요."
            energy != null -> "$lead $energy 기록했어요. 단가를 몰라 금액은 비워 뒀어요."
            else -> "$lead 기록했어요."
        }
        return withBattery(body, batteryNow(r.car?.socEnd ?: battery?.socPercent, battery?.rangeKm))
    }

    /**
     * 말로 단가를 정한 뒤 — 이 충전이 얼마가 됐는지와 지금 배터리·주행가능거리.
     * 예: "이번 충전은 41킬로와트시, 약 17,111원이에요. 지금 배터리 80퍼센트로 약 344킬로미터 갈 수 있어요."
     */
    fun afterPrice(r: ChargingRecord?, battery: BatteryNow?): String? {
        val parts = mutableListOf<String>()
        if (r != null && r.kWh > 0) {
            parts += if (r.paymentAmount > 0) "이번 충전은 ${kwh(r.kWh)}, 약 ${won(r.paymentAmount)}이에요." else "이번 충전은 ${kwh(r.kWh)}이에요."
        }
        batteryNow(battery?.socPercent, battery?.rangeKm)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** "…기록했어요." 뒤에 배터리 문장을 자연스럽게 잇는다 */
    private fun withBattery(body: String, tail: String?): String = when {
        tail == null -> body
        body.endsWith("기록했어요.") -> body.removeSuffix("기록했어요.") + "기록했고, " + tail
        else -> "$body $tail"
    }

    /** "지금 배터리 85퍼센트로 약 365킬로미터 갈 수 있어요." — 둘 다 모르면 null */
    private fun batteryNow(soc: Int?, rangeKm: Double?): String? {
        val km = rangeKm?.takeIf { it in 1.0..2000.0 }?.roundToInt()
        val s = soc?.takeIf { it in 0..100 }
        return when {
            s != null && km != null -> "지금 배터리 ${s}퍼센트로 약 ${km}킬로미터 갈 수 있어요."
            s != null -> "지금 배터리는 ${s}퍼센트예요."
            km != null -> "지금 약 ${km}킬로미터 갈 수 있어요."
            else -> null
        }
    }

    /** 이번 주행 비용 */
    fun trip(t: TripCost): String {
        val km = if (t.distanceKm >= 10) "${t.distanceKm.roundToInt()}킬로미터" else String.format(Locale.US, "%.1f킬로미터", t.distanceKm)
        val base = "이번 주행 $km, 전기 요금은 약 ${won(t.cost)}이에요."
        val s = t.savings
        return if (t.gasCost != null && s != null && s > 0) "$base 내연기관차였다면 ${won(t.gasCost)}이었어요." else base
    }

    /**
     * 지난달 브리핑 — 달이 바뀐 뒤 처음 앱을 열 때 한 번.
     * 결제액 · 전월 대비 · 내연기관 대비 절감액(설정이 있을 때). 그 달 기록이 없으면 null.
     */
    fun monthlyBriefing(
        records: List<ChargingRecord>,
        month: YearMonth,
        gasEfficiency: Double? = null,
        gasPrice: Double? = null,
    ): String? {
        val monthly = ChargingStats.monthly(records)
        val s = monthly[month] ?: return null
        if (s.count == 0) return null
        val m = month.monthValue
        val parts = mutableListOf<String>()
        parts += if (s.payment > 0) "${m}월 충전비는 ${s.count}번에 ${won(s.payment)}이에요."
        else "${m}월에 ${s.count}번 충전했어요."
        val prev: PeriodStat? = monthly[month.minusMonths(1)]
        if (prev != null && prev.payment > 0 && s.payment > 0) {
            val diff = s.payment - prev.payment
            if (abs(diff) >= 1000) {
                parts += "${month.minusMonths(1).monthValue}월보다 ${won(abs(diff))} ${if (diff < 0) "적어요" else "많아요"}."
            }
        }
        if (gasEfficiency != null && gasPrice != null) {
            val eff = ChargingStats.monthlyEfficiency(OdoSegments.windows(records))[month]
            val saved = eff?.savings(gasEfficiency, gasPrice)
            if (saved != null && saved >= 1000) parts += "내연기관차보다 ${won(saved)} 아꼈어요."
        }
        return parts.joinToString(" ")
    }
}

/** 지금 배터리 — 안내 끝에 "지금 배터리 ○퍼센트로 약 ○킬로미터 갈 수 있어요"를 붙일 때 쓴다 */
data class BatteryNow(
    val socPercent: Int? = null,
    /** 차가 알려 주는 주행가능거리(km) */
    val rangeKm: Double? = null,
) {
    /** 지금과 같은 전비로 [soc]%일 때 갈 수 있는 거리 — 주행가능거리를 SOC 비율로 늘린다 */
    fun rangeAt(soc: Double): Double? {
        val r = rangeKm?.takeIf { it in 1.0..2000.0 } ?: return null
        val now = socPercent?.takeIf { it in 1..100 } ?: return null
        return r / now * soc
    }
}
