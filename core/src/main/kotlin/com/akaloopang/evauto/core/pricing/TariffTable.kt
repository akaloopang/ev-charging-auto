package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.PowerTier
import java.time.LocalDate

/**
 * 사업자별 공시 요금표 — 무공해차 통합누리집(ev.or.kr) '전기차 충전요금 안내'의 사업자별 요금 매트릭스.
 *
 * 2026-08-01 개편 이후 공공·민간 사업자가 용량 5구간(30kW 미만 / 30~49 / 50~99 / 100~199 / 200 이상)별
 * 회원가·비회원가를 등재한다. 현재 공식 오픈 API는 없어서, 이 표를 수집일 기준 스냅샷으로 앱에 넣고
 * 갱신 주기마다 교체한다. 이 값은 '안내가'라서 현장 단가·프로모션·로밍과 다를 수 있다 →
 * 충전소에서 확인한 단가([StationPriceBook])가 있으면 그것이 항상 우선한다.
 */
data class TariffEntry(
    val operator: String,
    val member: Map<PowerTier, Double>,
    val nonMember: Map<PowerTier, Double>,
    /** 사업자가 요금을 마지막으로 갱신한 날 */
    val updated: LocalDate,
)

class TariffTable(
    val entries: List<TariffEntry>,
    /** 표를 수집한 날 */
    val collectedOn: LocalDate,
    val source: String = SOURCE,
) {
    private val byNorm: Map<String, TariffEntry> = entries.associateBy { ProviderCatalog.norm(it.operator) }

    /** 앱 사업자 이름(별칭 포함) 또는 공시 사업자 이름 → 요금표 항목 */
    fun find(operator: String?): TariffEntry? {
        if (operator.isNullOrBlank()) return null
        CATALOG_TO_OFFICIAL[operator]?.let { byNorm[ProviderCatalog.norm(it)] }?.let { return it }
        ProviderCatalog.normalize(operator)?.let { canonical ->
            CATALOG_TO_OFFICIAL[canonical]?.let { byNorm[ProviderCatalog.norm(it)] }?.let { return it }
        }
        return byNorm[ProviderCatalog.norm(operator)]
    }

    /**
     * 단가 조회.
     * @param allowHigherTier 구간이 '하한값'일 때(차량이 받은 최대 출력으로만 추정) 그 구간 요금이 없으면
     *   더 높은 구간 요금을 쓴다. 정격을 아는 경우에는 쓰지 않는다.
     */
    fun price(operator: String?, tier: PowerTier, member: Boolean = true, allowHigherTier: Boolean = false): Double? {
        val e = find(operator) ?: return null
        val table = if (member) e.member else e.nonMember
        table[tier]?.let { return it }
        if (!allowHigherTier) return null
        return PowerTier.entries.filter { it.ordinal > tier.ordinal }.firstNotNullOfOrNull { table[it] }
    }

    /** 충전소 이름 안에서 공시 사업자 이름을 찾는다 (예: "티맵 ○○ 충전소" → 티맵). 긴 이름부터. */
    fun findOperatorInName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val n = ProviderCatalog.norm(name)
        return entries.map { it.operator }
            .filter { ProviderCatalog.norm(it).length >= 2 }
            .sortedByDescending { it.length }
            .firstOrNull { n.contains(ProviderCatalog.norm(it)) }
    }

    companion object {
        const val SOURCE = "무공해차 통합누리집 전기차 충전요금 안내(사업자별 요금 매트릭스, 안내가)"
        const val BUNDLED = "/tariff/ev_tariff_2026-10-22.csv"

        /** 앱 사업자 목록 이름 → 공시 사업자 이름. 공시에 없는 사업자(테슬라 등)는 넣지 않는다. */
        val CATALOG_TO_OFFICIAL: Map<String, String> = mapOf(
            "볼트업" to "LG유플러스 볼트업",
            "SK 일렉링크" to "SK일렉링크",
            "에버온" to "에버온",
            "GS 차지비" to "GS차지비",
            "환경부" to "기후에너지환경부",
            "한전" to "한국전력공사",
            "대영채비" to "채비",
            "E-pit" to "현대자동차",
            "파워큐브" to "파워큐브",
            "플러그링크" to "플러그링크",
        )

        /**
         * CSV: operator,회원 5구간,비회원 5구간,updated. '#'으로 시작하는 줄은 주석. 빈 칸 = 미등재.
         */
        fun parse(csv: String, collectedOn: LocalDate): TariffTable {
            val tiers = PowerTier.entries
            val entries = csv.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val c = line.split(',')
                    if (c.size < 12) return@mapNotNull null
                    fun cell(i: Int) = c[i].trim().toDoubleOrNull()
                    TariffEntry(
                        operator = c[0].trim(),
                        member = tiers.mapIndexedNotNull { i, t -> cell(1 + i)?.let { t to it } }.toMap(),
                        nonMember = tiers.mapIndexedNotNull { i, t -> cell(6 + i)?.let { t to it } }.toMap(),
                        updated = runCatching { LocalDate.parse(c[11].trim()) }.getOrDefault(collectedOn),
                    )
                }
                .toList()
            return TariffTable(entries, collectedOn)
        }

        /** 앱에 들어 있는 스냅샷 */
        fun loadBundled(): TariffTable? {
            val stream = TariffTable::class.java.getResourceAsStream(BUNDLED) ?: return null
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val date = Regex("\\d{4}-\\d{2}-\\d{2}").find(BUNDLED)?.value?.let(LocalDate::parse) ?: LocalDate.now()
            return parse(text, date)
        }
    }
}
