package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.StationRef

/** 길안내할 충전소를 고르는 기준 */
enum class StationPick(val key: String, val label: String) {
    CHEAPEST("cheapest", "가장 저렴했던 충전소"),
    MOST_VISITED("most_visited", "자주 가는 충전소"),
    LAST("last", "지난번 충전소"),
    NAMED("name", "이름으로 찾은 충전소");

    companion object {
        fun of(raw: String?): StationPick? {
            val k = raw?.lowercase()?.replace(" ", "_")?.replace("-", "_") ?: return null
            return entries.firstOrNull { it.key == k } ?: when (k) {
                "cheap", "lowest_price", "저렴한" -> CHEAPEST
                "frequent", "most_used", "favorite", "자주" -> MOST_VISITED
                "recent", "previous", "latest" -> LAST
                else -> null
            }
        }
    }
}

data class PickedStation(
    val station: StationRef,
    val pick: StationPick,
    val visits: Int,
    /** 그 충전소에서 낸 평균 단가(원/kWh, 단가 있는 기록만) */
    val avgUnitPrice: Double?,
)

/**
 * 내 충전 기록에서 길안내할 충전소 고르기 ("글레오, 충전 기록에서 제일 싼 충전소로 안내해 줘").
 * 좌표가 있는 충전소만 고른다 — 내비에 목적지로 넘겨야 하니까.
 */
object StationPicker {

    fun pick(
        records: List<ChargingRecord>,
        stations: List<StationRef>,
        how: StationPick,
        name: String? = null,
    ): PickedStation? {
        val byKey = stations.filter { it.location != null }.associateBy { it.key }
        val groups = records.filter { it.car?.stationKey in byKey }.groupBy { it.car!!.stationKey!! }
        fun picked(key: String, p: StationPick): PickedStation {
            val rs = groups[key].orEmpty()
            val priced = rs.filter(ChargingStats::priced)
            val kWh = priced.sumOf { it.kWh }
            return PickedStation(byKey.getValue(key), p, rs.size, if (kWh > 0) priced.sumOf { it.paymentAmount } / kWh else null)
        }
        return when (how) {
            StationPick.CHEAPEST -> groups.keys.map { picked(it, how) }
                .filter { it.avgUnitPrice != null }
                .minWithOrNull(compareBy<PickedStation> { it.avgUnitPrice }.thenByDescending { it.visits })
            StationPick.MOST_VISITED -> groups.keys.map { picked(it, how) }
                .maxWithOrNull(compareBy<PickedStation> { it.visits }.thenBy { -(it.avgUnitPrice ?: Double.MAX_VALUE) })
            StationPick.LAST -> records.filter { it.car?.stationKey in byKey }.maxByOrNull { it.date }
                ?.let { picked(it.car!!.stationKey!!, how) }
            StationPick.NAMED -> {
                val q = name?.let(ProviderCatalog::norm)?.takeIf { it.isNotEmpty() } ?: return null
                val hits = byKey.values.filter { st ->
                    listOfNotNull(st.name, st.address, st.displayName).any { ProviderCatalog.norm(it).contains(q) }
                }
                hits.map { picked(it.key, how) }.maxByOrNull { it.visits }
            }
        }
    }
}
