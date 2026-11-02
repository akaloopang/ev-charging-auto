package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import java.time.YearMonth

/** 월간 요약 — 차량 화면 리포트용 */
data class MonthSummary(
    val month: YearMonth,
    val count: Int,
    val totalKwh: Double,
    val totalAmount: Double,
    val apartmentCount: Int,
    val apartmentAmount: Double,
    val receiptCount: Int,
    val billSettledCount: Int,
    val estimatedCount: Int,
    /** 확인(1탭)이 필요한 기록 수 */
    val pendingConfirmCount: Int,
    /** 무료 충전소 기록 (0원이 확실하다) */
    val freeCount: Int = 0,
) {
    /** 금액이 확정된 비율 (영수증 + 관리비 정산 + 무료 충전소) */
    val settledRatio: Double get() = if (count == 0) 1.0 else (receiptCount + billSettledCount + freeCount).toDouble() / count

    companion object {
        fun of(records: List<ChargingRecord>, month: YearMonth): MonthSummary {
            val m = records.filter { YearMonth.from(it.date) == month }
            val apt = m.filter { it.provider == ProviderCatalog.APARTMENT }
            return MonthSummary(
                month = month,
                count = m.size,
                totalKwh = m.sumOf { it.kWh },
                totalAmount = m.sumOf { it.paymentAmount },
                apartmentCount = apt.size,
                apartmentAmount = apt.sumOf { it.paymentAmount },
                receiptCount = m.count { it.amountStatus == AmountStatus.RECEIPT },
                billSettledCount = m.count { it.amountStatus == AmountStatus.BILL_SETTLED },
                estimatedCount = m.count { it.amountStatus == AmountStatus.ESTIMATED && !it.isFree },
                pendingConfirmCount = m.count { it.car != null && !it.car.confirmed },
                freeCount = m.count { it.isFree && it.amountStatus == AmountStatus.ESTIMATED },
            )
        }
    }
}
