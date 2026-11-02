package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord

/**
 * 차량 데이터 → 과금 기준 충전량(kWh) 추정.
 *
 * 배터리에 들어간 에너지와 충전기가 계량(과금)한 에너지는 다르다(충전 손실). 그래서
 *   과금 kWh ≈ 배터리 kWh × 손실계수
 * 로 추정하고, 손실계수는 영수증·관리비 정산으로 확정된 기록에서 학습한다.
 */
object EnergyEstimator {

    /** 적분값을 믿을 수 있는 최소 커버리지 */
    const val MIN_COVERAGE = 0.8

    data class Estimate(
        /** 배터리 측 에너지 */
        val batteryKwh: Double,
        /** 과금 기준 추정 (손실 보정 후) */
        val billedKwh: Double,
        val method: Method,
    )

    enum class Method { INTEGRATED, SOC_DELTA, PARTIAL_INTEGRATED }

    /**
     * @param settingCapacityKwh 사용자가 설정한 배터리 용량 — 넣었으면 차량 값보다 먼저 쓴다
     *   (차종·에뮬레이터가 용량을 엉뚱한 단위나 기본값으로 주는 경우가 있어, 사용자가 확인한 값을 믿는다)
     */
    fun estimate(session: ChargeSession, lossFactor: Double, settingCapacityKwh: Double?): Estimate? {
        val integrated = session.integratedKwh
        val capacity = settingCapacityKwh?.takeIf { it > 0 } ?: session.batteryCapacityKwh
        val rise = session.socRise
        val (battery, method) = when {
            integrated != null && integrated > 0 && session.integrationCoverage >= MIN_COVERAGE ->
                integrated to Method.INTEGRATED
            rise != null && rise > 0 && capacity != null && capacity > 0 ->
                (rise / 100.0 * capacity) to Method.SOC_DELTA
            integrated != null && integrated > 0 ->
                integrated to Method.PARTIAL_INTEGRATED
            else -> return null
        }
        return Estimate(battery, battery * lossFactor, method)
    }
}

/**
 * 충전 손실계수 학습.
 *
 * 영수증(또는 관리비 정산)으로 확정된 기록 중 차량이 배터리 측 에너지를 남긴 기록에서
 *   계수 = 확정 kWh ÷ 배터리 kWh
 * 를 모아 충전방식별 중앙값을 쓴다. 표본이 적으면 기본값.
 *
 * 기본값은 일반적인 충전 손실 수준을 가정한 시작값이며, 영수증이 쌓이면 실측값으로 바뀐다.
 */
object LossCalibrator {
    val DEFAULTS: Map<ChargeType, Double> = mapOf(
        ChargeType.AC to 1.10,
        ChargeType.DC to 1.05,
        ChargeType.ULTRA to 1.05,
    )
    const val MIN_SAMPLES = 3
    const val MAX_SAMPLES = 20
    const val MIN_FACTOR = 1.0
    const val MAX_FACTOR = 1.35

    fun factorFor(chargeType: ChargeType?, records: List<ChargingRecord>): Double {
        val type = chargeType ?: ChargeType.AC
        val ratios = records
            .asSequence()
            .filter { it.chargeType == type }
            .filter { it.amountStatus == AmountStatus.RECEIPT || it.amountStatus == AmountStatus.BILL_SETTLED }
            .mapNotNull { r ->
                val battery = r.car?.batteryKwh ?: return@mapNotNull null
                if (battery <= 0 || r.kWh <= 0) null else r.date to r.kWh / battery
            }
            .filter { it.second in 0.8..1.6 } // 오입력·센서 이상치 제외
            .sortedByDescending { it.first }
            .take(MAX_SAMPLES)
            .map { it.second }
            .toList()
        if (ratios.size < MIN_SAMPLES) return DEFAULTS.getValue(type)
        return median(ratios).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}
