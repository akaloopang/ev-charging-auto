package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceResolver
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.RoundingRule
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.pricing.TierEstimate
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PricingEngineTest {

    private val tariff = TariffTable.loadBundled()!!
    private val car = CarSettings(batteryCapacityKwh = 77.4, home = HomeZone(GeoPoint(HOME_LAT, HOME_LON)))

    /** 판교 근처 임의의 공용 충전소 위치 */
    private val stationLoc = GeoPoint(37.4012, 127.1086)

    private fun publicSession(start: String, loc: GeoPoint?, peak: Double = 95.0) = ChargeSession(
        source = RecordSource.CAR_LIVE, start = t(start), end = t(start).plusMinutes(30),
        socStart = 30.0, socEnd = 60.0, odometerStart = 1000.0, odometerEnd = 1000.0, peakKw = peak,
        batteryCapacityKwh = 77.4, location = loc,
    )

    // ── 공시 요금표 ──
    @Test fun bundled_tariff_loads_all_operators() {
        assertEquals(102, tariff.entries.size)
        assertEquals(LocalDate.of(2026, 10, 22), tariff.collectedOn)
        assertEquals("E-pit = 현대자동차 100~199kW 회원가", 460.0, tariff.price("E-pit", PowerTier.T100_TO_200)!!, 0.0)
        assertEquals(350.0, tariff.price("볼트업", PowerTier.OVER_200)!!, 0.0)
        assertEquals("비회원가", 450.0, tariff.price("볼트업", PowerTier.OVER_200, member = false)!!, 0.0)
        assertEquals(391.0, tariff.price("SK 일렉링크", PowerTier.T50_TO_100)!!, 0.0)
        assertEquals("앱 별칭 '채비' → 공시 '채비'", 410.0, tariff.price("채비", PowerTier.T50_TO_100)!!, 0.0)
        assertEquals("공시 이름 그대로도 찾는다", 420.0, tariff.price("티맵", PowerTier.OVER_200)!!, 0.0)
        assertNull("공시에 없는 사업자", tariff.price("테슬라", PowerTier.OVER_200))
    }

    @Test fun higher_tier_only_when_tier_is_a_lower_bound() {
        // SK시그넷: 50~99kW 미등재, 100~199kW 347원
        assertNull(tariff.price("SK시그넷", PowerTier.T50_TO_100, allowHigherTier = false))
        assertEquals(347.0, tariff.price("SK시그넷", PowerTier.T50_TO_100, allowHigherTier = true)!!, 0.0)
    }

    @Test fun tier_estimate_from_rated_or_peak() {
        assertEquals(TierEstimate(PowerTier.OVER_200, true), TierEstimate.of(350.0, 120.0, null))
        assertEquals("관측 최대 95kW → 정격은 그 이상, 하한값", TierEstimate(PowerTier.T50_TO_100, false), TierEstimate.of(null, 95.0, ChargeType.DC))
        assertEquals(TierEstimate(PowerTier.UNDER_30, true), TierEstimate.of(null, 6.9, null))
    }

    // ── 처음 간 충전소: 공시 안내가 + '영수증 단가 1회 입력' 안내 → 입력 후 자동 ──
    @Test fun first_visit_uses_official_then_station_price_after_one_receipt() {
        var pricing = PricingContext(PriceSettings(), StationPriceBook(), tariff)
        val navi = NaviChargingStation("SK일렉링크 판교점", maxChargingPowerKw = 100)

        val first = RecordFactory.build(publicSession("2026-10-22T12:00:00", stationLoc), pricing, car, emptyList(), navi)
        assertEquals("SK 일렉링크", first.record.provider)
        assertEquals(PriceSource.OFFICIAL_TARIFF, first.price.source)
        assertEquals(PowerTier.T100_TO_200, first.price.tier)
        assertEquals(391.0, first.record.unitPrice, 0.0)
        assertTrue("처음 간 충전소 → 영수증 단가 1회 입력 안내", first.record.car!!.askStationPrice)
        assertNotNull(first.station)

        // 영수증에 찍힌 실제 단가 385원을 한 번 입력
        pricing = pricing.copy(book = RecordFactory.learnStationPrice(pricing.book, first.record, first.station!!, 385.0, LocalDate.of(2026, 10, 22)))

        // 다음 방문: 내비 없이 와도 위치(50m 이내)로 같은 충전소를 알아본다
        val near = GeoPoint(stationLoc.lat + 0.0004, stationLoc.lon)
        val second = RecordFactory.build(publicSession("2026-10-29T12:00:00", near, peak = 140.0), pricing, car, emptyList())
        assertEquals(PriceSource.STATION_CONFIRMED, second.price.source)
        assertEquals("내비 없이 와도 등록 때의 사업자", "SK 일렉링크", second.record.provider)
        assertEquals(385.0, second.record.unitPrice, 0.0)
        assertFalse(second.record.car!!.askStationPrice)
        // SOC 30→60% × 77.4 = 23.22kWh × 1.05(급속 기본 손실계수) = 24.38kWh × 385원 = 9,386.3 → 9,386원
        assertEquals(24.38, second.record.kWh, 1e-9)
        assertEquals(9_386.0, second.record.paymentAmount, 0.0)
    }

    @Test fun station_price_is_per_power_tier() {
        val st = StationRef.of("E-pit 고양", stationLoc, "E-pit")!!
        val book = StationPriceBook()
            .learn(st, PowerTier.T100_TO_200, ChargeType.DC, 460.0, LocalDate.of(2026, 10, 1))
            .learn(st, PowerTier.OVER_200, ChargeType.ULTRA, 500.0, LocalDate.of(2026, 10, 1))
        assertEquals(500.0, book.find(st, PowerTier.OVER_200, ChargeType.ULTRA)!!.unitPrice, 0.0)
        assertEquals(460.0, book.find(st, PowerTier.T100_TO_200, ChargeType.DC)!!.unitPrice, 0.0)
        // 같은 충전소·구간을 다시 확인하면 교체
        val updated = book.learn(st, PowerTier.OVER_200, ChargeType.ULTRA, 510.0, LocalDate.of(2026, 11, 1))
        assertEquals(2, updated.prices.size)
        assertEquals(510.0, updated.find(st, PowerTier.OVER_200, null)!!.unitPrice, 0.0)
        assertNull("다른 충전소는 영향 없음", book.find(StationRef.of(null, GeoPoint(35.1, 129.0), null)!!, PowerTier.OVER_200, null))
    }

    @Test fun station_price_flagged_when_operator_tariff_changed_after_confirmation() {
        // GS차지비 공시 갱신일 2026-09-22 — 그 전에 확인한 단가는 '변경 확인 필요'
        val st = StationRef.of("GS차지비 강남", stationLoc, "GS 차지비")!!
        val book = StationPriceBook().learn(st, PowerTier.T50_TO_100, ChargeType.DC, 320.0, LocalDate.of(2026, 9, 1))
        val r = PriceResolver(PriceSettings(), book, tariff).resolve(
            PriceQuery("GS 차지비", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(PowerTier.T50_TO_100, true), st),
        )
        assertEquals(PriceSource.STATION_CONFIRMED, r.source)
        assertTrue(r.possiblyOutdated)
    }

    @Test fun my_setting_beats_official_but_not_station() {
        val settings = PriceSettings(providerPrices = listOf(com.akaloopang.evauto.core.pricing.ProviderUnitPrice("볼트업", dcUnitPrice = 300.0)))
        val st = StationRef.of("볼트업 마트", stationLoc, "볼트업")!!
        val q = PriceQuery("볼트업", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(PowerTier.T50_TO_100, true), st)
        assertEquals(PriceSource.MY_SETTING, PriceResolver(settings, StationPriceBook(), tariff).resolve(q).source)
        val book = StationPriceBook().learn(st, PowerTier.T50_TO_100, ChargeType.DC, 333.0, LocalDate.of(2026, 10, 22))
        val r = PriceResolver(settings, book, tariff).resolve(q)
        assertEquals(PriceSource.STATION_CONFIRMED, r.source)
        assertEquals(333.0, r.unitPrice!!, 0.0)
    }

    @Test fun apartment_never_guesses_and_operator_home_charger_uses_tariff() {
        val apt = PriceResolver(PriceSettings(), StationPriceBook(), tariff).resolve(
            PriceQuery("아파트", PlaceKind.HOME, ChargeType.AC, TierEstimate(PowerTier.UNDER_30, true), null),
        )
        assertEquals("관리비 청구 아파트는 추측하지 않는다(관리비 정산으로 확정)", PriceSource.NONE, apt.source)
        assertFalse(apt.askStationPrice)

        // 아파트에 사업자(에버온) 충전기가 있는 경우
        val everon = PriceResolver(PriceSettings(), StationPriceBook(), tariff).resolve(
            PriceQuery("에버온", PlaceKind.HOME, ChargeType.AC, TierEstimate(PowerTier.UNDER_30, true), null),
        )
        assertEquals(PriceSource.OFFICIAL_TARIFF, everon.source)
        assertEquals(296.0, everon.unitPrice!!, 0.0)
    }

    @Test fun reprice_after_learning_updates_only_estimates_at_that_station() {
        var pricing = PricingContext(PriceSettings(), StationPriceBook(), tariff)
        val navi = NaviChargingStation("채비 안성휴게소", maxChargingPowerKw = 200)
        val d = RecordFactory.build(publicSession("2026-10-22T12:00:00", stationLoc, peak = 180.0), pricing, car, emptyList(), navi)
        assertEquals(430.0, d.record.unitPrice, 0.0) // 채비 초급속 회원가
        pricing = pricing.copy(book = RecordFactory.learnStationPrice(pricing.book, d.record, d.station!!, 400.0, LocalDate.of(2026, 10, 22)))
        val repriced = RecordFactory.reprice(d.record, pricing, RoundingRule.HALF_UP_1, station = d.station)
        assertEquals(PriceSource.STATION_CONFIRMED, repriced.car!!.priceSource)
        assertEquals(400.0, repriced.unitPrice, 0.0)
        val receipt = d.record.copy(car = d.record.car!!.copy(amountStatus = AmountStatus.RECEIPT), paymentAmount = 17_001.0)
        assertEquals(17_001.0, RecordFactory.reprice(receipt, pricing, RoundingRule.HALF_UP_1, station = d.station).paymentAmount, 0.0)
    }

    @Test fun station_book_survives_backup_round_trip() {
        val st = StationRef.of("E-pit 고양", stationLoc, "E-pit")!!
        val book = StationPriceBook().learn(st, PowerTier.OVER_200, ChargeType.ULTRA, 500.0, LocalDate.of(2026, 10, 22))
        val text = JsonCodec.encodeBackup(emptyList(), PriceSettings(), car, t("2026-10-22T08:00:00"), book, listOf(st))
        val back = JsonCodec.decodeBackup(text)!!
        assertEquals(book, back.stationPrices)
        assertEquals(listOf(st), back.stations)
        assertTrue(back.car!!.useMemberTariff)
    }
}
