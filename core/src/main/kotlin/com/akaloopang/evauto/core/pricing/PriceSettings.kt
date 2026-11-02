package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerTier

/**
 * 사업자별 내 설정 단가(원/kWh).
 *
 * 2026-08 개편으로 같은 사업자라도 출력 5구간마다 요금이 다르다(예: SK일렉링크 회원가 30~50kW 295원, 50kW 이상 391원).
 * 그래서 구간마다 칸을 따로 두고([tierPrices]), 말이나 영수증으로 들어온 단가를 해당 구간 칸에 채운다.
 * 완속/급속/초급속 3칸([acUnitPrice] 등)은 예전 백업 호환용 — 구간 칸이 비어 있을 때만 쓴다.
 */
data class ProviderUnitPrice(
    val provider: String,
    val acUnitPrice: Double? = null,
    val dcUnitPrice: Double? = null,
    /** 초급속 (8/1 개편 대응, 미설정 시 급속 단가 사용) */
    val ultraUnitPrice: Double? = null,
    /** 출력 구간별 단가 — 있으면 위 3분류보다 우선 */
    val tierPrices: Map<PowerTier, Double> = emptyMap(),
) {
    /** 이 구간 단가: 구간 칸 → 예전 3분류 칸 */
    fun priceFor(tier: PowerTier): Double? = tierPrices[tier] ?: legacy(tier.chargeType)

    /** 예전 3분류 칸 */
    fun legacy(type: ChargeType?): Double? = when (type) {
        ChargeType.AC -> acUnitPrice
        ChargeType.DC -> dcUnitPrice
        ChargeType.ULTRA -> ultraUnitPrice ?: dcUnitPrice
        null -> acUnitPrice ?: dcUnitPrice
    }

    /** 여러 구간 칸에 같은 단가를 채운다 */
    fun withTiers(tiers: Collection<PowerTier>, unitPrice: Double): ProviderUnitPrice =
        copy(tierPrices = tierPrices + tiers.associateWith { unitPrice })

    /** 화면 입력용: 5구간 모두 칸 값으로 펼친다(예전 3분류 값도 해당 구간에 보여 준다) */
    fun expanded(): Map<PowerTier, Double> = PowerTier.entries.mapNotNull { t -> priceFor(t)?.let { t to it } }.toMap()
}

/**
 * 사업자 목록·별칭 정규화.
 */
object ProviderCatalog {
    const val APARTMENT = "아파트"
    const val OTHER = "기타"

    /** 공식 제공자 전체 목록 — 인프라 규모 + 사용자 수 기준 정렬 */
    val officialProviders: List<String> = listOf(
        APARTMENT,      // 자체 관리비 충전 (특수)
        "볼트업", "SK 일렉링크", "테슬라", "에버온", "EV 인프라",
        "GS 차지비", "환경부", "한전", "대영채비", "E-pit",
        "파워큐브", "플러그링크", "현대오일뱅크", "워터",
        OTHER,
    )

    /** 별칭(정규화된 소문자·공백/하이픈 제거) → 공식명 */
    val aliasMap: Map<String, String> = mapOf(
        "볼트업" to "볼트업", "bolttap" to "볼트업", "boltup" to "볼트업", "voltup" to "볼트업", "볼트" to "볼트업",
        "sk일렉링크" to "SK 일렉링크", "skelectlink" to "SK 일렉링크",
        "테슬라" to "테슬라", "tesla" to "테슬라", "supercharger" to "테슬라", "슈퍼차저" to "테슬라",
        "에버온" to "에버온", "everon" to "에버온",
        "ev인프라" to "EV 인프라", "evinfra" to "EV 인프라", "이브이인프라" to "EV 인프라",
        "gs차지비" to "GS 차지비", "gschargev" to "GS 차지비", "차지비" to "GS 차지비", "chargev" to "GS 차지비", "chargebi" to "GS 차지비",
        "환경부" to "환경부", "한국자동차환경협회" to "환경부", "kepca" to "환경부", "무공해차통합누리집" to "환경부",
        "한전" to "한전", "한국전력" to "한전", "한국전력공사" to "한전", "kepco" to "한전",
        "대영채비" to "대영채비", "chaevi" to "대영채비", "채비" to "대영채비",
        "epit" to "E-pit", "이피트" to "E-pit", "현대이피트" to "E-pit",
        "파워큐브" to "파워큐브", "powercube" to "파워큐브",
        "플러그링크" to "플러그링크", "pluglink" to "플러그링크",
        "현대오일뱅크" to "현대오일뱅크", "현대ev" to "현대오일뱅크",
        "워터" to "워터", "watt" to "워터", "wattinn" to "워터",
        "아파트" to APARTMENT,
    )

    fun norm(s: String): String = s.lowercase().replace(" ", "").replace("-", "")

    /**
     * 사업자명 → 공식명.
     * 1) 그대로 일치 2) 공백·대소문자 무시 일치 3) 별칭 4) 부분 포함
     */
    fun normalize(raw: String, providers: List<String> = officialProviders): String? {
        if (raw.isEmpty()) return null
        if (raw in providers) return raw
        val rawNorm = norm(raw)
        providers.firstOrNull { norm(it) == rawNorm }?.let { return it }
        aliasMap[rawNorm]?.let { if (it in providers) return it }
        providers.firstOrNull {
            val p = norm(it)
            p.isNotEmpty() && (rawNorm.contains(p) || p.contains(rawNorm))
        }?.let { return it }
        return null
    }

    /**
     * 충전소 이름(내비 목적지 등) 안에서 사업자를 찾는다. 예: "E-pit 고양 충전소" → "E-pit",
     * "채비 ○○휴게소" → "대영채비". 짧은 별칭이 긴 이름 일부와 겹치지 않도록 긴 별칭부터 본다.
     * '아파트'·'기타'는 이름으로 판정하지 않는다(장소 판정은 PlaceClassifier가 한다).
     */
    fun findInStationName(name: String?, providers: List<String> = officialProviders): String? {
        if (name.isNullOrBlank()) return null
        val n = norm(name)
        val candidates = providers.filter { it != APARTMENT && it != OTHER }
        candidates.sortedByDescending { norm(it).length }
            .firstOrNull { norm(it).length >= 2 && n.contains(norm(it)) }
            ?.let { return it }
        aliasMap.entries
            .filter { it.value != APARTMENT && it.value in providers }
            .sortedByDescending { it.key.length }
            .firstOrNull { it.key.length >= 2 && n.contains(it.key) }
            ?.let { return it.value }
        return null
    }

    /**
     * 저장된 목록 + 공식 제공자 병합.
     * 사용자 순서 보존, 별칭으로 이미 커버되는 공식명은 다시 넣지 않음, 빠진 공식명만 뒤에 추가.
     */
    fun mergeProviders(saved: List<String>?): List<String> {
        if (saved == null) return officialProviders.toList()
        val covered = mutableSetOf<String>()
        for (s in saved) {
            if (s in officialProviders) covered += s
            else aliasMap[norm(s)]?.let { if (it in officialProviders) covered += it }
        }
        return saved + officialProviders.filter { it !in covered }
    }
}

/**
 * 단가·비교 기준 설정.
 */
data class PriceSettings(
    val apartmentAcUnitPrice: Double? = null,
    val apartmentDcUnitPrice: Double? = null,
    val apartmentUltraUnitPrice: Double? = null,
    val providers: List<String> = ProviderCatalog.officialProviders,
    val providerPrices: List<ProviderUnitPrice> = emptyList(),
    /** 비교 대상 내연기관차 연비 (km/L) */
    val gasEfficiency: Double? = null,
    /** 연료 가격 (원/L) */
    val gasPrice: Double? = null,
) {
    val effectiveGasEfficiency: Double
        get() = gasEfficiency?.takeIf { it > 0 } ?: DEFAULT_GAS_EFFICIENCY
    val effectiveGasPrice: Double
        get() = gasPrice?.takeIf { it > 0 } ?: DEFAULT_GAS_PRICE

    val hasApartmentAcRate: Boolean get() = (apartmentAcUnitPrice ?: 0.0) > 0

    fun normalizeProvider(raw: String): String? = ProviderCatalog.normalize(raw, providers)

    fun getPriceFor(provider: String): ProviderUnitPrice? {
        val target = normalizeProvider(provider) ?: provider
        return providerPrices.firstOrNull { it.provider == target }
    }

    /**
     * 사업자·출력 구간 → 단가.
     * 구간을 알면 그 구간 칸, 모르면 충전방식(완속/급속/초급속) 칸. 초급속 미설정 시 급속으로 폴백.
     * 구간 칸만 있고 구간을 모르면, 그 충전방식의 구간들이 모두 같은 값일 때만 쓴다(추측하지 않는다).
     */
    fun getUnitPrice(provider: String, chargeType: ChargeType?, tier: PowerTier? = null): Double? {
        if (provider == ProviderCatalog.APARTMENT) {
            return when (tier?.chargeType ?: chargeType) {
                ChargeType.DC -> apartmentDcUnitPrice
                ChargeType.ULTRA -> apartmentUltraUnitPrice ?: apartmentDcUnitPrice
                else -> apartmentAcUnitPrice
            }
        }
        val price = getPriceFor(provider) ?: return null
        if (tier != null) return price.priceFor(tier)
        price.legacy(chargeType)?.let { return it }
        val tiers = if (chargeType == null) PowerTier.entries else PowerTier.entries.filter { it.chargeType == chargeType }
        return tiers.mapNotNull { price.tierPrices[it] }.distinct().singleOrNull()
    }

    companion object {
        const val DEFAULT_GAS_EFFICIENCY = 12.0 // km/L
        const val DEFAULT_GAS_PRICE = 1700.0    // 원/L
    }
}
