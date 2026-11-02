package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeState
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.stats.MonthSummary
import com.akaloopang.evauto.core.stats.OdoSegments
import com.akaloopang.evauto.core.stats.SavingsCalculator
import com.akaloopang.evauto.core.stats.SavingsIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class JsonAndStatsTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 외부에서 만든 백업 형태 — 마이크로초 날짜, 구버전 단가 키, 모르는 충전방식 값 */
    private val externalBackup = """
        {
          "version": 1,
          "exportedAt": "2026-10-03T08:26:11.123456",
          "records": [
            {"id":"a1","date":"2026-09-30T00:00:00.000","provider":"볼트업","kWh":20.58,"unitPrice":318,
             "paymentAmount":6546,"chargeType":"dc","chargeSpeedKw":null,"chargingMinutes":31,"odometer":12001.5,"memo":null},
            {"id":"a2","date":"2026-10-01T21:10:05.123456","provider":"아파트","kWh":30,"unitPrice":250.0,
             "paymentAmount":7500,"chargeType":"futureType","odometer":null,"memo":"메모"},
            {"broken": true}
          ],
          "settings": {"apartmentUnitPrice": 250, "providers": ["채비","아파트"], "providerPrices": [
            {"provider":"볼트업","acUnitPrice":null,"dcUnitPrice":318}], "gasEfficiency": 0}
        }
    """.trimIndent()

    @Test fun reads_external_backup() {
        val b = JsonCodec.decodeBackup(externalBackup, seoul)
        assertNotNull(b)
        b!!
        assertEquals("깨진 기록 1건은 건너뛴다", 2, b.records.size)
        val a1 = b.records[0]
        assertEquals(ChargeType.DC, a1.chargeType)
        assertEquals(6546.0, a1.paymentAmount, 0.0)
        assertEquals(31, a1.chargingMinutes)
        assertNull("외부 기록은 car 메타 없음", a1.car)
        assertEquals("외부에서 가져온 기록은 확정값으로 본다", AmountStatus.RECEIPT, a1.amountStatus)
        assertNull("모르는 충전방식은 null", b.records[1].chargeType)
        assertEquals(10, b.records[1].date.monthValue)
        assertEquals("구버전 단일 아파트 단가 → AC/DC", 250.0, b.price.apartmentAcUnitPrice!!, 0.0)
        assertEquals(250.0, b.price.apartmentDcUnitPrice!!, 0.0)
        assertNull("0 이하 연비는 미설정", b.price.gasEfficiency)
        assertFalse("채비로 커버되면 대영채비 재추가 안 함", b.price.providers.contains("대영채비"))
        assertNull(b.car)
    }

    @Test fun rejects_non_backup_json() {
        assertNull(JsonCodec.decodeBackup("""{"hello":1}"""))
        assertNull(JsonCodec.decodeBackup("not json"))
    }

    @Test fun utc_date_is_converted_to_local() {
        assertEquals(t("2026-10-21T09:00:00"), JsonCodec.parseDate("2026-10-21T00:00:00.000Z", seoul))
    }

    @Test fun round_trip_with_car_meta_and_settings() {
        val r = rec("c1", "2026-10-20T22:14:00", kWh = 42.57, pay = 10_643.0, status = AmountStatus.ESTIMATED, batteryKwh = 38.7)
        val car = CarSettings(batteryCapacityKwh = 77.4, home = HomeZone(GeoPoint(HOME_LAT, HOME_LON)), targetSoc = 85)
        val text = JsonCodec.encodeBackup(listOf(r), PriceSettings(apartmentAcUnitPrice = 250.0), car, t("2026-10-21T08:00:00"))
        assertTrue("백업 키 유지", text.contains("\"paymentAmount\"") && text.contains("\"settings\""))
        val back = JsonCodec.decodeBackup(text, seoul)!!
        assertEquals(r, back.records.single())
        assertEquals(car, back.car)
        assertEquals(250.0, back.price.apartmentAcUnitPrice!!, 0.0)
    }

    @Test fun peak_from_average_round_trip() {
        val base = rec("c2", "2026-10-21T21:18:51", kWh = 8.58, pay = 0.0, status = AmountStatus.ESTIMATED)
        val r = base.copy(car = base.car!!.copy(observedPeakKw = 306.2, peakFromAverage = true))
        val text = JsonCodec.encodeBackup(listOf(r), PriceSettings(), CarSettings(), t("2026-10-21T22:00:00"))
        val back = JsonCodec.decodeBackup(text, seoul)!!.records.single()
        assertEquals(r, back)
        assertTrue(back.car!!.peakFromAverage)
        // 예전 백업(필드 없음)은 false
        val old = JsonCodec.decodeBackup(text.replace("\"peakFromAverage\": true,", "").replace("\"peakFromAverage\":true,", ""), seoul)!!.records.single()
        assertFalse(old.car!!.peakFromAverage)
    }

    @Test fun snapshot_round_trip() {
        val s = snap("2026-10-20T22:14:00", soc = 40.0, odo = 12345.6, state = ChargeState.CHARGING, port = true, rate = 7.1, lat = HOME_LAT, lon = HOME_LON, capacity = 77.4)
        assertEquals(s, JsonCodec.decodeSnapshot(JsonCodec.encodeSnapshot(s)))
    }

    // ── 절감액 ──
    @Test fun savings_with_vehicle_odometer() {
        val records = listOf(
            rec("1", "2026-10-01T22:00:00", kWh = 40.0, pay = 10_000.0, odo = 10_000.0),
            rec("2", "2026-10-05T22:00:00", kWh = 40.0, pay = 10_000.0, odo = 10_250.0),
            rec("3", "2026-10-09T22:00:00", kWh = 40.0, pay = 10_000.0, odo = 10_500.0),
        )
        val res = SavingsCalculator.compute(records, gasEfficiency = 12.0, gasPrice = 1700.0)
        assertTrue(res.available)
        assertEquals(500.0, res.distanceKm, 0.0)
        assertEquals("첫 기록 충전은 그 전 주행 몫", 80.0, res.kWh, 0.0)
        assertEquals(6.25, res.efficiencyKmPerKwh!!, 1e-9)
        assertEquals(500.0 / 12 * 1700 - 20_000, res.savings, 1e-6)
        assertEquals(40.0, res.evCostPerKm!!, 1e-9)
    }

    @Test fun missing_apartment_charge_is_suspected_then_merged_when_plausible() {
        // 2번 구간에 아파트 충전이 빠지면 300km/10kWh = 30km/kWh (불가능) → 누락 의심
        val records = listOf(
            rec("1", "2026-10-01T22:00:00", kWh = 40.0, pay = 10_000.0, odo = 10_000.0),
            rec("2", "2026-10-03T22:00:00", kWh = 10.0, pay = 2_500.0, odo = 10_300.0),
        )
        val w = OdoSegments.windows(records)
        assertTrue(w.single().missingChargeSuspected)
        assertEquals(SavingsIssue.NOT_ENOUGH_ODOMETER, SavingsCalculator.compute(records.take(1), 12.0, 1700.0).issue)
    }

    @Test fun month_summary_counts_settlement_state() {
        val records = listOf(
            rec("1", "2026-10-01T22:00:00", kWh = 40.0, pay = 10_000.0, status = AmountStatus.BILL_SETTLED),
            rec("2", "2026-10-02T12:00:00", kWh = 20.0, pay = 6_800.0, provider = "E-pit", status = AmountStatus.RECEIPT),
            rec("3", "2026-10-03T22:00:00", kWh = 40.0, pay = 10_000.0, status = AmountStatus.ESTIMATED),
            rec("4", "2026-09-30T22:00:00", kWh = 40.0, pay = 10_000.0, status = AmountStatus.ESTIMATED),
        )
        val m = MonthSummary.of(records, YearMonth.of(2026, 10))
        assertEquals(3, m.count)
        assertEquals(2, m.apartmentCount)
        assertEquals(2.0 / 3, m.settledRatio, 1e-9)
        assertEquals(3, m.pendingConfirmCount)
    }
}
