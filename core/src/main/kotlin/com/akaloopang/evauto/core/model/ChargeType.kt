package com.akaloopang.evauto.core.model

/**
 * 충전 방식 3분류.
 *
 * 2026-08-01 공공 충전요금이 출력 5단계로 세분화되면서 초급속(ULTRA)이 추가됐다.
 * 저장은 [key] 문자열("ac"/"dc"/"ultra")로 한다.
 */
enum class ChargeType(val key: String, val label: String, val speedName: String) {
    AC("ac", "AC 완속", "완속"),
    DC("dc", "DC 급속", "급속"),
    ULTRA("ultra", "DC 초급속", "초급속");

    companion object {
        /** 모르는 값(구버전·미래 버전)은 예외 대신 null — 복원 전체가 깨지지 않게. */
        fun fromKey(raw: String?): ChargeType? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 2026-08-01 공공 충전요금 출력 5단계 구간.
 * ⚠️ 저장하지 않는다. 기록에는 원시 출력(kW)만 저장하고 표시 시점에 계산한다.
 */
enum class PowerTier(val label: String, val speedName: String, val chargeType: ChargeType) {
    UNDER_30("30kW 미만", "완속", ChargeType.AC),
    T30_TO_50("30~50kW", "중속", ChargeType.DC),
    T50_TO_100("50~100kW", "급속", ChargeType.DC),
    T100_TO_200("100~200kW", "급속", ChargeType.DC),
    OVER_200("200kW 이상", "초급속", ChargeType.ULTRA);
}

/**
 * 출력(kW) → 충전 방식 판정.
 *
 * 세 종류의 kW를 구분한다.
 *  - 정격 출력(rated): 충전기 명판 값. 내비 충전소 정보(maxChargingPower)가 이것. 구간을 정확히 가른다.
 *  - 세션 평균 출력: 충전량 ÷ 충전시간. DC는 테이퍼링 때문에 정격보다 크게 낮다(실측 250kW → 평균 113kW).
 *  - 차량 관측 최대 출력(peak): 차가 받은 순간 출력의 최대값. 정격의 하한선이다.
 */
object PowerClassifier {
    /** AC 온보드 차저 상한 근사 (국내 완속 7kW, 일부 11kW). */
    const val AC_CEILING_KW = 10.0
    /** 차량이 실제로 받은 AC 최대 출력 상한 — 관측 peak 판정용(11kW 온보드 차저 + 측정 오차). */
    const val AC_OBSERVED_CEILING_KW = 12.0
    const val ULTRA_FLOOR_KW = 200.0

    fun tierFromRatedKw(ratedKw: Double?): PowerTier? {
        val kw = ratedKw ?: return null
        if (kw <= 0) return null
        return when {
            kw < 30 -> PowerTier.UNDER_30
            kw < 50 -> PowerTier.T30_TO_50
            kw < 100 -> PowerTier.T50_TO_100
            kw < 200 -> PowerTier.T100_TO_200
            else -> PowerTier.OVER_200
        }
    }

    fun chargeTypeFromRatedKw(ratedKw: Double?): ChargeType? = tierFromRatedKw(ratedKw)?.chargeType

    /** 세션 평균 출력으로는 확실한 구간만 제안한다. */
    fun suggestFromAverageKw(averageKw: Double?): ChargeType? {
        val kw = averageKw ?: return null
        if (kw <= 0) return null
        if (kw <= AC_CEILING_KW) return ChargeType.AC
        if (kw >= ULTRA_FLOOR_KW) return ChargeType.ULTRA
        return null
    }

    /**
     * 차량이 관측한 최대 충전 출력 → 충전 방식.
     *  - peak ≤ 12kW: AC 완속 (DC 충전기는 이렇게 낮은 출력으로 오래 충전하지 않는다)
     *  - peak ≥ 200kW: 초급속 (정격은 peak 이상이므로 확실)
     *  - 그 사이: DC. 초급속 충전기여도 배터리 온도·SOC 때문에 200 미만으로 받을 수 있어
     *    "DC 이상"만 확실하다 → 정격 정보(내비)가 있으면 그쪽을 우선한다.
     */
    fun fromObservedPeakKw(peakKw: Double?): ChargeType? {
        val kw = peakKw ?: return null
        if (kw <= 0) return null
        return when {
            kw <= AC_OBSERVED_CEILING_KW -> ChargeType.AC
            kw >= ULTRA_FLOOR_KW -> ChargeType.ULTRA
            else -> ChargeType.DC
        }
    }

    /**
     * 정격(내비) > 관측 peak 순으로 판정하되, 차가 실제로 AC 수준 출력만 받았다면 AC로 본다.
     * 내비 목적지가 충전소였어도 실제로는 다른 곳(집 완속)에서 충전했을 수 있기 때문이다.
     */
    fun decide(ratedKw: Double?, observedPeakKw: Double?): ChargeType? {
        val observed = fromObservedPeakKw(observedPeakKw)
        if (observed == ChargeType.AC) return ChargeType.AC
        return chargeTypeFromRatedKw(ratedKw) ?: observed
    }
}
