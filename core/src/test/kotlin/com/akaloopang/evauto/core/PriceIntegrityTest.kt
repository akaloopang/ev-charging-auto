package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceResolver
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.RepricePolicy
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
import com.akaloopang.evauto.core.stats.ChargingStats
import com.akaloopang.evauto.core.stats.GasCarClass
import com.akaloopang.evauto.core.stats.OdoSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 단가 신뢰도(지난 기록 보존) · 같은 사업자 다른 충전소 단가 제안 · 통계 */
class PriceIntegrityTest {

    private val tariff = TariffTable.loadBundled()!!
    private val aa = StationRef.of("SK일렉링크 에이에이동점", GeoPoint(37.40, 127.10), "SK 일렉링크")!!
    private val bb = StationRef.of("SK일렉링크 비비동점", GeoPoint(37.55, 126.95), "SK 일렉링크")!!
    private val day = LocalDate.of(2026, 10, 20)

    private fun q(st: StationRef, tier: PowerTier, exact: Boolean = true) =
        PriceQuery("SK 일렉링크", PlaceKind.PUBLIC, tier.chargeType, TierEstimate(tier, exact), st)

    // ── 같은 사업자 다른 충전소 단가 제안 ──
    @Test fun new_station_suggests_price_learned_at_another_station_of_same_operator_and_tier() {
        val book = StationPriceBook().learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
        val r = PriceResolver(PriceSettings(), book, tariff).resolve(q(bb, PowerTier.T100_TO_200))
        assertEquals(PriceSource.OTHER_STATION, r.source)
        assertEquals(420.0, r.unitPrice!!, 0.0)
        assertEquals("에이에이동점 단가를 빌려 왔다고 알려 준다", aa.key, r.refStation!!.key)
        assertEquals(day, r.refDate)
        assertTrue("이 충전소도 같은지 묻는다", r.askStationPrice)
    }

    @Test fun different_power_tier_is_not_suggested() {
        val book = StationPriceBook().learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
        val r = PriceResolver(PriceSettings(), book, tariff).resolve(q(bb, PowerTier.T50_TO_100))
        assertEquals("50kW 충전기에 100kW 단가를 제안하지 않는다 → 공시가", PriceSource.OFFICIAL_TARIFF, r.source)
    }

    @Test fun different_operator_is_not_suggested_and_same_station_still_wins() {
        val chaevi = StationRef.of("채비 시청점", GeoPoint(37.56, 126.97), "채비")!!
        val book = StationPriceBook()
            .learn(chaevi, PowerTier.T100_TO_200, ChargeType.DC, 380.0, day)
            .learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
        val other = PriceResolver(PriceSettings(), book, tariff).resolve(q(bb, PowerTier.T100_TO_200))
        assertEquals(420.0, other.unitPrice!!, 0.0) // 채비 380원이 아니라 SK 420원
        val same = PriceResolver(PriceSettings(), book, tariff).resolve(q(aa, PowerTier.T100_TO_200))
        assertEquals(PriceSource.STATION_CONFIRMED, same.source)
    }

    @Test fun most_recent_other_station_price_and_my_setting_beats_suggestion() {
        val cc = StationRef.of("SK일렉링크 씨씨점", GeoPoint(35.1, 129.0), "SK 일렉링크")!!
        val book = StationPriceBook()
            .learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
            .learn(cc, PowerTier.T100_TO_200, ChargeType.DC, 400.0, day.plusDays(3))
        assertEquals(400.0, PriceResolver(PriceSettings(), book, tariff).resolve(q(bb, PowerTier.T100_TO_200)).unitPrice!!, 0.0)
        val mine = PriceSettings(providerPrices = listOf(ProviderUnitPrice("SK 일렉링크", tierPrices = mapOf(PowerTier.T100_TO_200 to 350.0))))
        assertEquals(PriceSource.MY_SETTING, PriceResolver(mine, book, tariff).resolve(q(bb, PowerTier.T100_TO_200)).source)
    }

    @Test fun lower_bound_tier_suggests_only_when_all_higher_tiers_agree() {
        val book = StationPriceBook()
            .learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
            .learn(aa, PowerTier.OVER_200, ChargeType.ULTRA, 450.0, day)
        val r = PriceResolver(PriceSettings(), book, tariff).resolve(q(bb, PowerTier.T100_TO_200, exact = false))
        assertTrue(r.source != PriceSource.OTHER_STATION)
    }

    @Test fun record_remembers_where_the_suggested_price_came_from() {
        val car = CarSettings(batteryCapacityKwh = 77.4, home = HomeZone(GeoPoint(HOME_LAT, HOME_LON)))
        val book = StationPriceBook().learn(aa, PowerTier.T100_TO_200, ChargeType.DC, 420.0, day)
        val session = ChargeSession(
            source = RecordSource.CAR_LIVE, start = t("2026-10-22T12:00:00"), end = t("2026-10-22T12:30:00"),
            socStart = 30.0, socEnd = 70.0, odometerStart = 2000.0, odometerEnd = 2000.0, peakKw = 120.0,
            batteryCapacityKwh = 77.4, location = bb.location,
        )
        val d = RecordFactory.build(session, PricingContext(PriceSettings(), book, tariff), car, emptyList(),
            NaviChargingStation("SK일렉링크 비비동점", maxChargingPowerKw = 100))
        assertEquals(PriceSource.OTHER_STATION, d.record.car!!.priceSource)
        assertEquals("SK일렉링크 에이에이동점", d.record.car!!.priceRef)
        assertEquals("지난번 SK일렉링크 에이에이동점 단가", RecordFactory.priceLabel(d.record))
        val back = JsonCodec.decodeRecord(JsonCodec.encodeRecord(d.record))
        assertEquals(d.record.car!!.priceRef, back.car!!.priceRef)
        assertEquals(day, back.car!!.priceRefDate)
    }

    // ── 지난 기록 보존 ──
    private fun est(id: String, source: PriceSource?, unit: Double, confirmed: Boolean, status: AmountStatus = AmountStatus.ESTIMATED) =
        rec(id, "2026-10-19T22:00:00", 10.0, unit * 10, status = status).let { r ->
            r.copy(unitPrice = unit, car = CarMeta(RecordSource.CAR_INFERRED, status, confirmed = confirmed, priceSource = source))
        }

    @Test fun reprice_policy_keeps_past_prices() {
        val oldApt = est("a", PriceSource.MY_SETTING, 190.0, confirmed = true)
        val pendingApt = est("b", PriceSource.MY_SETTING, 190.0, confirmed = false)
        val noPrice = est("c", PriceSource.NONE, 0.0, confirmed = false)
        val guess = est("d", PriceSource.OFFICIAL_TARIFF, 391.0, confirmed = false)
        val receipt = est("e", PriceSource.MY_SETTING, 190.0, confirmed = true, status = AmountStatus.RECEIPT)

        // 단가 설정을 바꿀 때 기본: 비어 있던 기록만 채운다
        assertFalse(RepricePolicy.unpriced(oldApt))
        assertFalse(RepricePolicy.unpriced(pendingApt))
        assertTrue(RepricePolicy.unpriced(noPrice))
        // '잘못 입력해서 고친다'를 고르면 설정 단가로 계산한 추정 기록까지 (영수증은 절대 아님)
        assertTrue(RepricePolicy.fromSetting(oldApt))
        assertFalse(RepricePolicy.fromSetting(receipt))
        // 회원/비회원 기준 변경: 공시가로 짐작한 미확인 기록만
        assertTrue(RepricePolicy.pendingGuess(guess))
        assertFalse(RepricePolicy.pendingGuess(pendingApt))
        assertFalse(RepricePolicy.pendingGuess(guess.copy(car = guess.car!!.copy(confirmed = true))))
    }

    @Test fun setup_flags_survive_backup() {
        val car = CarSettings(setupDone = true, homeCharging = false)
        val back = JsonCodec.decodeCarSettings(JsonCodec.encodeCarSettings(car))
        assertTrue(back.setupDone)
        assertFalse(back.homeCharging)
        val old = JsonCodec.decodeCarSettings(JsonCodec.parseObject("{\"homeProvider\":\"아파트\"}")!!)
        assertFalse("예전 데이터는 처음 설정을 한 번 보여 준다", old.setupDone)
        assertTrue(old.homeCharging)
    }

    // ── 통계 ──
    @Test fun month_comparison_cuts_last_month_at_same_day_and_projection_waits_a_week() {
        val rs = listOf(
            rec("1", "2026-09-03T10:00:00", 20.0, 6000.0),
            rec("2", "2026-09-25T10:00:00", 20.0, 6000.0),
            rec("3", "2026-10-05T10:00:00", 30.0, 9000.0),
        )
        val c = ChargingStats.compareMonth(rs, LocalDate.of(2026, 10, 20))
        assertEquals(1, c.lastMonthSame.count)
        assertEquals(2, c.lastMonthFull.count)
        assertEquals(50.0, c.diffPct!!, 0.001)
        assertNull(ChargingStats.projectedMonthPayment(rs, LocalDate.of(2026, 10, 5)))
        assertEquals(9000.0 / 20 * 31, ChargingStats.projectedMonthPayment(rs, LocalDate.of(2026, 10, 20))!!, 0.01)
    }

    @Test fun group_stats_and_unit_price_ignore_unpriced_records() {
        val rs = listOf(
            rec("1", "2026-10-01T10:00:00", 10.0, 4200.0, provider = "SK 일렉링크", type = ChargeType.DC),
            rec("2", "2026-10-02T10:00:00", 10.0, 0.0, provider = "아파트"),
            rec("3", "2026-10-03T10:00:00", 20.0, 3800.0, provider = "아파트"),
        )
        val byP = ChargingStats.byProvider(rs)
        assertEquals("SK 일렉링크", byP.first().label)
        val apt = byP.first { it.label == "아파트" }
        assertEquals(2, apt.stat.count)
        assertEquals("결제 0원 제외 평균 단가", 190.0, apt.priced.unitPrice, 0.001)
        assertEquals(190.0, ChargingStats.cheapest(rs)!!.unitPrice, 0.001)
        assertEquals(listOf(ChargeType.AC, ChargeType.DC), ChargingStats.byChargeType(rs).map { it.first })
        val six = ChargingStats.lastMonths(rs, YearMonth.of(2026, 10))
        assertEquals(6, six.size)
        assertEquals(YearMonth.of(2026, 5), six.first().first)
        assertEquals(3, six.last().second.count)
    }

    @Test fun monthly_efficiency_spreads_a_segment_over_days_and_computes_savings() {
        // 9/30 → 10/2 구간: 300km, 50kWh, 10,000원 — 이틀(10/1, 10/2)로 나뉘어 전부 10월
        val rs = listOf(
            rec("s", "2026-09-30T08:00:00", 0.0, 0.0, odo = 1000.0),
            rec("e", "2026-10-02T08:00:00", 50.0, 10_000.0, odo = 1300.0),
        )
        val eff = ChargingStats.monthlyEfficiency(OdoSegments.windows(rs))
        val oct = eff.getValue(YearMonth.of(2026, 10))
        assertEquals(300.0, oct.distanceKm, 0.001)
        assertEquals(6.0, oct.kmPerKwh, 0.001)
        // 중형(12km/L) 1,700원/L → 300km = 42,500원 − 10,000원
        assertEquals(32_500.0, oct.savings(GasCarClass.MID.kmPerL, 1700.0)!!, 0.01)
        assertEquals(GasCarClass.MID, GasCarClass.matching(12.0))
        assertEquals(GasCarClass.LARGE, GasCarClass.matching(9.0))
        assertEquals(listOf("경차", "준중형", "중형", "SUV", "대형", "하이브리드"), GasCarClass.entries.map { it.label })
        assertNull("직접 입력한 연비는 차급에 안 맞을 수 있다", GasCarClass.matching(16.3))
    }
}
