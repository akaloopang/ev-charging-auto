package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.pricing.AmountPolicy
import com.akaloopang.evauto.core.pricing.RoundingRule
import kotlin.math.roundToInt

/**
 * 충전 중 실시간 요금 미터. 급속충전을 기다리는 동안 차 안 화면에 보여준다.
 */
object LiveMeter {

    data class Reading(
        val socNow: Double?,
        /** 지금까지 충전량 (과금 기준 추정) */
        val kWhSoFar: Double,
        /** 지금까지 추정 금액 (단가 미설정이면 null) */
        val amountSoFar: Double?,
        val rateKw: Double?,
        val targetSoc: Int,
        /** 목표 SOC까지 남은 시간(분) — 출력·용량을 모르면 null */
        val minutesToTarget: Int?,
        /** 목표 SOC까지 추가될 추정 금액 */
        val amountToTarget: Double?,
        val reachedTarget: Boolean,
    )

    fun read(
        active: ActiveSession,
        latest: VehicleSnapshot,
        unitPrice: Double?,
        lossFactor: Double,
        /** 사용자가 설정한 배터리 용량 — 넣었으면 차량 값보다 먼저 */
        settingCapacityKwh: Double?,
        targetSoc: Int,
        rounding: RoundingRule = RoundingRule.HALF_UP_1,
    ): Reading {
        val capacity = settingCapacityKwh?.takeIf { it > 0 } ?: latest.batteryCapacityKwh ?: active.batteryCapacityKwh
        val socNow = latest.socPercent ?: active.socLatest
        val batterySoFar = when {
            active.integratedKwh > 0 -> active.integratedKwh
            socNow != null && active.socStart != null && capacity != null ->
                ((socNow - active.socStart) / 100.0 * capacity).coerceAtLeast(0.0)
            else -> 0.0
        }
        val kWhSoFar = AmountPolicy.round2(batterySoFar * lossFactor)
        val amountSoFar = AmountPolicy.estimateAmount(kWhSoFar, unitPrice, rounding) ?: if (unitPrice != null) 0.0 else null

        val rate = latest.chargeRateKw?.takeIf { it > VehicleSnapshot.MIN_CHARGE_RATE_KW }
        val remainingBattery = if (socNow != null && capacity != null) ((targetSoc - socNow) / 100.0 * capacity).coerceAtLeast(0.0) else null
        val minutes = if (remainingBattery != null && rate != null) (remainingBattery / rate * 60).roundToInt() else null
        val amountToTarget = if (remainingBattery != null && unitPrice != null) {
            AmountPolicy.estimateAmount(remainingBattery * lossFactor, unitPrice, rounding) ?: 0.0
        } else null

        return Reading(
            socNow = socNow,
            kWhSoFar = kWhSoFar,
            amountSoFar = amountSoFar,
            rateKw = rate,
            targetSoc = targetSoc,
            minutesToTarget = minutes,
            amountToTarget = amountToTarget,
            reachedTarget = socNow != null && socNow >= targetSoc,
        )
    }
}
