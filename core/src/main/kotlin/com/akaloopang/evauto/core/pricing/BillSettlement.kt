package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.RecordSource
import java.time.YearMonth
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 관리비 고지서 = 아파트 충전의 영수증.
 *
 * 고지서의 월 전기차 충전 요금(원)을 그달 아파트 충전 기록에 충전량 비율로 나눈다.
 * 비율대로 나누면 소수점이 생기므로 "최대 잔여 배분(largest remainder)"으로 원 단위를 정해
 * 기록 합계가 고지서 금액과 정확히 같게 만든다. 고지서에 월 충전량(kWh)이 있으면
 * 충전량도 같은 방식(0.01kWh 단위)으로 맞춘다.
 *
 * 영수증으로 이미 확정된 기록(RECEIPT)은 건드리지 않는다. 아파트 충전기 중에는 사업자가
 * 운영해 알림톡 영수증이 오는 곳도 있기 때문이다 — 그 금액은 고지서 총액에서 빼고 나눈다.
 */
object BillSettlement {

    data class Result(
        val updated: List<ChargingRecord>,
        /** 정산 전 추정 금액 합계 */
        val estimatedTotal: Double,
        /** 고지서 금액 − 추정 합계 (양수면 추정이 적었다) */
        val difference: Double,
    )

    fun isTarget(r: ChargingRecord, month: YearMonth, provider: String = ProviderCatalog.APARTMENT): Boolean =
        r.provider == provider && YearMonth.from(r.date) == month

    /**
     * @param records 전체 기록 (대상만 골라 정산하고, 정산된 기록만 돌려준다)
     * @param billAmount 고지서의 월 충전 요금(원, 정수)
     * @param billKwh 고지서의 월 충전량(kWh) — 없으면 충전량은 그대로 둔다
     */
    fun settle(
        records: List<ChargingRecord>,
        month: YearMonth,
        billAmount: Long,
        billKwh: Double? = null,
        provider: String = ProviderCatalog.APARTMENT,
    ): Result {
        require(billAmount >= 0) { "고지서 금액은 0 이상이어야 합니다" }
        val inMonth = records.filter { isTarget(it, month, provider) }
        val fixed = inMonth.filter { it.amountStatus == AmountStatus.RECEIPT }
        val targets = inMonth.filter { it.amountStatus != AmountStatus.RECEIPT }.sortedBy { it.date }
        if (targets.isEmpty()) return Result(emptyList(), 0.0, billAmount.toDouble())

        val remainingAmount = (billAmount - fixed.sumOf { it.paymentAmount }.roundToLong()).coerceAtLeast(0)
        val estimatedTotal = targets.sumOf { it.paymentAmount }

        // 충전량: 고지서 kWh가 있으면 비율로 맞춘다(영수증 확정분 제외)
        val kwhList: List<Double> = if (billKwh != null && billKwh > 0) {
            val remainKwh = (billKwh - fixed.sumOf { it.kWh }).coerceAtLeast(0.0)
            val hundredths = distribute((remainKwh * 100).roundToLong(), targets.map { it.kWh })
            hundredths.map { it / 100.0 }
        } else targets.map { it.kWh }

        val amounts = distribute(remainingAmount, kwhList)
        val updated = targets.mapIndexed { i, r ->
            val kWh = kwhList[i]
            val amount = amounts[i].toDouble()
            val meta = (r.car ?: CarMeta(RecordSource.MANUAL, AmountStatus.BILL_SETTLED)).let {
                it.copy(
                    amountStatus = AmountStatus.BILL_SETTLED,
                    estimatedKwh = it.estimatedKwh ?: r.kWh.takeIf { _ -> r.isEstimated },
                    estimatedAmount = it.estimatedAmount ?: r.paymentAmount.takeIf { _ -> r.isEstimated },
                )
            }
            r.copy(
                kWh = kWh,
                paymentAmount = amount,
                unitPrice = AmountPolicy.round2(ChargingRecord.calcUnitPrice(kWh, amount)),
                car = meta,
            )
        }
        return Result(updated, estimatedTotal, billAmount - (estimatedTotal + fixed.sumOf { it.paymentAmount }))
    }

    /**
     * 정수 [total]을 [weights] 비율로 나눈다. 결과 합계는 항상 [total]과 같다.
     * 가중치가 모두 0이면 균등 분배. 나머지는 소수부가 큰 순(같으면 앞 순서)으로 1씩 준다.
     */
    fun distribute(total: Long, weights: List<Double>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val w = if (weights.all { it <= 0.0 }) weights.map { 1.0 } else weights.map { it.coerceAtLeast(0.0) }
        val sum = w.sum()
        val exact = w.map { total * it / sum }
        val base = exact.map { floor(it).toLong() }.toMutableList()
        var left = total - base.sum()
        val order = exact.indices.sortedWith(compareByDescending<Int> { exact[it] - floor(exact[it]) }.thenBy { it })
        var k = 0
        while (left > 0) {
            base[order[k % order.size]] += 1
            left--
            k++
        }
        return base
    }
}
