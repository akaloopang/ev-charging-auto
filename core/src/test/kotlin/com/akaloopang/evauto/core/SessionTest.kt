package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.ChargeSessionTracker
import com.akaloopang.evauto.core.session.ChargeSessionTracker.Event
import com.akaloopang.evauto.core.session.ChargeState
import com.akaloopang.evauto.core.session.EnergyEstimator
import com.akaloopang.evauto.core.session.GapReconciler
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.LiveMeter
import com.akaloopang.evauto.core.session.LossCalibrator
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PlaceClassifier
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private val home = HomeZone(GeoPoint(HOME_LAT, HOME_LON))
    private val price = PriceSettings(
        apartmentAcUnitPrice = 250.0,
        providerPrices = listOf(ProviderUnitPrice("E-pit", acUnitPrice = 280.0, dcUnitPrice = 340.0, ultraUnitPrice = 350.0)),
    )
    private val car = CarSettings(batteryCapacityKwh = 77.4, home = home)

    // ── 충전 출력을 안 알려 주는 차종: SOC 상승 속도로 급속 판정 ──
    @Test fun fast_soc_rise_without_charge_rate_is_not_mistaken_for_home_ac() {
        // 저녁(야간 판정 시간대)에 1분 30초 만에 SOC 30→40% — 완속(최대 11kW)으로는 불가능한 속도
        val tracker = ChargeSessionTracker()
        tracker.onSnapshot(snap("2026-10-21T21:18:51", soc = 30.0, odo = 0.0, rate = 0.0, lat = 37.4220, lon = -122.0840, capacity = 77.4))
        tracker.onSnapshot(snap("2026-10-21T21:20:22", soc = 40.0, odo = 0.0, rate = 0.0, lat = 37.4220, lon = -122.0840, capacity = 77.4))
        val done = tracker.onSnapshot(snap("2026-10-21T21:23:26", soc = 40.0, odo = 0.0, rate = 0.0, capacity = 77.4))
            .filterIsInstance<Event.Completed>().single().session
        assertNull("차가 출력을 0으로만 줌", done.peakKw)

        val avg = RecordFactory.fastAverageKw(done, 77.4)
        assertNotNull(avg)
        assertEquals(7.74 / (91.0 / 3600.0), avg!!, 1e-6) // 7.74kWh ÷ 91초 ≈ 306kW

        val d = RecordFactory.build(done, PricingContext(price), CarSettings(batteryCapacityKwh = 77.4), emptyList())
        assertEquals(PlaceKind.PUBLIC, d.placeDecision.place)
        assertEquals(ChargeType.ULTRA, d.record.chargeType)
        assertTrue(d.record.car!!.peakFromAverage)
        assertEquals(306.2, d.record.car!!.observedPeakKw!!, 0.001)
    }

    @Test fun slow_average_says_nothing_and_overnight_stays_home_ac() {
        // 밤새 10시간 동안 30→100% = 평균 5.4kW → 완속인지 급속인지 말해 주지 않는다 (주차 시간이 섞임)
        val s = GapReconciler.reconcile(
            snap("2026-10-20T21:00:00", soc = 30.0, odo = 100.0, lat = HOME_LAT, lon = HOME_LON),
            snap("2026-10-21T07:00:00", soc = 100.0, odo = 100.0),
        )!!
        assertNull(RecordFactory.fastAverageKw(s, 77.4))
        val r = RecordFactory.build(s, PricingContext(price), car, emptyList()).record
        assertEquals(ChargeType.AC, r.chargeType)
        assertFalse(r.car!!.peakFromAverage)
        assertNull(r.car!!.observedPeakKw)
    }

    // ── 아파트 충전 누락 방지: 시동 OFF/ON 비교 ──
    @Test fun overnight_apartment_charge_is_inferred_while_app_was_off() {
        val lastSeen = snap("2026-10-20T22:14:00", soc = 40.0, odo = 12_345.6, lat = HOME_LAT + 0.0005, lon = HOME_LON)
        val morning = snap("2026-10-21T07:52:00", soc = 90.0, odo = 12_345.6) // 지하주차장: 위치 없음
        val s = GapReconciler.reconcile(lastSeen, morning)
        assertNotNull(s)
        s!!
        assertEquals(RecordSource.CAR_INFERRED, s.source)
        assertEquals(50.0, s.socRise!!, 0.0)
        assertFalse(s.lowConfidence)

        val draft = RecordFactory.build(s, PricingContext(price), car, history = emptyList())
        val r = draft.record
        assertEquals("집 반경 안 + 시동 끄기 직전 위치", PlaceKind.HOME, draft.placeDecision.place)
        assertEquals("아파트", r.provider)
        assertEquals(ChargeType.AC, r.chargeType)
        // 50% × 77.4kWh = 38.7kWh(배터리) × 1.10(완속 기본 손실계수) = 42.57kWh
        assertEquals(42.57, r.kWh, 1e-9)
        assertEquals(10_643.0, r.paymentAmount, 0.0) // 42.57 × 250 = 10,642.5 → 반올림
        assertEquals(AmountStatus.ESTIMATED, r.amountStatus)
        assertEquals("야간 충전의 날짜는 시동 끈 날", 20, r.date.dayOfMonth)
        assertNull("주차 시간 ≠ 충전 시간", r.chargingMinutes)
    }

    @Test fun no_charge_when_soc_barely_changes() {
        val a = snap("2026-10-20T22:00:00", soc = 55.0, odo = 100.0)
        assertNull(GapReconciler.reconcile(a, snap("2026-10-21T08:00:00", soc = 56.0, odo = 100.0)))
        assertNull(GapReconciler.reconcile(a, snap("2026-10-21T08:00:00", soc = 54.0, odo = 100.0)))
    }

    @Test fun charge_with_driving_in_between_is_kept_as_low_confidence() {
        val a = snap("2026-10-20T18:00:00", soc = 30.0, odo = 100.0)
        val s = GapReconciler.reconcile(a, snap("2026-10-21T08:00:00", soc = 70.0, odo = 135.0))
        assertNotNull("충전은 확실히 있었다", s)
        assertTrue("주행으로 쓴 만큼 적게 잡힌 하한값", s!!.lowConfidence)
    }

    @Test fun first_apartment_charge_without_home_asks_once() {
        val s = GapReconciler.reconcile(
            snap("2026-10-20T23:00:00", soc = 40.0, odo = 10.0),
            snap("2026-10-21T07:00:00", soc = 85.0, odo = 10.0),
        )!!
        val d = PlaceClassifier.classify(s, home = null, chargeType = null)
        assertEquals(PlaceKind.HOME, d.place)
        assertTrue("집 위치가 없으면 한 번 확인", d.needsConfirm)
    }

    // ── 앱 실행 중 실시간 감지 ──
    @Test fun live_dc_session_with_state_and_rate() {
        val tr = ChargeSessionTracker()
        val ev = mutableListOf<Event>()
        ev += tr.onSnapshot(snap("2026-10-21T12:00:00", soc = 25.0, odo = 500.0, state = ChargeState.NOT_CHARGING, port = false))
        ev += tr.onSnapshot(snap("2026-10-21T12:01:00", soc = 25.0, odo = 500.0, state = ChargeState.CHARGING, port = true, rate = 180.0, capacity = 77.4))
        ev += tr.onSnapshot(snap("2026-10-21T12:11:00", soc = 50.0, odo = 500.0, state = ChargeState.CHARGING, port = true, rate = 200.0))
        // 10분 공백은 적분하지 않는다(앱 멈춤 가정) → 1분 간격 샘플
        ev += tr.onSnapshot(snap("2026-10-21T12:12:00", soc = 52.0, odo = 500.0, state = ChargeState.CHARGING, port = true, rate = 190.0))
        ev += tr.onSnapshot(snap("2026-10-21T12:13:00", soc = 54.0, odo = 500.0, state = ChargeState.NOT_CHARGING, port = true, rate = 0.0))
        assertTrue(ev.first() is Event.Started)
        val done = ev.filterIsInstance<Event.Completed>().single().session
        assertEquals(25.0, done.socStart!!, 0.0)
        assertEquals(54.0, done.socEnd!!, 0.0)
        assertEquals(200.0, done.peakKw!!, 0.0)
        assertTrue("적분 커버리지가 낮으면 SOC 방식으로 추정", done.integrationCoverage < EnergyEstimator.MIN_COVERAGE)

        val navi = NaviChargingStation(name = "E-pit 고양", maxChargingPowerKw = 350)
        val r = RecordFactory.build(done, PricingContext(price), car, emptyList(), navi).record
        assertEquals("E-pit", r.provider)
        assertEquals(ChargeType.ULTRA, r.chargeType)
        assertEquals(350.0, r.chargeSpeedKw!!, 0.0)
        assertEquals(12, r.chargingMinutes)
    }

    @Test fun session_ends_when_car_drives_away_and_keeps_pre_drive_soc() {
        val tr = ChargeSessionTracker()
        tr.onSnapshot(snap("2026-10-21T12:00:00", soc = 40.0, odo = 500.0, state = ChargeState.CHARGING, port = true, rate = 7.0))
        tr.onSnapshot(snap("2026-10-21T12:30:00", soc = 44.0, odo = 500.0, state = ChargeState.CHARGING, port = true, rate = 7.0))
        val ev = tr.onSnapshot(snap("2026-10-21T12:40:00", soc = 43.0, odo = 503.0))
        val s = ev.filterIsInstance<Event.Completed>().single().session
        assertEquals("주행으로 줄어든 SOC가 아니라 주행 전 값", 44.0, s.socEnd!!, 0.0)
    }

    @Test fun car_without_charge_state_detects_by_soc_rise() {
        val tr = ChargeSessionTracker(endGrace = java.time.Duration.ofMinutes(3))
        tr.onSnapshot(snap("2026-10-21T20:00:00", soc = 40.0, odo = 900.0))
        val ev1 = tr.onSnapshot(snap("2026-10-21T20:10:00", soc = 41.0, odo = 900.0))
        assertTrue(ev1.single() is Event.Started)
        tr.onSnapshot(snap("2026-10-21T20:20:00", soc = 43.0, odo = 900.0))
        val ev2 = tr.onSnapshot(snap("2026-10-21T20:30:00", soc = 43.0, odo = 900.0))
        val s = ev2.filterIsInstance<Event.Completed>().single().session
        assertEquals(40.0, s.socStart!!, 0.0)
        assertEquals(43.0, s.socEnd!!, 0.0)
    }

    @Test fun plug_in_and_out_without_charging_is_discarded() {
        val tr = ChargeSessionTracker()
        tr.onSnapshot(snap("2026-10-21T12:00:00", soc = 60.0, odo = 1.0, state = ChargeState.CHARGING, port = true, rate = 7.0))
        val ev = tr.onSnapshot(snap("2026-10-21T12:00:30", soc = 60.0, odo = 1.0, state = ChargeState.NOT_CHARGING, port = false, rate = 0.0))
        assertTrue(ev.single() is Event.Discarded)
    }

    @Test fun restart_mid_charge_finishes_with_current_soc() {
        val tr = ChargeSessionTracker()
        tr.onSnapshot(snap("2026-10-21T22:00:00", soc = 30.0, odo = 7.0, state = ChargeState.CHARGING, port = true, rate = 7.0))
        val saved = tr.active
        val revived = ChargeSessionTracker().apply { restore(saved, null) }
        val ev = revived.resumeAfterRestart(snap("2026-10-22T07:00:00", soc = 88.0, odo = 7.0, state = ChargeState.FULLY_CHARGED, port = true))
        val s = ev.filterIsInstance<Event.Completed>().single().session
        assertEquals(88.0, s.socEnd!!, 0.0)
        assertFalse(s.lowConfidence)
    }

    // ── 손실계수 학습 · 실시간 미터 ──
    @Test fun loss_factor_learns_from_receipts() {
        assertEquals(1.10, LossCalibrator.factorFor(ChargeType.AC, emptyList()), 0.0)
        val history = listOf(
            rec("1", "2026-10-01T22:00:00", kWh = 41.0, pay = 0.0, status = AmountStatus.BILL_SETTLED, batteryKwh = 36.0),
            rec("2", "2026-10-03T22:00:00", kWh = 33.6, pay = 0.0, status = AmountStatus.RECEIPT, batteryKwh = 30.0),
            rec("3", "2026-10-05T22:00:00", kWh = 22.6, pay = 0.0, status = AmountStatus.RECEIPT, batteryKwh = 20.0),
            rec("e", "2026-10-06T22:00:00", kWh = 99.0, pay = 0.0, status = AmountStatus.ESTIMATED, batteryKwh = 20.0), // 추정은 제외
        )
        // 비율 1.1389, 1.12, 1.13 → 중앙값 1.13
        assertEquals(1.13, LossCalibrator.factorFor(ChargeType.AC, history), 1e-9)
    }

    @Test fun live_meter_shows_amount_so_far_and_to_target() {
        val tr = ChargeSessionTracker()
        tr.onSnapshot(snap("2026-10-21T12:00:00", soc = 40.0, odo = 1.0, state = ChargeState.CHARGING, port = true, rate = 100.0, capacity = 77.4))
        val latest = snap("2026-10-21T12:01:00", soc = 46.0, odo = 1.0, state = ChargeState.CHARGING, port = true, rate = 100.0, capacity = 77.4)
        tr.onSnapshot(latest)
        val reading = LiveMeter.read(tr.active!!, latest, unitPrice = 340.0, lossFactor = 1.05, settingCapacityKwh = null, targetSoc = 80)
        // 적분 1분 × 100kW = 1.6667kWh × 1.05 = 1.75kWh → 595원
        assertEquals(1.75, reading.kWhSoFar, 1e-9)
        assertEquals(595.0, reading.amountSoFar!!, 0.0)
        // 남은 34% × 77.4 = 26.316kWh ÷ 100kW = 15.8분
        assertEquals(16, reading.minutesToTarget)
        assertFalse(reading.reachedTarget)
    }

    @Test fun reprice_touches_only_estimated_records() {
        val est = rec("e", "2026-10-21T22:00:00", kWh = 40.0, pay = 0.0, status = AmountStatus.ESTIMATED)
        val r = RecordFactory.reprice(est, PricingContext(price), com.akaloopang.evauto.core.pricing.RoundingRule.HALF_UP_1)
        assertEquals(10_000.0, r.paymentAmount, 0.0)
        val fixed = rec("f", "2026-10-21T22:00:00", kWh = 40.0, pay = 9_999.0, status = AmountStatus.RECEIPT)
        assertEquals("영수증 금액은 그대로", 9_999.0,
            RecordFactory.reprice(fixed, PricingContext(price), com.akaloopang.evauto.core.pricing.RoundingRule.HALF_UP_1, provider = "E-pit").paymentAmount, 0.0)
    }

    @Test fun missing_unit_price_is_flagged_not_guessed() {
        val s = ChargeSession(
            source = RecordSource.CAR_LIVE, start = t("2026-10-21T12:00:00"), end = t("2026-10-21T12:30:00"),
            socStart = 30.0, socEnd = 60.0, odometerStart = 1.0, odometerEnd = 1.0, peakKw = 95.0,
            batteryCapacityKwh = 60.0, location = GeoPoint(35.0, 129.0),
        )
        val d = RecordFactory.build(s, PricingContext(PriceSettings()), car, emptyList(), NaviChargingStation("볼트업 해운대", 100))
        assertTrue(d.missingUnitPrice)
        assertEquals(0.0, d.record.paymentAmount, 0.0)
        assertEquals("볼트업", d.record.provider)
    }
}
