package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 기간 합계 (건수·충전량·결제액). 평균 단가는 건별 단가의 평균이 아니라 총액 ÷ 총량. */
data class PeriodStat(val count: Int = 0, val kWh: Double = 0.0, val payment: Double = 0.0) {
    val isEmpty: Boolean get() = count == 0
    val unitPrice: Double get() = if (kWh > 0) payment / kWh else 0.0

    companion object {
        fun of(records: Iterable<ChargingRecord>): PeriodStat {
            var c = 0; var k = 0.0; var p = 0.0
            for (r in records) { c++; k += r.kWh; p += r.paymentAmount }
            return PeriodStat(c, k, p)
        }
    }
}

/**
 * 이번 달 vs 전월 **같은 기간**. 이번 달은 진행 중이라 전월 한 달 전체와 비교하면 월초엔 늘 크게 줄어든 것처럼 보인다.
 * 그래서 전월도 1일~오늘 날짜까지만 자른다(전월이 짧으면 말일까지).
 */
data class MonthComparison(
    val thisMonth: PeriodStat,
    val lastMonthSame: PeriodStat,
    val lastMonthFull: PeriodStat,
    val day: Int,
) {
    val diff: Double get() = thisMonth.payment - lastMonthSame.payment
    /** 전월 같은 기간 결제액이 0이면 비율을 낼 수 없다 */
    val diffPct: Double? get() = if (lastMonthSame.payment > 0) diff / lastMonthSame.payment * 100 else null
}

/** 묶음 하나(사업자·충전소·충전방식)의 합계. [priced]는 결제 0원(단가 미입력·무료) 제외 — 평균 단가 비교용 */
data class GroupStat(val key: String, val label: String, val stat: PeriodStat, val priced: PeriodStat)

/** 사업자·충전방식별 실측 충전 속도 — 차가 잰 충전 시간이 있는 기록만 */
data class SpeedStat(val provider: String, val type: ChargeType?, val count: Int, val kWh: Double, val minutes: Int) {
    /** 세션 평균 출력 = 충전량 ÷ 충전 시간 (배터리가 찰수록 느려지는 만큼 충전기 최대 출력보다 낮다) */
    val avgKw: Double get() = if (minutes > 0) kWh / (minutes / 60.0) else 0.0
    val avgMinutes: Double get() = if (count > 0) minutes.toDouble() / count else 0.0
}

/** 주행거리 구간을 달로 나눈 값 (주행거리·충전량·결제액) */
data class PeriodEfficiency(val distanceKm: Double, val kWh: Double, val payment: Double) {
    val kmPerKwh: Double get() = if (kWh > 0) distanceKm / kWh else 0.0
    val reliable: Boolean get() = kWh > 0 && kmPerKwh <= MAX_PLAUSIBLE_EFFICIENCY

    /** 같은 거리를 내연기관차로 달렸을 때 연료비 − 실제 충전비. 계산할 수 없으면 null, 손해면 음수 */
    fun savings(gasEfficiency: Double, gasPrice: Double): Double? {
        if (!reliable || gasEfficiency <= 0 || gasPrice <= 0) return null
        return distanceKm / gasEfficiency * gasPrice - payment
    }
}

/**
 * 통계 화면의 집계 (원래 EV충전 기록 앱의 통계를 차량용으로 옮김).
 * '지금'이 필요한 계산은 날짜를 인자로 받는다 — 월초·월말 경계를 테스트로 고정하려고.
 */
object ChargingStats {
    /** 예상 결제액을 보여 주기 시작하는 날 — 월초 며칠은 한두 건으로 한 달을 추정해 값이 크게 튄다 */
    const val MIN_DAY_FOR_PROJECTION = 7

    /** 단가 비교 대상: 실제로 돈을 낸 기록만 (결제 0원이 섞이면 '최저 0원/kWh'가 된다) */
    fun priced(r: ChargingRecord): Boolean = r.kWh > 0 && r.paymentAmount > 0 && r.unitPrice > 0

    /** 달 → 합계 (최신 달 먼저) */
    fun monthly(records: Iterable<ChargingRecord>): Map<YearMonth, PeriodStat> =
        records.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it }).mapValues { PeriodStat.of(it.value) }

    /** 막대 그래프용: 최근 [n]개월 (오래된 달 먼저, 기록 없는 달도 0으로) */
    fun lastMonths(records: Iterable<ChargingRecord>, now: YearMonth, n: Int = 6): List<Pair<YearMonth, PeriodStat>> {
        val m = monthly(records)
        return (n - 1 downTo 0).map { i -> now.minusMonths(i.toLong()).let { it to (m[it] ?: PeriodStat()) } }
    }

    /** 연도 → 합계 (최신 연도 먼저) */
    fun yearly(records: Iterable<ChargingRecord>): Map<Int, PeriodStat> =
        records.groupBy { it.date.year }.toSortedMap(compareByDescending { it }).mapValues { PeriodStat.of(it.value) }

    /** 사업자별 (결제액 큰 순) */
    fun byProvider(records: Iterable<ChargingRecord>): List<GroupStat> =
        records.groupBy { it.provider }
            .map { (p, rs) -> GroupStat(p, p, PeriodStat.of(rs), PeriodStat.of(rs.filter(::priced))) }
            .sortedWith(compareByDescending<GroupStat> { it.stat.payment }.thenBy { it.label })

    /**
     * 충전소별 (자주 간 순). 공용 충전소는 GPS·이름으로 알아본 충전소 키로 묶는다 — 표기가 흔들리는 메모보다 정확하다.
     * @param name 충전소 키 → 화면 이름
     */
    fun byStation(records: Iterable<ChargingRecord>, name: (String) -> String?): List<GroupStat> =
        records.filter { it.car?.stationKey != null }
            .groupBy { it.car!!.stationKey!! }
            .map { (k, rs) -> GroupStat(k, name(k) ?: rs.first().memo ?: rs.first().provider, PeriodStat.of(rs), PeriodStat.of(rs.filter(::priced))) }
            .sortedWith(compareByDescending<GroupStat> { it.stat.count }.thenByDescending { it.stat.payment })

    /** 충전방식별 (완속 → 급속 → 초급속) */
    fun byChargeType(records: Iterable<ChargingRecord>): List<Pair<ChargeType, PeriodStat>> =
        ChargeType.entries.mapNotNull { t ->
            PeriodStat.of(records.filter { it.chargeType == t }).takeIf { !it.isEmpty }?.let { t to it }
        }

    /** 방식별 평균 단가 (결제 0원 제외) */
    fun unitPriceByType(records: Iterable<ChargingRecord>): Map<ChargeType, Double> =
        ChargeType.entries.mapNotNull { t ->
            PeriodStat.of(records.filter { it.chargeType == t && priced(it) }).takeIf { it.kWh > 0 }?.let { t to it.unitPrice }
        }.toMap()

    /** 사업자·충전방식별 실측 충전 속도 (자주 쓴 순) — "같은 급속인데 이 사업자는 실제로 느리다"가 보인다 */
    fun chargingSpeed(records: Iterable<ChargingRecord>): List<SpeedStat> =
        records.filter { (it.chargingMinutes ?: 0) > 0 && it.kWh > 0 }
            .groupBy { it.provider to it.chargeType }
            .map { (k, rs) -> SpeedStat(k.first, k.second, rs.size, rs.sumOf { it.kWh }, rs.sumOf { it.chargingMinutes!! }) }
            .sortedWith(compareByDescending<SpeedStat> { it.count }.thenByDescending { it.avgKw })

    /** [monthsBack] 1 = 전월, 12 = 작년 같은 달. 비교 대상도 1일~오늘 날짜까지만 자른다 */
    fun compareMonth(records: Iterable<ChargingRecord>, today: LocalDate, monthsBack: Long = 1): MonthComparison {
        val now = YearMonth.from(today)
        val last = now.minusMonths(monthsBack)
        val thisMonth = mutableListOf<ChargingRecord>()
        val lastSame = mutableListOf<ChargingRecord>()
        val lastFull = mutableListOf<ChargingRecord>()
        for (r in records) {
            val d = r.date.toLocalDate()
            val ym = YearMonth.from(d)
            if (ym == now && d.dayOfMonth <= today.dayOfMonth) thisMonth += r
            else if (ym == last) {
                lastFull += r
                if (d.dayOfMonth <= today.dayOfMonth) lastSame += r
            }
        }
        return MonthComparison(PeriodStat.of(thisMonth), PeriodStat.of(lastSame), PeriodStat.of(lastFull), today.dayOfMonth)
    }

    /** 이번 달 예상 결제액 = 지금까지 ÷ 지난 날 수 × 이번 달 날 수. 월초([MIN_DAY_FOR_PROJECTION]일 전)거나 기록이 없으면 null */
    fun projectedMonthPayment(records: Iterable<ChargingRecord>, today: LocalDate): Double? {
        if (today.dayOfMonth < MIN_DAY_FOR_PROJECTION) return null
        val now = YearMonth.from(today)
        val s = PeriodStat.of(records.filter { YearMonth.from(it.date) == now && it.date.dayOfMonth <= today.dayOfMonth })
        if (s.isEmpty) return null
        return s.payment / today.dayOfMonth * now.lengthOfMonth()
    }

    fun cheapest(records: Iterable<ChargingRecord>): ChargingRecord? = records.filter(::priced).minByOrNull { it.unitPrice }
    fun priciest(records: Iterable<ChargingRecord>): ChargingRecord? = records.filter(::priced).maxByOrNull { it.unitPrice }

    // ── 주행거리 구간 → 달별 전비·절감액 ───────────────────────
    /**
     * 구간 하나가 각 달에 차지하는 비율. 구간을 끝난 달에 통째로 넣으면 두 달치가 한 달에 몰리고,
     * 연말에 걸친 구간은 통째로 다음 해로 넘어간다. 그래서 날짜 수만큼 고르게 나눈다(시작 날 제외, 끝 날 포함).
     */
    private fun monthShares(s: OdoSegment): Map<YearMonth, Double> {
        val start = s.start.date.toLocalDate()
        val end = s.end.date.toLocalDate()
        val days = ChronoUnit.DAYS.between(start, end)
        if (days <= 0) return mapOf(YearMonth.from(end) to 1.0)
        val shares = mutableMapOf<YearMonth, Double>()
        for (i in 1..days) {
            val k = YearMonth.from(start.plusDays(i))
            shares[k] = (shares[k] ?: 0.0) + 1.0 / days
        }
        return shares
    }

    /** 달별 (주행거리·충전량·결제액). 충전 누락이 의심되는 구간은 통째로 뺀다(여러 달에 얇게 퍼져 절감액을 조용히 키우지 않게) */
    fun monthlyEfficiency(windows: List<OdoSegment>): Map<YearMonth, PeriodEfficiency> {
        val dist = mutableMapOf<YearMonth, Double>()
        val kwh = mutableMapOf<YearMonth, Double>()
        val pay = mutableMapOf<YearMonth, Double>()
        for (s in windows) {
            if (s.kWh <= 0 || s.missingChargeSuspected) continue
            for ((k, f) in monthShares(s)) {
                dist[k] = (dist[k] ?: 0.0) + s.distanceKm * f
                kwh[k] = (kwh[k] ?: 0.0) + s.kWh * f
                pay[k] = (pay[k] ?: 0.0) + s.payment * f
            }
        }
        return dist.keys.sortedDescending().associateWith { PeriodEfficiency(dist[it]!!, kwh[it]!!, pay[it]!!) }
    }

    /** 충전 누락이 의심되는 묶음 (최근 것 먼저) — 이웃 구간과 합쳐도 전비가 불가능한 것만 */
    fun missingCharge(windows: List<OdoSegment>): List<OdoSegment> = windows.filter { it.missingChargeSuspected }.reversed()
}

/**
 * 내연기관차 비교 기준 (공인연비 근사, km/L). 크기 차급은 원래 앱과 같은 값.
 * 하이브리드는 차종마다 편차가 커서(약 15~21km/L) 대표값만 두고, 정확한 값은 연비 칸에 직접 넣는다.
 */
enum class GasCarClass(val label: String, val kmPerL: Double) {
    COMPACT("경차", 15.0), SMALL("준중형", 14.0), MID("중형", 12.0), SUV("SUV", 10.0), LARGE("대형", 9.0),
    HYBRID("하이브리드", 18.0);

    companion object {
        fun matching(kmPerL: Double?): GasCarClass? = entries.firstOrNull { kmPerL != null && it.kmPerL == kmPerL }
    }
}
