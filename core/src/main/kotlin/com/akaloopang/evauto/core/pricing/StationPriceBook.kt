package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.session.GeoPoint
import java.time.LocalDate
import java.util.Locale

/** 충전소 이름을 어디서 얻었나 */
enum class StationNameSource(val key: String) {
    /** 내비 목적지 충전소 이름 (공식 이름) */
    NAVI("navi"),
    /** 사용자가 직접 붙인 이름 */
    USER("user");

    companion object {
        fun fromKey(raw: String?): StationNameSource? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 충전소 식별 — GPS 좌표와 이름을 함께 둔다.
 *
 *  - 위치(GPS): 충전할 때 차가 있던 곳(지하주차장이면 진입 직전 위치). 같은 충전소를 알아보는 주 기준.
 *  - 이름: 내비 목적지 충전소 이름 > 사용자가 붙인 이름 > (없으면) 사업자 + 주소로 표시.
 *  - 주소: 위치로 찾은 도로명 주소(기기가 지원할 때). 이름이 없을 때 사람이 알아보는 용도.
 *
 * 같은 사업자도 충전소마다 단가가 다르다(집 근처 345원, 여행지 430원 등) — 그래서 확인 단가는 충전소별로 저장한다.
 */
data class StationRef(
    val key: String,
    val name: String? = null,
    val location: GeoPoint? = null,
    /** 운영 사업자 (앱 사업자명 또는 공시 사업자명) */
    val operator: String? = null,
    val address: String? = null,
    val nameSource: StationNameSource? = null,
) {
    /** 화면에 보여 줄 이름 */
    val displayName: String
        get() = name
            ?: address?.let { a -> operator?.let { "$it · $a" } ?: a }
            ?: operator?.let { "$it 충전소" }
            ?: "이름 없는 충전소"

    /** GPS 좌표 표시 (소수 5자리 ≈ 1m) */
    val coordinates: String?
        get() = location?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }

    /**
     * 같은 충전소인가.
     *  - 운영 사업자가 둘 다 알려져 있고 다르면 다른 충전소 (휴게소·주차장처럼 여러 사업자 충전기가 모인 곳)
     *  - 내비 이름이 둘 다 있고 다르면 다른 충전소 (가까이 붙은 두 충전소)
     *  - 그 외: 키가 같거나, 이름이 같거나, 위치가 [StationPriceBook.MATCH_RADIUS_M] 안
     */
    fun sameAs(other: StationRef): Boolean {
        if (key == other.key) return true
        val opA = operator?.let(::operatorKey)
        val opB = other.operator?.let(::operatorKey)
        if (opA != null && opB != null && opA != opB) return false
        val nameA = name?.let(ProviderCatalog::norm)
        val nameB = other.name?.let(ProviderCatalog::norm)
        if (nameA != null && nameA == nameB) return true
        if (nameSource == StationNameSource.NAVI && other.nameSource == StationNameSource.NAVI && nameA != nameB) return false
        val la = location
        val lb = other.location
        return la != null && lb != null && la.distanceTo(lb) <= StationPriceBook.MATCH_RADIUS_M
    }

    companion object {
        fun of(name: String?, location: GeoPoint?, operator: String?): StationRef? {
            val key = when {
                !name.isNullOrBlank() -> "name:" + ProviderCatalog.norm(name)
                location != null -> String.format(Locale.US, "geo:%.4f,%.4f", location.lat, location.lon)
                else -> return null
            }
            val n = name?.takeIf { it.isNotBlank() }
            return StationRef(key, n, location, operator, nameSource = n?.let { StationNameSource.NAVI })
        }

        /** 사업자 비교용: 별칭·공시 이름을 같은 값으로 ("채비" = "대영채비", "SK일렉링크" = "SK 일렉링크") */
        fun operatorKey(op: String): String =
            ProviderCatalog.normalize(op)?.let(ProviderCatalog::norm) ?: ProviderCatalog.norm(op)
    }
}

/** 이 충전소에서 확인한 단가 — 영수증·충전기 화면에서 한 번 입력하면 다음부터 자동 적용 */
data class StationPrice(
    val station: StationRef,
    /** 어느 출력 구간 충전기의 단가인가 (모르면 null) */
    val tier: PowerTier?,
    val chargeType: ChargeType?,
    val unitPrice: Double,
    val confirmedOn: LocalDate,
) {
    /** 무료 충전소 표시 (사용자가 고른 곳) — 구간·방식 없이 0원 하나로 저장한다 */
    val isFree: Boolean get() = unitPrice == 0.0 && tier == null && chargeType == null
}

/**
 * 충전소별 확인 단가 장부.
 *
 * 같은 충전소 판단: [StationRef.sameAs] (GPS 150m·이름·운영 사업자).
 * 같은 충전소라도 출력 구간마다 단가가 다르므로(2026-08 개편) 구간별로 따로 저장한다.
 */
data class StationPriceBook(val prices: List<StationPrice> = emptyList()) {

    fun sameStation(a: StationRef, b: StationRef): Boolean = a.sameAs(b)

    /** 충전소 이름·주소를 바꿨을 때 장부의 충전소 정보도 맞춘다 */
    fun rename(station: StationRef): StationPriceBook =
        copy(prices = prices.map { if (it.station.key == station.key) it.copy(station = station) else it })

    /**
     * 이 충전소·구간의 확인 단가.
     *
     *  - 구간이 정확하면(내비 정격·AC·200kW 이상 관측) 그 구간에 확인한 단가만 쓴다.
     *    같은 충전소라도 50kW와 100kW 충전기 요금이 다를 수 있어 다른 구간 값을 빌려 오지 않는다.
     *  - 구간이 하한값이면(차가 받은 최대 출력만 앎) 그 이상 구간에 확인한 단가가 모두 같을 때만 그것을 쓴다.
     *  - 구간을 모르고 저장했던 예전 값은 충전방식이 같으면 쓴다.
     */
    fun find(station: StationRef, tier: PowerTier?, chargeType: ChargeType?, tierExact: Boolean = true): StationPrice? {
        val here = prices.filter { sameStation(it.station, station) }
        if (here.isEmpty()) return null
        // 무료 충전소면 어느 충전기든 0원
        here.firstOrNull { it.isFree }?.let { return it }
        if (tier != null) {
            if (tierExact) {
                here.firstOrNull { it.tier == tier }?.let { return it }
            } else {
                val above = here.filter { p -> p.tier != null && p.tier.ordinal >= tier.ordinal }
                if (above.isNotEmpty() && above.map { it.unitPrice }.distinct().size == 1) {
                    return above.minBy { it.tier!!.ordinal }
                }
            }
            return here.filter { it.tier == null && it.chargeType == tier.chargeType }.maxByOrNull { it.confirmedOn }
        }
        if (chargeType != null) here.filter { it.chargeType == chargeType }.maxByOrNull { it.confirmedOn }?.let { return it }
        return here.takeIf { h -> h.map { it.unitPrice }.distinct().size == 1 }?.maxByOrNull { it.confirmedOn }
    }

    /**
     * 같은 사업자의 **다른** 충전소에서 확인한 단가 중 가장 최근 것 — 처음 가는 충전소에서 "지난번처럼 ○○원으로 할까요?"라고
     * 물을 때 쓴다. 출력 구간이 같아야 한다(50kW 충전기 단가를 100kW 충전기에 제안하지 않는다).
     *  - 구간이 정확하면 그 구간 값만
     *  - 구간이 하한값이면 그 이상 구간 값이 모두 같을 때만
     *  - 구간을 모르면 제안하지 않는다
     */
    fun findSameOperator(station: StationRef, operator: String?, tier: PowerTier?, tierExact: Boolean): StationPrice? {
        if (operator == null || tier == null) return null
        val op = StationRef.operatorKey(operator)
        val others = prices.filter { p ->
            val o = p.station.operator ?: return@filter false
            // 무료는 그 장소만의 사정 — 같은 사업자 다른 충전소에 0원을 제안하지 않는다
            !p.isFree && StationRef.operatorKey(o) == op && !sameStation(p.station, station)
        }
        if (others.isEmpty()) return null
        if (tierExact) return latest(others.filter { it.tier == tier })
        val above = others.filter { p -> p.tier != null && p.tier.ordinal >= tier.ordinal }
        return above.takeIf { a -> a.isNotEmpty() && a.map { it.unitPrice }.distinct().size == 1 }?.let(::latest)
    }

    /** 가장 최근에 확인한 것 (같은 날이면 나중에 넣은 것 — 장부는 넣은 순서대로 쌓인다) */
    private fun latest(ps: List<StationPrice>): StationPrice? {
        val day = ps.maxOfOrNull { it.confirmedOn } ?: return null
        return ps.last { it.confirmedOn == day }
    }

    /** 확인 단가 등록·갱신 (같은 충전소·같은 구간이면 교체) */
    fun learn(station: StationRef, tier: PowerTier?, chargeType: ChargeType?, unitPrice: Double, on: LocalDate): StationPriceBook {
        require(unitPrice > 0) { "단가는 0보다 커야 합니다" }
        val type = tier?.chargeType ?: chargeType
        // 무료였던 충전소에 단가가 생기면(유료 전환) 무료 표시는 지운다
        val keep = prices.filterNot {
            sameStation(it.station, station) && (it.isFree || (it.tier == tier && (tier != null || it.chargeType == type)))
        }
        return copy(prices = keep + StationPrice(station, tier, type, unitPrice, on))
    }

    /** 여러 구간에 같은 확인 단가 (사업자가 그 구간들을 같은 요금으로 받는 경우 — [TierPlanner]가 정한다) */
    fun learn(station: StationRef, tiers: Collection<PowerTier>, unitPrice: Double, on: LocalDate): StationPriceBook =
        tiers.distinct().fold(this) { book, t -> book.learn(station, t, t.chargeType, unitPrice, on) }

    /** 화면 표시용: 충전소별로 같은 단가 구간을 묶는다 */
    fun grouped(): List<Group> = prices
        .groupBy { Triple(it.station.key, it.unitPrice, it.confirmedOn) }
        .map { (_, ps) -> Group(ps.first().station, ps.mapNotNull { it.tier }.sortedBy { it.ordinal }, ps.first().chargeType, ps.first().unitPrice, ps.first().confirmedOn) }
        .sortedWith(compareBy<Group>({ it.station.name ?: it.station.key }, { it.tiers.firstOrNull()?.ordinal ?: -1 }))

    data class Group(val station: StationRef, val tiers: List<PowerTier>, val chargeType: ChargeType?, val unitPrice: Double, val confirmedOn: LocalDate) {
        /** 무료 충전소 표시 (모든 충전기 0원) */
        val isFree: Boolean get() = unitPrice == 0.0 && tiers.isEmpty() && chargeType == null
    }

    fun remove(station: StationRef): StationPriceBook = copy(prices = prices.filterNot { sameStation(it.station, station) })

    /** 무료 충전소로 표시 (요금을 받지 않는 곳, 선택 기능). 그 충전소의 구간별 단가는 지우고 0원 하나만 남긴다 */
    fun markFree(station: StationRef, on: LocalDate): StationPriceBook =
        copy(prices = prices.filterNot { sameStation(it.station, station) } + StationPrice(station, null, null, 0.0, on))

    fun isFree(station: StationRef): Boolean = prices.any { it.isFree && sameStation(it.station, station) }

    companion object {
        /** 대형 주차장·GPS 오차를 고려한 같은 충전소 반경 */
        const val MATCH_RADIUS_M = 150.0
    }
}
