package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.pricing.BillSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BillSettlementTest {

    private val oct = YearMonth.of(2026, 10)

    @Test fun distribute_always_sums_to_total() {
        val parts = BillSettlement.distribute(47_320, listOf(38.0, 12.5, 7.25, 40.1, 3.3))
        assertEquals(47_320L, parts.sum())
        // 3등분 1원 남는 경우
        assertEquals(listOf(34L, 33L, 33L), BillSettlement.distribute(100, listOf(1.0, 1.0, 1.0)))
        assertEquals("가중치가 모두 0이면 균등", 100L, BillSettlement.distribute(100, listOf(0.0, 0.0)).sum())
    }

    @Test fun apartment_month_matches_bill_to_the_won() {
        val records = listOf(
            rec("1", "2026-10-02T22:10:00", kWh = 38.0, pay = 9_500.0, status = AmountStatus.ESTIMATED),
            rec("2", "2026-10-09T23:40:00", kWh = 21.35, pay = 5_338.0, status = AmountStatus.ESTIMATED),
            rec("3", "2026-10-17T21:05:00", kWh = 44.6, pay = 11_150.0, status = AmountStatus.ESTIMATED),
            rec("x", "2026-09-30T22:00:00", kWh = 30.0, pay = 7_500.0, status = AmountStatus.ESTIMATED), // 지난달
            rec("p", "2026-10-05T13:00:00", kWh = 25.0, pay = 8_500.0, provider = "E-pit", status = AmountStatus.ESTIMATED),
        )
        val res = BillSettlement.settle(records, oct, billAmount = 27_013)
        assertEquals("이번 달 아파트 3건만", 3, res.updated.size)
        assertEquals(27_013.0, res.updated.sumOf { it.paymentAmount }, 0.0)
        assertTrue(res.updated.all { it.amountStatus == AmountStatus.BILL_SETTLED })
        assertTrue("원 단위 정수", res.updated.all { it.paymentAmount % 1.0 == 0.0 })
        assertEquals("추정 합계 대비 차이", 27_013.0 - (9_500 + 5_338 + 11_150), res.difference, 0.0)
        assertEquals("추정값 보존", 9_500.0, res.updated.first { it.id == "1" }.car!!.estimatedAmount!!, 0.0)
    }

    @Test fun bill_kwh_is_matched_too() {
        val records = listOf(
            rec("1", "2026-10-02T22:10:00", kWh = 38.0, pay = 9_500.0, status = AmountStatus.ESTIMATED),
            rec("2", "2026-10-09T23:40:00", kWh = 21.35, pay = 5_338.0, status = AmountStatus.ESTIMATED),
        )
        val res = BillSettlement.settle(records, oct, billAmount = 15_100, billKwh = 61.07)
        assertEquals(61.07, res.updated.sumOf { it.kWh }, 1e-9)
        assertEquals(15_100.0, res.updated.sumOf { it.paymentAmount }, 0.0)
    }

    @Test fun receipt_confirmed_apartment_record_is_untouched_and_subtracted() {
        val records = listOf(
            rec("r", "2026-10-03T22:00:00", kWh = 30.0, pay = 7_777.0, status = AmountStatus.RECEIPT),
            rec("1", "2026-10-10T22:00:00", kWh = 20.0, pay = 5_000.0, status = AmountStatus.ESTIMATED),
            rec("2", "2026-10-11T22:00:00", kWh = 20.0, pay = 5_000.0, status = AmountStatus.ESTIMATED),
        )
        val res = BillSettlement.settle(records, oct, billAmount = 17_777)
        assertEquals(2, res.updated.size)
        assertEquals("고지서 − 영수증 확정분을 나눈다", 10_000.0, res.updated.sumOf { it.paymentAmount }, 0.0)
    }
}
