package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.PowerTier.OVER_200
import com.akaloopang.evauto.core.model.PowerTier.T100_TO_200
import com.akaloopang.evauto.core.model.PowerTier.T30_TO_50
import com.akaloopang.evauto.core.model.PowerTier.T50_TO_100
import com.akaloopang.evauto.core.model.PowerTier.UNDER_30
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceResolver
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.RoundingRule
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.pricing.TierEstimate
import com.akaloopang.evauto.core.pricing.TierPlanner
import com.akaloopang.evauto.core.pricing.TierPlanner.Basis
import com.akaloopang.evauto.core.pricing.TierPlanner.Plan
import com.akaloopang.evauto.core.pricing.TierRange
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 같은 사업자라도 출력 구간마다 요금이 다르다 → 단가를 어느 구간 칸에 넣을지.
 * 공시(2026-10-22 스냅샷) 회원가:
 *   SK일렉링크 295 / 295 / 391 / 391 / 391 (비회원 590 전 구간)
 *   채비       275 / 315 / 410 / 430 / 430 (비회원 590 전 구간)
 *   에버온     296 전 구간
 * 단가는 항상 한 구간 칸에만 넣는다(구간·충전기별 프로모션이 있을 수 있다).
 */
class TierPlannerTest {

    private val tariff = TariffTable.loadBundled()!!
    private val sk = tariff.find("SK 일렉링크")
    private val chaevi = tariff.find("대영채비")
    private val everon = tariff.find("에버온")

    private fun plan(price: Double, spoken: SpokenSpeed? = null, car: TierEstimate? = null, op: String? = "SK 일렉링크", member: Boolean = true) =
        TierPlanner.plan(price, spoken, car, op?.let { tariff.find(it) }, member)

    // ── 방금 충전: 차가 구간을 알면 묻지 않는다. 칸은 항상 하나 ──
    @Test fun car_knows_the_tier_so_no_question() {
        val p = plan(385.0, car = TierEstimate(T100_TO_200, exact = true)) as Plan.Fill
        assertEquals(Basis.CAR, p.basis)
        assertEquals("프로모션이 있을 수 있어 같은 요금 이웃 구간에도 넣지 않는다", listOf(T100_TO_200), p.tiers)
    }

    @Test fun slow_charge_is_its_own_tier() {
        assertEquals(Plan.Fill(UNDER_30, Basis.CAR), plan(300.0, car = TierEstimate(UNDER_30, exact = true)))
    }

    @Test fun lower_bound_resolved_when_price_matches_one_tier() {
        // 관측 최대 85kW → 50kW 이상만 확실. 채비 410원 = 50~100kW 공시가(유일) → 그 구간
        assertEquals(Plan.Fill(T50_TO_100, Basis.PRICE_MATCH), plan(410.0, car = TierEstimate(T50_TO_100, exact = false), op = "대영채비"))
        // 430원은 100~200kW와 200kW 이상 둘 다 → 그 둘로 좁혀서 묻는다
        assertEquals(Plan.AskKw(listOf(T100_TO_200, OVER_200)), plan(430.0, car = TierEstimate(T50_TO_100, exact = false), op = "대영채비"))
    }

    @Test fun lower_bound_with_unmatched_price_asks_kw() {
        assertEquals(Plan.AskKw(listOf(T50_TO_100, T100_TO_200, OVER_200)), plan(420.0, car = TierEstimate(T50_TO_100, exact = false), op = "대영채비"))
        assertEquals("SK처럼 공시가가 같아도 구간을 모르면 묻는다", Plan.AskKw(listOf(T50_TO_100, T100_TO_200, OVER_200)),
            plan(400.0, car = TierEstimate(T50_TO_100, exact = false)))
    }

    // ── 사업자 단가 설정: "급속"은 넓은 말 ──
    @Test fun fast_word_narrowed_by_official_price() {
        assertEquals("295원은 급속 중 30~50kW만", Plan.Fill(T30_TO_50, Basis.PRICE_MATCH), plan(295.0, spoken = SpokenSpeed.FAST))
        assertEquals("391원은 50~100·100~200 둘 → 그 둘 중에서 묻는다", Plan.AskKw(listOf(T50_TO_100, T100_TO_200)), plan(391.0, spoken = SpokenSpeed.FAST))
        assertEquals(Plan.AskKw(SpokenSpeed.FAST.tiers), plan(350.0, spoken = SpokenSpeed.FAST))
        assertEquals("에버온처럼 구간 요금이 같아도 칸은 하나씩", Plan.AskKw(SpokenSpeed.FAST.tiers), plan(300.0, spoken = SpokenSpeed.FAST, op = "에버온"))
    }

    @Test fun spoken_kw_wins_over_car() {
        assertEquals(Plan.Fill(T100_TO_200, Basis.SPOKEN), plan(410.0, spoken = SpokenSpeed.ofKw(100.0), car = TierEstimate(OVER_200, exact = true)))
        assertEquals(Plan.Fill(T100_TO_200, Basis.SPOKEN), plan(440.0, spoken = SpokenSpeed.ofKw(100.0), op = "대영채비"))
    }

    @Test fun nonmember_price_is_recognised() {
        // 회원가 기준 설정이어도 비회원가로 결제했을 수 있다 — 채비 비회원 590원은 급속 전 구간 → 묻는다
        assertEquals(Plan.AskKw(SpokenSpeed.FAST.tiers), plan(590.0, spoken = SpokenSpeed.FAST, op = "대영채비"))
        // GS차지비 비회원 470원(30kW 미만~100~200kW) 중 완속은 말로 정해짐
        assertEquals(Plan.Fill(UNDER_30, Basis.SPOKEN), plan(470.0, spoken = SpokenSpeed.SLOW, op = "GS 차지비"))
    }

    @Test fun nothing_known_asks() {
        assertEquals("공시에 없는 사업자 + 속도 모름", Plan.AskKw(PowerTier.entries), plan(400.0, op = "테슬라"))
        assertEquals(Plan.AskKw(SpokenSpeed.FAST.tiers), plan(400.0, spoken = SpokenSpeed.FAST, op = "테슬라"))
        assertEquals("속도를 말 안 해도 공시가로 좁힌다", Plan.AskKw(listOf(T50_TO_100, T100_TO_200, OVER_200)), plan(391.0))
    }

    @Test fun tier_range_labels() {
        assertEquals("50kW 이상", TierRange.label(listOf(OVER_200, T50_TO_100, T100_TO_200)))
        assertEquals("완속(30kW 미만)", TierRange.label(listOf(UNDER_30)))
        assertEquals("30~100kW", TierRange.label(listOf(T30_TO_50, T50_TO_100)))
        assertEquals("50kW 미만", TierRange.label(listOf(UNDER_30, T30_TO_50)))
        assertEquals("30~50kW, 200kW 이상", TierRange.label(listOf(T30_TO_50, OVER_200)))
        assertEquals("100에서 200킬로와트", TierRange.label(listOf(T100_TO_200), spoken = true))
        assertEquals("완속", TierRange.label(listOf(UNDER_30), spoken = true))
    }

    // ── 내 설정 단가: 5구간 칸 ──
    @Test fun provider_tier_slots_and_legacy_fallback() {
        val s = PriceSettings(
            providerPrices = listOf(
                ProviderUnitPrice("SK 일렉링크").withTiers(listOf(T50_TO_100, T100_TO_200, OVER_200), 400.0),
                ProviderUnitPrice("E-pit", dcUnitPrice = 340.0), // 예전 3분류 백업
            ),
        )
        assertEquals(400.0, s.getUnitPrice("SK 일렉링크", ChargeType.DC, T100_TO_200)!!, 0.0)
        assertNull("30~50kW 칸은 비어 있음 → 공시가로 넘어간다", s.getUnitPrice("SK 일렉링크", ChargeType.DC, T30_TO_50))
        assertNull("완속 칸은 비어 있음", s.getUnitPrice("SK 일렉링크", ChargeType.AC))
        assertEquals("급속 칸 값이 하나뿐이면 구간 몰라도 쓴다", 400.0, s.getUnitPrice("SK 일렉링크", ChargeType.DC)!!, 0.0)
        assertEquals("예전 '급속' 값은 급속 구간 모두에", 340.0, s.getUnitPrice("E-pit", ChargeType.DC, T50_TO_100)!!, 0.0)
        assertEquals("예전 초급속 미설정 → 급속", 340.0, s.getUnitPrice("E-pit", ChargeType.ULTRA, OVER_200)!!, 0.0)
        assertEquals(mapOf(T30_TO_50 to 340.0, T50_TO_100 to 340.0, T100_TO_200 to 340.0, OVER_200 to 340.0),
            s.getPriceFor("E-pit")!!.expanded())

        // 30~50kW는 공시가 295, 50kW 이상은 내 단가 400
        val st = StationRef.of("SK일렉링크 판교", GeoPoint(37.4, 127.1), "SK 일렉링크")
        val r1 = PriceResolver(s, StationPriceBook(), tariff).resolve(PriceQuery("SK 일렉링크", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(T30_TO_50, true), st))
        assertEquals(PriceSource.OFFICIAL_TARIFF, r1.source)
        assertEquals(295.0, r1.unitPrice!!, 0.0)
        val r2 = PriceResolver(s, StationPriceBook(), tariff).resolve(PriceQuery("SK 일렉링크", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(T100_TO_200, true), st))
        assertEquals(PriceSource.MY_SETTING, r2.source)
        assertEquals(400.0, r2.unitPrice!!, 0.0)
    }

    @Test fun tier_slots_survive_backup() {
        val s = PriceSettings(providerPrices = listOf(ProviderUnitPrice("SK 일렉링크").withTiers(listOf(T50_TO_100, OVER_200), 391.0)))
        val back = JsonCodec.decodePriceSettings(JsonCodec.encodePriceSettings(s))
        assertEquals(s.providerPrices, back.providerPrices)
    }

    // ── 충전소 확인 단가: 다른 구간 값을 빌려 오지 않는다 ──
    @Test fun station_price_does_not_leak_across_tiers() {
        val st = StationRef.of("채비 안성휴게소", GeoPoint(37.0, 127.2), "대영채비")!!
        val book = StationPriceBook().learn(st, listOf(T100_TO_200, OVER_200), 430.0, LocalDate.of(2026, 10, 22))
        assertNull("50kW 충전기 단가는 따로 (채비 50kW 410원 ≠ 430원)", book.find(st, T50_TO_100, ChargeType.DC, tierExact = true))
        assertEquals("하한값 50kW인데 그 이상 확인 단가가 하나뿐이면 그것",
            430.0, book.find(st, T50_TO_100, ChargeType.DC, tierExact = false)!!.unitPrice, 0.0)
        val two = book.learn(st, T50_TO_100, ChargeType.DC, 410.0, LocalDate.of(2026, 10, 23))
        assertNull("그 이상 구간 값이 갈리면 추측하지 않는다", two.find(st, T50_TO_100, ChargeType.DC, tierExact = false))
        assertEquals(410.0, two.find(st, T50_TO_100, ChargeType.DC)!!.unitPrice, 0.0)
        assertEquals("화면에는 충전소별 같은 단가 묶음으로", listOf(listOf(T50_TO_100), listOf(T100_TO_200, OVER_200)),
            two.grouped().map { it.tiers })
    }

    @Test fun receipt_price_learns_only_a_certain_tier_and_refines_record() {
        val loc = GeoPoint(37.0, 127.2)
        val car = CarSettings(batteryCapacityKwh = 77.4, home = HomeZone(GeoPoint(HOME_LAT, HOME_LON)))
        val pricing = PricingContext(PriceSettings(), StationPriceBook(), tariff)
        fun build(name: String, peak: Double) = RecordFactory.build(
            ChargeSession(
                source = RecordSource.CAR_LIVE, start = t("2026-10-22T12:00:00"), end = t("2026-10-22T12:30:00"),
                socStart = 30.0, socEnd = 60.0, odometerStart = 1000.0, odometerEnd = 1000.0, peakKw = peak,
                batteryCapacityKwh = 77.4, location = loc,
            ),
            pricing, car, emptyList(), NaviChargingStation(name, maxChargingPowerKw = 0),
        )
        // 채비, 관측 85kW(50kW 이상만 확실), 영수증 430원 → 100~200 / 200 이상 중 모름 → 저장하지 않는다
        val d = build("채비 안성휴게소", 85.0)
        assertEquals(T50_TO_100, d.record.car!!.tier)
        val (book1, same) = RecordFactory.learnStationPriceAuto(pricing.book, d.record, d.station!!, 430.0, LocalDate.of(2026, 10, 22), pricing)
        assertTrue("구간이 갈리면 충전소 단가로 저장하지 않는다", book1.prices.isEmpty())
        assertEquals(d.record, same)

        // 이브이시스(310/324/360/380/380), 관측 40kW(30kW 이상만 확실), 영수증 360원 = 50~100kW 유일 → 저장 + 기록 구간 상향
        val e = build("이브이시스 판교", 40.0)
        assertEquals(T30_TO_50, e.record.car!!.tier)
        val (book2, refined) = RecordFactory.learnStationPriceAuto(pricing.book, e.record, e.station!!, 360.0, LocalDate.of(2026, 10, 22), pricing)
        assertEquals(listOf(T50_TO_100), book2.prices.map { it.tier })
        assertEquals(T50_TO_100, refined.car!!.tier)
        val repriced = RecordFactory.reprice(refined, pricing.copy(book = book2), RoundingRule.HALF_UP_1, station = e.station)
        assertEquals(PriceSource.STATION_CONFIRMED, repriced.car!!.priceSource)
        assertEquals(360.0, repriced.unitPrice, 0.0)
    }
}
