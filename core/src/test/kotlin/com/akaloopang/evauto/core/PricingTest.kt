package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerClassifier
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.AmountPolicy
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.Receipt
import com.akaloopang.evauto.core.pricing.RoundingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {

    // ── 사업자 정규화 ──
    @Test fun normalize_alias_and_spacing() {
        assertEquals("SK 일렉링크", ProviderCatalog.normalize("SK일렉링크"))
        assertEquals("대영채비", ProviderCatalog.normalize("채비"))
        assertEquals("GS 차지비", ProviderCatalog.normalize("차지비"))
        assertEquals("E-pit", ProviderCatalog.normalize("epit"))
        assertEquals("테슬라", ProviderCatalog.normalize("Supercharger"))
        assertNull(ProviderCatalog.normalize(""))
    }

    @Test fun station_name_to_provider() {
        assertEquals("E-pit", ProviderCatalog.findInStationName("E-pit 고양 충전소"))
        assertEquals("대영채비", ProviderCatalog.findInStationName("채비 안성휴게소(서울방향)"))
        assertEquals("테슬라", ProviderCatalog.findInStationName("Tesla Supercharger 하남"))
        assertEquals("SK 일렉링크", ProviderCatalog.findInStationName("SK일렉링크 판교"))
        assertNull(ProviderCatalog.findInStationName("행복마트 주차장"))
        assertNull("이름으로 아파트를 판정하지 않는다", ProviderCatalog.findInStationName("래미안 아파트"))
    }

    @Test fun merge_providers_keeps_user_order_and_renames() {
        val merged = ProviderCatalog.mergeProviders(listOf("채비", "아파트", "내 충전기"))
        assertEquals(listOf("채비", "아파트", "내 충전기"), merged.take(3))
        assertFalse("별칭으로 커버된 공식명은 다시 넣지 않는다", merged.contains("대영채비"))
        assertTrue(merged.contains("E-pit"))
    }

    @Test fun unit_price_fallbacks() {
        val s = PriceSettings(
            apartmentAcUnitPrice = 250.0, apartmentDcUnitPrice = 300.0,
            providerPrices = listOf(ProviderUnitPrice("E-pit", acUnitPrice = 280.0, dcUnitPrice = 340.0)),
        )
        assertEquals(250.0, s.getUnitPrice("아파트", ChargeType.AC)!!, 0.0)
        assertEquals("아파트 초급속 미설정 → 급속", 300.0, s.getUnitPrice("아파트", ChargeType.ULTRA)!!, 0.0)
        assertEquals("초급속 미설정 → 급속", 340.0, s.getUnitPrice("E-pit", ChargeType.ULTRA)!!, 0.0)
        assertEquals("별칭으로도 찾는다", 280.0, s.getUnitPrice("이피트", ChargeType.AC)!!, 0.0)
        assertNull(s.getUnitPrice("볼트업", ChargeType.AC))
    }

    // ── 출력 → 충전방식 ──
    @Test fun power_tiers_boundaries() {
        assertEquals(PowerTier.UNDER_30, PowerClassifier.tierFromRatedKw(7.0))
        assertEquals(PowerTier.T30_TO_50, PowerClassifier.tierFromRatedKw(30.0))
        assertEquals(PowerTier.T100_TO_200, PowerClassifier.tierFromRatedKw(199.9))
        assertEquals(PowerTier.OVER_200, PowerClassifier.tierFromRatedKw(200.0))
        assertNull(PowerClassifier.tierFromRatedKw(0.0))
    }

    @Test fun observed_peak_classification() {
        assertEquals(ChargeType.AC, PowerClassifier.fromObservedPeakKw(6.8))
        assertEquals(ChargeType.DC, PowerClassifier.fromObservedPeakKw(95.0))
        assertEquals(ChargeType.ULTRA, PowerClassifier.fromObservedPeakKw(235.0))
        assertEquals("정격(내비)이 있으면 정격 우선", ChargeType.ULTRA, PowerClassifier.decide(350.0, 150.0))
        assertEquals("실제로 AC만 받았으면 내비 목적지와 무관하게 AC", ChargeType.AC, PowerClassifier.decide(350.0, 7.0))
    }

    // ── 영수증이 최종값 ──
    @Test fun receipt_amount_is_final_and_not_recomputed() {
        val est = rec("a", "2026-10-20T09:00:00", kWh = 32.15, pay = 10_931.0, provider = "E-pit",
            status = AmountStatus.ESTIMATED, type = ChargeType.DC)
        // 영수증: 31.98kWh, 단가 340원 → 10,873.2원이지만 사업자가 절사해 10,873원 결제
        val r = AmountPolicy.applyReceipt(est, Receipt(amount = 10_873.0, kWh = 31.98, unitPrice = 340.0))
        assertEquals(10_873.0, r.paymentAmount, 0.0)
        assertEquals(31.98, r.kWh, 0.0)
        assertEquals(340.0, r.unitPrice, 0.0)
        assertEquals(AmountStatus.RECEIPT, r.amountStatus)
        assertEquals("차량 추정값은 보존", 32.15, r.car!!.estimatedKwh!!, 0.0)
        assertEquals(10_931.0, r.car!!.estimatedAmount!!, 0.0)
    }

    @Test fun receipt_without_kwh_reverse_calculates_like_phone_app() {
        val est = rec("a", "2026-10-20T09:00:00", kWh = 20.0, pay = 6_800.0, provider = "볼트업", status = AmountStatus.ESTIMATED)
        val r = AmountPolicy.applyReceipt(est, Receipt(amount = 6_546.0), settingsUnitPrice = 318.0)
        assertEquals(6_546.0, r.paymentAmount, 0.0)
        assertEquals("6546 ÷ 318 = 20.58", 20.58, r.kWh, 0.0)
        assertEquals("단가는 설정값 그대로 (역산 반올림 오차 방지)", 318.0, r.unitPrice, 0.0)
    }

    @Test fun rounding_rules() {
        assertEquals(10_873.0, RoundingRule.FLOOR_1.apply(10_873.6), 0.0)
        assertEquals(10_874.0, RoundingRule.HALF_UP_1.apply(10_873.5), 0.0)
        assertEquals(10_870.0, RoundingRule.FLOOR_10.apply(10_879.9), 0.0)
        assertNull(AmountPolicy.estimateAmount(10.0, null))
    }

    @Test fun status_priority() {
        assertTrue(AmountPolicy.canOverride(AmountStatus.ESTIMATED, AmountStatus.BILL_SETTLED))
        assertTrue(AmountPolicy.canOverride(AmountStatus.BILL_SETTLED, AmountStatus.RECEIPT))
        assertFalse("관리비 정산이 영수증을 덮으면 안 된다", AmountPolicy.canOverride(AmountStatus.RECEIPT, AmountStatus.BILL_SETTLED))
    }
}
