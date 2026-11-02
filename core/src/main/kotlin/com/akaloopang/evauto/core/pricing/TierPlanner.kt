package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.model.PowerTier

/**
 * 사용자가 말한(또는 Gleo가 넘긴) 충전 속도.
 *  - "100킬로와트" 처럼 출력을 말하면 구간이 하나로 정해진다([exact]).
 *  - "급속"·"초급속" 같은 말은 여러 구간을 뜻한다(사람마다 쓰는 범위가 달라서).
 */
data class SpokenSpeed(val tiers: List<PowerTier>, val exact: Boolean, val kw: Double? = null) {
    val chargeType: ChargeType
        get() = when {
            tiers.all { it == PowerTier.UNDER_30 } -> ChargeType.AC
            tiers.all { it == PowerTier.OVER_200 } -> ChargeType.ULTRA
            else -> ChargeType.DC
        }

    companion object {
        /** 완속 — 30kW 미만 */
        val SLOW = SpokenSpeed(listOf(PowerTier.UNDER_30), exact = true)
        /** 중속 — 30~50kW */
        val MEDIUM = SpokenSpeed(listOf(PowerTier.T30_TO_50), exact = true)
        /** 급속 — 보통 50·100kW 충전기를 말하지만 30kW대도 급속이라 부른다 */
        val FAST = SpokenSpeed(listOf(PowerTier.T30_TO_50, PowerTier.T50_TO_100, PowerTier.T100_TO_200), exact = false)
        /** 초급속 — 100kW 이상을 부르는 사람도, 200kW 이상만 부르는 사람도 있다 */
        val ULTRA_FAST = SpokenSpeed(listOf(PowerTier.T100_TO_200, PowerTier.OVER_200), exact = false)

        /** 충전기 출력(kW) → 구간. 3~400kW 밖이면 잘못 들은 값 */
        fun ofKw(kw: Double): SpokenSpeed? {
            if (kw < MIN_KW || kw > MAX_KW) return null
            return PowerClassifier.tierFromRatedKw(kw)?.let { SpokenSpeed(listOf(it), exact = true, kw = kw) }
        }

        fun of(type: ChargeType): SpokenSpeed = when (type) {
            ChargeType.AC -> SLOW
            ChargeType.DC -> FAST
            ChargeType.ULTRA -> ULTRA_FAST
        }

        const val MIN_KW = 3.0
        const val MAX_KW = 400.0
    }
}

/**
 * 단가를 어느 출력 구간 칸에 넣을지 정한다. 칸은 항상 하나다.
 *
 * 같은 사업자라도 충전기·구간별 프로모션이 있을 수 있어 한 구간에서 확인한 단가를 다른 구간에 넣지 않는다.
 * 사용자에게 출력을 묻는 것은 '정말 모를 때'뿐이다.
 *  1. 출력을 말했으면("100킬로") 그 구간.
 *  2. 방금 충전이라면 차가 이미 안다 — 내비 정격, AC 수준 출력(완속), 200kW 이상 관측이면 구간이 정확하다.
 *  3. 차가 하한값만 알거나 "급속"처럼 넓게 말했으면 후보 구간이 여러 개 → 말한 단가와 공시가가 같은 구간이
 *     하나뿐이면 그 구간 (예: 채비 410원 = 50~100kW)
 *  4. 그래도 여럿이면 충전기 출력을 묻는다. 공시가와 같은 구간이 있으면 후보를 그 구간들로 좁혀서 묻는다
 *     (예: 채비 430원 → "100~200kW / 200kW 이상 중 어느 충전기였나요?").
 */
object TierPlanner {

    enum class Basis { SPOKEN, CAR, PRICE_MATCH }

    sealed interface Plan {
        /** 이 구간 칸에 단가를 넣는다 (항상 한 칸) */
        data class Fill(val tier: PowerTier, val basis: Basis) : Plan {
            val tiers: List<PowerTier> get() = listOf(tier)
        }
        /** 후보가 여럿이라 충전기 출력을 물어야 한다 */
        data class AskKw(val candidates: List<PowerTier>) : Plan
    }

    private val FAST_TIERS = PowerTier.entries.filter { it != PowerTier.UNDER_30 }
    private const val EPS = 0.05

    /**
     * @param spoken 말한 속도 (없으면 null)
     * @param car 방금 충전에서 차가 판정한 구간 (사업자 단가 설정처럼 충전과 무관하면 null)
     * @param entry 그 사업자의 공시 요금 (공시에 없는 사업자면 null)
     * @param member 공시 회원가 기준이면 true (비회원가와 같은 값도 함께 대조한다)
     */
    fun plan(price: Double, spoken: SpokenSpeed?, car: TierEstimate?, entry: TariffEntry?, member: Boolean): Plan {
        val (candidates, basis) = candidates(spoken, car)
        if (candidates.size == 1) return Plan.Fill(candidates.single(), basis)
        if (entry != null) {
            for (table in if (member) listOf(entry.member, entry.nonMember) else listOf(entry.nonMember, entry.member)) {
                val matched = candidates.filter { t -> table[t]?.let { kotlin.math.abs(it - price) < EPS } == true }
                if (matched.size == 1) return Plan.Fill(matched.single(), Basis.PRICE_MATCH)
                if (matched.isNotEmpty()) return Plan.AskKw(matched)
            }
        }
        return Plan.AskKw(candidates)
    }

    /** 후보 구간과 근거 */
    private fun candidates(spoken: SpokenSpeed?, car: TierEstimate?): Pair<List<PowerTier>, Basis> {
        // 사용자가 충전기에 적힌 출력을 말했으면 그게 가장 정확하다
        if (spoken != null && spoken.exact) return spoken.tiers to Basis.SPOKEN
        val carTier = car?.tier
        // 차가 정확히 아는 구간은 "급속" 같은 넓은 말보다 정확하다 (읽어 주기로 바로잡을 수 있다)
        if (carTier != null && car.exact) return listOf(carTier) to Basis.CAR
        if (carTier != null) {
            // 하한값: 정격은 관측 최대 출력 이상
            val above = FAST_TIERS.filter { it.ordinal >= carTier.ordinal }
            val narrowed = spoken?.tiers?.filter { it in above }.orEmpty()
            return (narrowed.ifEmpty { above }) to Basis.CAR
        }
        if (spoken != null) return spoken.tiers to Basis.SPOKEN
        return PowerTier.entries.toList() to Basis.SPOKEN
    }
}

/** 출력 구간 묶음을 사람이 읽는 말로: 화면 "50kW 이상", 음성 "50킬로와트 이상" */
object TierRange {
    private fun lower(t: PowerTier) = when (t) {
        PowerTier.UNDER_30 -> 0
        PowerTier.T30_TO_50 -> 30
        PowerTier.T50_TO_100 -> 50
        PowerTier.T100_TO_200 -> 100
        PowerTier.OVER_200 -> 200
    }

    private fun upper(t: PowerTier): Int? = when (t) {
        PowerTier.UNDER_30 -> 30
        PowerTier.T30_TO_50 -> 50
        PowerTier.T50_TO_100 -> 100
        PowerTier.T100_TO_200 -> 200
        PowerTier.OVER_200 -> null
    }

    fun label(tiers: Collection<PowerTier>, spoken: Boolean = false): String {
        val sorted = tiers.distinct().sortedBy { it.ordinal }
        if (sorted.isEmpty()) return ""
        val runs = mutableListOf(mutableListOf(sorted.first()))
        for (t in sorted.drop(1)) {
            if (t.ordinal == runs.last().last().ordinal + 1) runs.last() += t else runs += mutableListOf(t)
        }
        return runs.joinToString(", ") { run(it, spoken) }
    }

    private fun run(r: List<PowerTier>, spoken: Boolean): String {
        val unit = if (spoken) "킬로와트" else "kW"
        val first = r.first()
        val hi = upper(r.last())
        return when {
            first == PowerTier.UNDER_30 && hi == 30 -> if (spoken) "완속" else "완속(30kW 미만)"
            first == PowerTier.UNDER_30 && hi == null -> if (spoken) "모든 충전기" else "전체 구간"
            first == PowerTier.UNDER_30 -> "$hi$unit 미만"
            hi == null -> "${lower(first)}$unit 이상"
            else -> if (spoken) "${lower(first)}에서 $hi$unit" else "${lower(first)}~$hi$unit"
        }
    }
}
