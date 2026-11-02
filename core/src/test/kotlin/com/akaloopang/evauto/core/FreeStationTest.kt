package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceQuery
import com.akaloopang.evauto.core.pricing.PriceResolver
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.RepricePolicy
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TierEstimate
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargePlanEstimator
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.voice.Announcements
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.core.voice.VoicePriceParser
import com.akaloopang.evauto.core.pricing.RoundingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 무료 충전소(선택 기능): 한 번 '무료'로 표시하면 다음부터 0원으로 자동 기록 */
class FreeStationTest {

    private val office = StationRef.of(null, GeoPoint(37.5010, 127.0396), null)!!.copy(name = "회사")
    private val day = LocalDate.of(2026, 10, 20)
    private val car = CarSettings(batteryCapacityKwh = 77.4, home = HomeZone(GeoPoint(37.5894, 127.0167)))

    /** 회사 지하주차장 완속 7시간 (집 반경 밖) */
    private fun officeSession(daysAgo: Long = 1) = ChargeSession(
        source = RecordSource.CAR_INFERRED,
        start = LocalDateTime.of(2026, 10, 21, 9, 0).minusDays(daysAgo),
        end = LocalDateTime.of(2026, 10, 21, 16, 0).minusDays(daysAgo),
        socStart = 40.0, socEnd = 80.0, odometerStart = 1000.0, odometerEnd = 1000.0,
        location = GeoPoint(37.5010, 127.0396), batteryCapacityKwh = 77.4,
    )

    @Test fun free_station_records_zero_won_and_is_not_asked_again() {
        val book = StationPriceBook().markFree(office, day)
        assertTrue(book.isFree(office))
        val d = RecordFactory.build(officeSession(), PricingContext(PriceSettings(), book), car, emptyList())
        val r = d.record
        assertEquals(PlaceKind.PUBLIC, r.car!!.place)
        assertEquals(PriceSource.STATION_CONFIRMED, r.car!!.priceSource)
        assertEquals(0.0, r.paymentAmount, 0.0)
        assertTrue("무료로 기록", r.isFree)
        assertFalse("다시 묻지 않는다", r.car!!.askStationPrice)
        assertFalse("단가를 몰라 비운 기록이 아니다 — 나중에 단가 설정이 바뀌어도 다시 계산하지 않는다", RepricePolicy.unpriced(r))
        assertEquals("무료 충전소", RecordFactory.priceLabel(r))
        assertTrue(Announcements.recorded(r, whileAway = true).contains("무료 충전소라 0원"))
    }

    @Test fun free_marker_wins_for_any_charger_and_is_not_suggested_to_same_operator() {
        val mart = StationRef.of("SK일렉링크 ○○마트점", GeoPoint(37.55, 127.0), "SK 일렉링크")!!
        val other = StationRef.of("SK일렉링크 시청점", GeoPoint(37.5663, 126.9779), "SK 일렉링크")!!
        val book = StationPriceBook().learn(mart, PowerTier.T50_TO_100, ChargeType.DC, 380.0, day).markFree(mart, day)
        val r = PriceResolver(PriceSettings(), book, null)
        val atMart = r.resolve(PriceQuery("SK 일렉링크", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(PowerTier.T100_TO_200, true), mart))
        assertEquals(0.0, atMart.unitPrice!!, 0.0)
        val atOther = r.resolve(PriceQuery("SK 일렉링크", PlaceKind.PUBLIC, ChargeType.DC, TierEstimate(PowerTier.T50_TO_100, true), other))
        assertTrue("다른 충전소에 0원을 제안하지 않는다", atOther.source != PriceSource.OTHER_STATION)
    }

    @Test fun learning_a_price_turns_free_station_paid_and_free_marker_survives_json() {
        val book = StationPriceBook().markFree(office, day)
        val json = JsonCodec.encodeBackup(emptyList(), PriceSettings(), CarSettings(), LocalDateTime.of(2026, 10, 21, 0, 0), book, listOf(office))
        val back = JsonCodec.decodeBackup(json)
        assertNotNull(back)
        assertTrue("무료 표시가 백업·복원돼도 남는다", back!!.stationPrices.isFree(office))
        val paid = book.learn(office, PowerTier.UNDER_30, ChargeType.AC, 200.0, day)
        assertFalse("유료로 바뀌면 무료 표시를 지운다", paid.isFree(office))
        assertEquals(200.0, paid.find(office, PowerTier.UNDER_30, ChargeType.AC)!!.unitPrice, 0.0)
    }

    @Test fun expected_cost_at_free_station_is_zero() {
        val book = StationPriceBook().markFree(StationRef.of("회사 충전소", null, null)!!, day)
        val plan = NaviChargingPlan(NaviChargingStation("회사 충전소", 7, 2, 80), arrivalSoc = 30, endSoc = 80)
        val e = ChargePlanEstimator.estimate(plan, null, null, car, PricingContext(PriceSettings(), book), emptyList())!!
        assertEquals(0.0, e.amount!!, 0.0)
        assertEquals("무료 충전소로 가요. 회사 충전소에서는 80퍼센트까지 채워도 0원이에요.", Announcements.chargePlan(e))
    }

    @Test fun waiting_record_turns_zero_won_when_marked_free() {
        // 처음 간 회사 충전기: 단가를 묻는 기록 → 무료 충전소로 표시 → 0원 · 다시 묻지 않음
        val d = RecordFactory.build(officeSession(), PricingContext(PriceSettings(), StationPriceBook()), car, emptyList(), NaviChargingStation("회사 지하주차장 충전기", 7))
        assertTrue("처음엔 단가를 묻는다", d.record.car!!.askStationPrice)
        val st = d.station!!
        val book = StationPriceBook().markFree(st, day)
        val r = RecordFactory.reprice(d.record, PricingContext(PriceSettings(), book), RoundingRule.HALF_UP_1, station = st)
        assertTrue(r.isFree)
        assertEquals(0.0, r.paymentAmount, 0.0)
        assertFalse(r.car!!.askStationPrice)
        assertFalse("무료는 요금표가 바뀌어도 '단가 다시 확인' 경고를 띄우지 않는다", r.car!!.priceMaybeOutdated)
    }

    @Test fun saying_free_marks_the_station_free() {
        assertEquals(VoiceCommand.Free, VoicePriceParser.parse("무료야", expectPrice = true))
        assertEquals(VoiceCommand.Free, VoicePriceParser.parse("여기 공짜예요", expectPrice = true))
        assertEquals(VoiceCommand.Free, VoicePriceParser.parse("돈 안 내는 곳이야", expectPrice = true))
        assertEquals("무료가 아니라는 말은 아니오", VoiceCommand.Confirm(false), VoicePriceParser.parse("무료 아니야", expectPrice = true))
        val paid = VoicePriceParser.parse("무료 아니고 삼백팔십오 원", expectPrice = true)
        assertTrue("금액을 말했으면 단가", paid is VoiceCommand.SetPrice && paid.unitPrice == 385.0)
    }

    @Test fun free_kwh_lowers_my_average_price_for_trip_cost() {
        val paid = RecordFactory.build(officeSession(3), PricingContext(PriceSettings(), StationPriceBook().learn(office, PowerTier.UNDER_30, ChargeType.AC, 300.0, day)), car, emptyList()).record
        val free = RecordFactory.build(officeSession(1), PricingContext(PriceSettings(), StationPriceBook().markFree(office, day)), car, emptyList()).record
        val onlyPaid = com.akaloopang.evauto.core.stats.DriveCost.averageUnitPrice(listOf(paid), day)!!
        val both = com.akaloopang.evauto.core.stats.DriveCost.averageUnitPrice(listOf(paid, free), day)!!
        assertEquals(300.0, onlyPaid, 1.0)
        assertTrue("무료로 넣은 만큼 평균 단가가 내려간다: $both", both < 200.0)
    }

    @Test fun free_record_counts_as_settled_not_estimated() {
        val free = RecordFactory.build(officeSession(1), PricingContext(PriceSettings(), StationPriceBook().markFree(office, day)), car, emptyList()).record
        val m = com.akaloopang.evauto.core.stats.MonthSummary.of(listOf(free), java.time.YearMonth.from(free.date))
        assertEquals(0, m.estimatedCount)
        assertEquals(1.0, m.settledRatio, 0.0)
    }
}
