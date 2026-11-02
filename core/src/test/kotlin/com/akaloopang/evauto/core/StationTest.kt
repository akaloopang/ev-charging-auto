package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.StationNameSource
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PlaceClassifier
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 충전소 = GPS 좌표 + 이름. 같은 사업자도 충전소마다 단가가 다르다. */
class StationTest {

    private val nearHome = GeoPoint(37.5900, 127.0170)
    private val travel = GeoPoint(35.1580, 129.1600) // 여행지(부산)

    @Test fun same_operator_different_places_keep_their_own_price() {
        // 집 근처 SK 345원, 여행지 SK 430원 — 둘 다 100~200kW
        val a = StationRef.of(null, nearHome, "SK 일렉링크")!!
        val b = StationRef.of(null, travel, "SK 일렉링크")!!
        val book = StationPriceBook()
            .learn(a, PowerTier.T100_TO_200, ChargeType.DC, 345.0, LocalDate.of(2026, 10, 1))
            .learn(b, PowerTier.T100_TO_200, ChargeType.DC, 430.0, LocalDate.of(2026, 10, 10))
        assertEquals(345.0, book.find(a, PowerTier.T100_TO_200, ChargeType.DC)!!.unitPrice, 0.0)
        assertEquals(430.0, book.find(b, PowerTier.T100_TO_200, ChargeType.DC)!!.unitPrice, 0.0)
        // 다음에 집 근처에 다시 가면(내비 없이, GPS 40m 차이) 345원
        val again = StationRef.of(null, GeoPoint(nearHome.lat + 0.0003, nearHome.lon), null)!!
        assertEquals(345.0, book.find(again, PowerTier.T100_TO_200, ChargeType.DC)!!.unitPrice, 0.0)
    }

    @Test fun same_spot_different_operators_are_different_stations() {
        // 휴게소처럼 한 자리에 여러 사업자 충전기
        val chaevi = StationRef.of(null, travel, "대영채비")!!
        val epit = StationRef.of(null, GeoPoint(travel.lat + 0.0002, travel.lon), "E-pit")!!
        assertFalse(chaevi.sameAs(epit))
        assertTrue("별칭은 같은 사업자", chaevi.sameAs(StationRef.of(null, travel, "채비")!!))
        val book = StationPriceBook().learn(chaevi, PowerTier.OVER_200, ChargeType.ULTRA, 430.0, LocalDate.of(2026, 10, 1))
        assertEquals("E-pit 충전기에 채비 단가를 쓰지 않는다", null, book.find(epit, PowerTier.OVER_200, ChargeType.ULTRA))
    }

    @Test fun two_named_stations_close_together_are_different() {
        val a = StationRef.of("SK일렉링크 판교역점", nearHome, "SK 일렉링크")!!
        val b = StationRef.of("SK일렉링크 판교테크원점", GeoPoint(nearHome.lat + 0.0008, nearHome.lon), "SK 일렉링크")!!
        assertFalse(a.sameAs(b))
        // 이름 없이(내비 없이) 같은 자리에 오면 위치로 알아본다
        assertTrue(a.sameAs(StationRef.of(null, GeoPoint(nearHome.lat + 0.0002, nearHome.lon), "SK 일렉링크")!!))
    }

    @Test fun display_name_and_gps() {
        val navi = StationRef.of("SK일렉링크 판교점", nearHome, "SK 일렉링크")!!
        assertEquals("SK일렉링크 판교점", navi.displayName)
        assertEquals(StationNameSource.NAVI, navi.nameSource)
        assertEquals("37.59000, 127.01700", navi.coordinates)
        val noName = StationRef.of(null, nearHome, "SK 일렉링크")!!
        assertEquals("SK 일렉링크 충전소", noName.displayName)
        assertEquals("SK 일렉링크 · 성북구 보문로 100", noName.copy(address = "성북구 보문로 100").displayName)
        assertEquals("집 앞 마트", noName.copy(name = "집 앞 마트", nameSource = StationNameSource.USER).displayName)
    }

    @Test fun station_json_keeps_gps_name_address() {
        val st = StationRef.of(null, nearHome, "SK 일렉링크")!!.copy(name = "집 앞 마트", nameSource = StationNameSource.USER, address = "성북구 보문로 100")
        assertEquals(st, JsonCodec.decodeStation(JsonCodec.encodeStation(st)))
        // 예전 백업(nameSource 없음): 이름이 있으면 내비 이름
        val legacy = JsonCodec.parseObject("""{"key":"name:sk판교","name":"SK 판교","lat":37.4,"lon":127.1}""")!!
        assertEquals(StationNameSource.NAVI, JsonCodec.decodeStation(legacy)!!.nameSource)
    }

    // ── 집 반경 안의 급속 = 집 근처 공용 충전소 ──
    private fun session(peak: Double, loc: GeoPoint) = ChargeSession(
        source = RecordSource.CAR_LIVE, start = t("2026-10-22T19:00:00"), end = t("2026-10-22T19:40:00"),
        socStart = 30.0, socEnd = 70.0, odometerStart = 1000.0, odometerEnd = 1000.0, peakKw = peak,
        batteryCapacityKwh = 77.4, location = loc,
    )

    @Test fun fast_charge_inside_home_radius_is_public() {
        val home = HomeZone(nearHome)
        val mart = GeoPoint(nearHome.lat + 0.0015, nearHome.lon) // 약 170m
        assertEquals(PlaceKind.PUBLIC, PlaceClassifier.classify(session(95.0, mart), home, ChargeType.DC).place)
        assertEquals("아파트에 급속 충전기가 있으면 집", PlaceKind.HOME, PlaceClassifier.classify(session(95.0, mart), home, ChargeType.DC, homeHasDc = true).place)
        assertEquals("완속은 집", PlaceKind.HOME, PlaceClassifier.classify(session(7.0, mart), home, ChargeType.AC).place)
        assertEquals("내비로 찾아간 충전소는 공용", PlaceKind.PUBLIC, PlaceClassifier.classify(session(7.0, mart), home, ChargeType.AC, "에버온 보문점").place)

        // 기록까지: 집 근처 급속은 공용 충전소로 저장되고 GPS가 남는다
        val car = CarSettings(batteryCapacityKwh = 77.4, home = home)
        val d = RecordFactory.build(session(95.0, mart), PricingContext(PriceSettings(), StationPriceBook(), TariffTableHolder.tariff), car, emptyList(),
            NaviChargingStation("SK일렉링크 성북점", maxChargingPowerKw = 100))
        assertEquals(PlaceKind.PUBLIC, d.record.car!!.place)
        assertEquals(mart, d.station!!.location)
        assertEquals("SK일렉링크 성북점", d.station!!.displayName)
    }
}

private object TariffTableHolder {
    val tariff = com.akaloopang.evauto.core.pricing.TariffTable.loadBundled()
}
