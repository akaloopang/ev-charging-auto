package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.publicdata.EvChargerApi
import com.akaloopang.evauto.core.publicdata.FuelProduct
import com.akaloopang.evauto.core.publicdata.Opinet
import com.akaloopang.evauto.core.publicdata.PublicCache
import com.akaloopang.evauto.core.publicdata.PublicCharger
import com.akaloopang.evauto.core.publicdata.RegionCode
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargePlanEstimator
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.stats.DriveCost
import com.akaloopang.evauto.core.stats.StationPick
import com.akaloopang.evauto.core.stats.StationPicker
import com.akaloopang.evauto.core.voice.Announcements
import com.akaloopang.evauto.core.voice.GleoActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Pleos API·공공데이터를 활용한 기능: 예상 충전비 · 주행 비용 · 음성 안내 · 공공 충전소 · 유가 · 길안내 충전소 고르기 */
class ApiFeaturesTest {

    private val day = LocalDate.of(2026, 10, 20)
    private val cityHall = StationRef.of("SK일렉링크 시청점", GeoPoint(37.5663, 126.9779), "SK 일렉링크")!!
    private val car = CarSettings(batteryCapacityKwh = 77.4, targetSoc = 80)

    private fun plan(name: String = "SK일렉링크 시청점", kw: Int = 100, arrival: Int? = 30, end: Int? = 80) =
        NaviChargingPlan(NaviChargingStation(name, kw, chargerCount = 4, targetSoc = null), arrivalSoc = arrival, endSoc = end)

    // ── 내비 충전 계획 → 예상 충전비 ──
    @Test fun expected_cost_uses_the_price_confirmed_at_that_station() {
        val book = StationPriceBook().learn(cityHall, PowerTier.T100_TO_200, ChargeType.DC, 400.0, day)
        val e = ChargePlanEstimator.estimate(plan(), currentSoc = 55.0, capacityKwh = null, car, PricingContext(PriceSettings(), book), emptyList())!!
        assertEquals(PriceSource.STATION_CONFIRMED, e.priceSource)
        assertEquals("도착 SOC는 내비 값을 쓴다", 30.0, e.fromSoc, 0.0)
        assertTrue(e.arrivalFromNavi)
        assertEquals(80.0, e.toSoc, 0.0)
        // 50%p × 77.4kWh × 급속 손실 1.05 ≈ 40.6kWh
        assertEquals(40.6, e.kWh!!, 0.1)
        assertEquals(e.kWh!! * 400, e.amount!!, 1.0)
        assertTrue(Announcements.chargePlan(e).contains("이 충전소 단가로"))
        // 충전량과 다 채우면 갈 수 있는 거리까지 (지금 30% · 130km → 80%면 약 347km)
        val said = Announcements.chargePlan(e, com.akaloopang.evauto.core.voice.BatteryNow(socPercent = 30, rangeKm = 130.0))
        assertTrue(said, said.contains("약 41킬로와트시 들어가고 약 "))
        assertTrue(said, said.endsWith("다 채우면 약 347킬로미터 갈 수 있어요."))
    }

    @Test fun without_navi_arrival_soc_the_current_soc_is_used() {
        val e = ChargePlanEstimator.estimate(plan(arrival = 0), 40.0, 179.6, car, PricingContext(PriceSettings()), emptyList())!!
        assertEquals(40.0, e.fromSoc, 0.0)
        assertFalse(e.arrivalFromNavi)
        assertEquals("사용자가 설정한 용량을 차량 값보다 먼저 쓴다", 0.4 * 77.4 * 1.05, e.kWh!!, 0.01)
        val noSetting = ChargePlanEstimator.estimate(plan(arrival = 0), 40.0, 64.0, CarSettings(targetSoc = 80), PricingContext(PriceSettings()), emptyList())!!
        assertEquals("설정이 없으면 차량 값", 0.4 * 64.0 * 1.05, noSetting.kWh!!, 0.01)
    }

    @Test fun unknown_price_leaves_amount_empty_and_asks_after_charging() {
        val e = ChargePlanEstimator.estimate(plan(name = "처음 가는 충전소"), 40.0, null, car, PricingContext(PriceSettings()), emptyList())!!
        assertNull(e.amount)
        assertNotNull(e.kWh)
        assertTrue(Announcements.chargePlan(e).contains("한 번만 알려 주세요"))
    }

    @Test fun nothing_to_charge_or_unknown_battery_is_handled() {
        assertNull("이미 목표 SOC 이상", ChargePlanEstimator.estimate(plan(arrival = 85), null, null, car, PricingContext(PriceSettings()), emptyList()))
        val noCap = ChargePlanEstimator.estimate(plan(), null, null, CarSettings(), PricingContext(PriceSettings()), emptyList())!!
        assertNull("용량을 모르면 kWh도 금액도 비운다", noCap.kWh)
        assertNull(noCap.amount)
        assertNull("SOC를 하나도 모르면 안내하지 않는다", ChargePlanEstimator.estimate(plan(arrival = null), null, 77.4, car, PricingContext(PriceSettings()), emptyList()))
        // 이유 문장 — 에뮬레이터 내비처럼 충전 계획 없이(배터리 넉넉) 충전소로 가는 경우
        assertEquals("지금 배터리가 85%라 목표 80%까지 채울 필요가 없어요.", ChargePlanEstimator.skipReason(plan(arrival = null, end = null), 85.0, car))
        assertEquals("도착 때 배터리 97% 예상이라 목표 80%까지 채울 필요가 없어요.", ChargePlanEstimator.skipReason(plan(arrival = 97, end = null), 99.0, car))
        assertEquals("배터리 정보를 기다리고 있어요.", ChargePlanEstimator.skipReason(plan(arrival = null), null, car))
    }

    @Test fun battery_capacity_setting_wins_over_vehicle_value() {
        // 에뮬레이터는 용량을 179.6kWh로 알려 준다 — 사용자가 77.4를 넣었으면 그 값으로 계산해야 한다
        val s = com.akaloopang.evauto.core.session.ChargeSession(
            source = RecordSource.CAR_INFERRED, start = LocalDateTime.of(2026, 10, 20, 22, 0), end = LocalDateTime.of(2026, 10, 21, 6, 0),
            socStart = 40.0, socEnd = 90.0, odometerStart = 100.0, odometerEnd = 100.0, batteryCapacityKwh = 179.6,
        )
        val withSetting = com.akaloopang.evauto.core.session.EnergyEstimator.estimate(s, 1.10, 77.4)!!
        assertEquals(0.5 * 77.4 * 1.10, withSetting.billedKwh, 0.01)
        val noSetting = com.akaloopang.evauto.core.session.EnergyEstimator.estimate(s, 1.10, null)!!
        assertEquals("설정이 없으면 차량 값", 0.5 * 179.6 * 1.10, noSetting.billedKwh, 0.01)
    }

    // ── 주행 비용 ──
    @Test fun trip_cost_uses_my_average_paid_unit_price() {
        val rs = listOf(
            rec("a", "2026-10-01T09:00", 20.0, 6000.0),   // 300원
            rec("b", "2026-10-10T09:00", 40.0, 16000.0),  // 400원
            rec("c", "2026-01-10T09:00", 10.0, 9000.0),   // 900원 — 90일 밖
        )
        assertEquals("kWh 가중 평균", 22000.0 / 60.0, DriveCost.averageUnitPrice(rs, day)!!, 0.01)
        val t = DriveCost.trip(42.0, 6.0, 350.0, gasEfficiency = 12.0, gasPrice = 1700.0)!!
        assertEquals(7.0, t.kWh, 1e-9)
        assertEquals(2450, t.cost)
        assertEquals(5950, t.gasCost)
        assertEquals(3500, t.savings)
        assertTrue(Announcements.trip(t).contains("2,450원"))
        assertNull("1km 미만은 안내하지 않는다", DriveCost.trip(0.5, 6.0, 350.0))
    }

    @Test fun vehicle_efficiency_is_read_in_either_unit() {
        assertEquals(5.2, DriveCost.fromVehicle(5.2)!!, 1e-9)
        assertEquals("kWh/100km → km/kWh", 6.25, DriveCost.fromVehicle(16.0)!!, 1e-9)
        assertNull(DriveCost.fromVehicle(0.0))
        assertNull(DriveCost.fromVehicle(120.0))
    }

    // ── 음성 안내 문장 ──
    @Test fun monthly_briefing_compares_with_previous_month() {
        val rs = listOf(
            rec("s1", "2026-09-05T09:00", 30.0, 10000.0, odo = 10000.0),
            rec("s2", "2026-09-25T09:00", 30.0, 10000.0, odo = 10200.0),
            rec("o1", "2026-10-05T09:00", 30.0, 9000.0, odo = 10400.0),
            rec("o2", "2026-10-25T09:00", 30.0, 6000.0, odo = 10600.0),
        )
        val text = Announcements.monthlyBriefing(rs, YearMonth.of(2026, 10), 12.0, 1700.0)!!
        assertTrue(text, text.startsWith("10월 충전비는 2번에 15,000원이에요."))
        assertTrue(text, text.contains("9월보다 5,000원 적어요."))
        assertTrue(text, text.contains("내연기관차보다"))
        assertNull("기록 없는 달은 브리핑하지 않는다", Announcements.monthlyBriefing(rs, YearMonth.of(2026, 8)))
    }

    @Test fun recorded_announcement_says_amount_or_missing_price() {
        val home = rec("h", "2026-10-20T01:00", 32.0, 9000.0).copy(
            car = CarMeta(RecordSource.CAR_INFERRED, AmountStatus.ESTIMATED, place = PlaceKind.HOME),
        )
        assertEquals("꺼져 있던 동안 집에서 충전한 것을 찾았어요. 32킬로와트시, 약 9,000원으로 기록했어요.", Announcements.recorded(home, whileAway = true))
        val morning = home.copy(car = home.car!!.copy(socEnd = 85))
        val now = com.akaloopang.evauto.core.voice.BatteryNow(socPercent = 85, rangeKm = 364.6)
        assertEquals(
            "꺼져 있던 동안 집에서 충전한 것을 찾았어요. 32킬로와트시, 약 9,000원으로 기록했고, 지금 배터리 85퍼센트로 약 365킬로미터 갈 수 있어요.",
            Announcements.recorded(morning, whileAway = true, battery = now),
        )
        assertTrue("주행가능거리를 모르면 배터리만", Announcements.recorded(morning, whileAway = true).endsWith("기록했고, 지금 배터리는 85퍼센트예요."))
        assertEquals(
            "충전소에서 막 끝난 충전도 충전량·금액과 갈 수 있는 거리",
            "충전이 끝났어요. 32킬로와트시, 약 9,000원으로 기록했고, 지금 배터리 85퍼센트로 약 365킬로미터 갈 수 있어요.",
            Announcements.recorded(morning, whileAway = false, battery = now),
        )
        assertEquals(
            "이번 충전은 32킬로와트시, 약 9,000원이에요. 지금 배터리 85퍼센트로 약 365킬로미터 갈 수 있어요.",
            Announcements.afterPrice(morning, now),
        )
        val unpriced = rec("u", "2026-10-20T01:00", 8.5, 0.0, provider = "SK 일렉링크")
        assertEquals("충전이 끝났어요. 8.5킬로와트시 기록했어요. 단가를 몰라 금액은 비워 뒀어요.", Announcements.recorded(unpriced, whileAway = false))
    }

    // ── 공공데이터 충전소 정보 ──
    private val sample = """
        {"resultMsg":"NORMAL SERVICE.","totalCount":3,"pageNo":1,"resultCode":"00","numOfRows":9999,
         "items":{"item":[
          {"statNm":"서울시청 지하주차장","statId":"ME000001","chgerId":"01","chgerType":"04","addr":"서울특별시 중구 세종대로 110",
           "lat":"37.5664","lng":"126.9780","busiNm":"SK일렉링크","output":"100","stat":"2","statUpdDt":"20261020101500","limitYn":"N"},
          {"statNm":"서울시청 지하주차장","statId":"ME000001","chgerId":"02","chgerType":"02","addr":"서울특별시 중구 세종대로 110",
           "lat":"37.5664","lng":"126.9780","busiNm":"SK일렉링크","output":"7","stat":"3","statUpdDt":"20261020100000"},
          {"statNm":"먼 충전소","statId":"ME000099","chgerId":"01","chgerType":"04","addr":"부산광역시","lat":"35.1","lng":"129.0","output":"50","stat":"2"}
         ]}}
    """.trimIndent()

    @Test fun public_charger_info_is_parsed_grouped_and_matched_on_device() {
        val page = EvChargerApi.parse(sample)
        assertTrue(page.ok)
        assertEquals(3, page.items.size)
        assertFalse(page.hasMore)
        val near = EvChargerApi.near(page.items, listOf(GeoPoint(37.5663, 126.9779)))
        assertEquals("내 충전소 주변만 남긴다", 2, near.size)
        val st = EvChargerApi.nearest(EvChargerApi.group(near), GeoPoint(37.5663, 126.9779))!!
        assertEquals("서울시청 지하주차장", st.name)
        assertEquals("SK일렉링크", st.operator)
        assertEquals(100, st.maxKw)
        assertEquals(1, st.availableCount)
        assertEquals(1, st.chargingCount)
        assertEquals("급속만 보면 1대 중 1대 비어 있음", 1, st.available(fast = true))
        assertNull("150m 밖이면 짝짓지 않는다", EvChargerApi.nearest(EvChargerApi.group(near), GeoPoint(37.60, 126.98)))
    }

    @Test fun public_api_variants_status_updates_and_cache_round_trip() {
        val wrapped = """{"response":{"header":{"resultCode":"00","resultMsg":"OK"},"body":{"totalCount":1,"pageNo":1,"numOfRows":10,
            "items":{"item":{"statId":"ME000001","chgerId":"01","statNm":"서울시청 지하주차장","stat":"3","statUpdDt":"20261020103000"}}}}}"""
        val one = EvChargerApi.parse(wrapped)
        assertEquals("item이 객체 하나로 와도 읽는다", 1, one.items.size)
        val err = EvChargerApi.parse("<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>")
        assertFalse(err.ok)
        assertEquals("SERVICE_KEY_IS_NOT_REGISTERED_ERROR", err.resultMsg)

        val base = EvChargerApi.parse(sample).items
        val merged = EvChargerApi.applyStatus(base, one.items)
        assertEquals("더 최근 상태로 바뀐다", 3, merged.first { it.chgerId == "01" && it.statId == "ME000001" }.stat)
        val stale = EvChargerApi.applyStatus(merged, listOf(one.items[0].copy(stat = 2, statUpdatedAt = LocalDateTime.of(2026, 10, 20, 9, 0))))
        assertEquals("오래된 상태로 덮지 않는다", 3, stale.first { it.chgerId == "01" && it.statId == "ME000001" }.stat)

        val cache = PublicCache("11", LocalDateTime.of(2026, 10, 20, 10, 0), LocalDateTime.of(2026, 10, 20, 10, 30), merged)
        val back = PublicCache.decode(cache.encode())!!
        assertEquals(cache.zcode, back.zcode)
        assertEquals(cache.chargers, back.chargers)

        assertTrue("디코딩 키는 인코딩해서 넣는다", EvChargerApi.infoUrl("ab+c/d==", "11", 1).contains("serviceKey=ab%2Bc%2Fd%3D%3D"))
        assertTrue("인코딩 키는 그대로", EvChargerApi.infoUrl("ab%2Bc", "11", 1).contains("serviceKey=ab%2Bc&"))
    }

    @Test fun region_code_comes_from_the_first_word_of_the_address() {
        assertEquals(listOf("11"), RegionCode.fromAddress("서울특별시 중구 세종대로 110"))
        assertEquals("경기도 광주시는 광주광역시가 아니다", listOf("41"), RegionCode.fromAddress("대한민국 경기도 광주시 오포읍"))
        assertEquals(listOf("29"), RegionCode.fromAddress("광주광역시 북구 용봉로 77"))
        assertEquals(listOf("51", "42"), RegionCode.fromAddress("강원특별자치도 춘천시"))
        assertEquals(listOf("52", "45"), RegionCode.fromAddress("전북특별자치도 전주시"))
        assertEquals(emptyList<String>(), RegionCode.fromAddress(null))
    }

    // ── 오피넷 평균 유가 ──
    @Test fun opinet_average_prices_are_parsed() {
        val text = """{"RESULT":{"OIL":[
            {"TRADE_DT":"20261022","PRODCD":"B034","PRODNM":"고급휘발유","PRICE":"1950.10","DIFF":"+1.20"},
            {"TRADE_DT":"20261022","PRODCD":"B027","PRODNM":"휘발유","PRICE":"1690.12","DIFF":"-0.53"},
            {"TRADE_DT":"20261022","PRODCD":"D047","PRODNM":"자동차용경유","PRICE":"1550.00","DIFF":"0"},
            {"TRADE_DT":"20261022","PRODCD":"C004","PRODNM":"실내등유","PRICE":"1300"}]}}"""
        val ps = Opinet.parseAverage(text)
        assertEquals(listOf(FuelProduct.GASOLINE, FuelProduct.DIESEL, FuelProduct.PREMIUM), ps.map { it.product })
        assertEquals(1690.12, ps[0].price, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 22), ps[0].date)
        assertEquals(-0.53, ps[0].diff!!, 1e-9)
        assertTrue("오류 응답은 빈 목록", Opinet.parseAverage("<html>error</html>").isEmpty())
    }

    // ── 길안내할 충전소 고르기 (Gleo) ──
    @Test fun station_picker_finds_cheapest_frequent_last_and_named() {
        val gangnam = StationRef.of("채비 강남점", GeoPoint(37.49, 127.02), "대영채비")!!
        val noGps = StationRef("name:x", "좌표 없는 충전소")
        fun at(id: String, date: String, st: StationRef, kWh: Double, pay: Double) =
            ChargingRecord(id, LocalDateTime.parse(date), "x", kWh, pay / kWh, pay,
                car = CarMeta(RecordSource.CAR_LIVE, AmountStatus.RECEIPT, stationKey = st.key))
        val rs = listOf(
            at("1", "2026-10-01T10:00", cityHall, 30.0, 12000.0), // 400
            at("2", "2026-10-03T10:00", cityHall, 30.0, 12000.0),
            at("3", "2026-10-05T10:00", gangnam, 30.0, 10500.0),  // 350
            at("4", "2026-10-09T10:00", noGps, 30.0, 6000.0),     // 200 — 좌표가 없어 제외
        )
        val stations = listOf(cityHall, gangnam, noGps)
        assertEquals(gangnam.key, StationPicker.pick(rs, stations, StationPick.CHEAPEST)!!.station.key)
        assertEquals(350.0, StationPicker.pick(rs, stations, StationPick.CHEAPEST)!!.avgUnitPrice!!, 1e-9)
        assertEquals(cityHall.key, StationPicker.pick(rs, stations, StationPick.MOST_VISITED)!!.station.key)
        assertEquals("좌표 있는 것 중 마지막", gangnam.key, StationPicker.pick(rs, stations, StationPick.LAST)!!.station.key)
        assertEquals(cityHall.key, StationPicker.pick(rs, stations, StationPick.NAMED, "시청")!!.station.key)
        assertNull(StationPicker.pick(rs, stations, StationPick.NAMED, "없는곳"))
        assertEquals(StationPick.MOST_VISITED, StationPick.of("frequent"))
        assertEquals(6, GleoActions.ALL.size)
    }
}
