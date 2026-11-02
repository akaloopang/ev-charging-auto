package com.akaloopang.evauto.core

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.voice.GleoActions
import com.akaloopang.evauto.core.voice.KoreanNumber
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.SpeedAnswer
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.core.voice.VoicePriceParser
import com.akaloopang.evauto.core.voice.VoiceTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {

    private val tariff = TariffTable.loadBundled()

    private fun price(s: String, expect: Boolean = false) = VoicePriceParser.parse(s, expectPrice = expect, tariff = tariff)

    // ── 숫자 읽기 ──
    @Test fun korean_numbers() {
        assertEquals(385.0, KoreanNumber.parse("385")!!, 0.0)
        assertEquals(385.0, KoreanNumber.parse("삼백팔십오")!!, 0.0)
        assertEquals(385.0, KoreanNumber.parse("삼백 팔십 오")!!, 0.0)
        assertEquals(385.0, KoreanNumber.parse("3백85")!!, 0.0)
        assertEquals("한 자리씩 읽기", 385.0, KoreanNumber.parse("삼팔오")!!, 0.0)
        assertEquals(324.4, KoreanNumber.parse("삼백이십사점사")!!, 1e-9)
        assertEquals(324.4, KoreanNumber.parse("324.4")!!, 1e-9)
        assertEquals(1_500.0, KoreanNumber.parse("천오백")!!, 0.0)
        assertEquals(100.0, KoreanNumber.parse("백")!!, 0.0)
        assertEquals(10_000.0, KoreanNumber.parse("만")!!, 0.0)
        assertNull(KoreanNumber.parse("충전소"))
    }

    // ── 단가 명령 ──
    @Test fun station_price_utterances() {
        val expected = VoiceCommand.SetPrice(PriceTarget.CurrentStation, 385.0, null)
        assertEquals(expected, price("이 충전소 단가 385원"))
        assertEquals(expected, price("킬로와트시당 삼백팔십오 원으로 저장해 줘"))
        assertEquals(expected, price("단가 3백85원"))
        assertEquals("'이 충전소'의 '이'를 숫자로 읽지 않는다", expected, price("이 충전소 삼백팔십오원"))
        assertEquals("고쳐 말하면 마지막 값", VoiceCommand.SetPrice(PriceTarget.CurrentStation, 390.0, null), price("380원 아니 390원"))
        assertEquals("'회원'의 '원'에 속지 않는다", VoiceCommand.SetPrice(PriceTarget.CurrentStation, 347.2, null), price("회원가 347.2원"))
    }

    @Test fun answer_to_price_question_can_be_just_a_number() {
        assertEquals(VoiceCommand.SetPrice(PriceTarget.CurrentStation, 391.0, null), price("삼백구십일", expect = true))
        assertEquals(VoiceCommand.SetPrice(PriceTarget.CurrentStation, 391.0, null), price("삼백구십일이에요", expect = true))
        assertEquals(VoiceCommand.SetPrice(PriceTarget.CurrentStation, 385.0, null), price("킬로와트시당 385", expect = true))
    }

    @Test fun targets_and_speeds() {
        assertEquals(VoiceCommand.SetPrice(PriceTarget.Apartment, 250.0, null), price("아파트 단가 이백오십 원"))
        assertEquals(VoiceCommand.SetPrice(PriceTarget.Provider("볼트업"), 350.0, SpokenSpeed.FAST), price("볼트업 급속 350원"))
        assertEquals(
            VoiceCommand.SetPrice(PriceTarget.Provider("SK 일렉링크"), 430.0, SpokenSpeed.ULTRA_FAST),
            price("SK일렉링크 초급속 사백삼십 원으로 바꿔줘"),
        )
        assertEquals("공시 요금표에만 있는 사업자", VoiceCommand.SetPrice(PriceTarget.Provider("티맵"), 420.0, SpokenSpeed.ULTRA_FAST), price("티맵 초급속 420원"))
        assertEquals(ChargeType.AC, (price("아파트 완속 이백오십 원") as VoiceCommand.SetPrice).chargeType)
    }

    // ── 충전기 출력(kW)은 단가와 구분해서 듣는다 ──
    @Test fun charger_kw_is_separated_from_price() {
        val a = price("SK일렉링크 백 킬로 삼백구십일 원") as VoiceCommand.SetPrice
        assertEquals(PriceTarget.Provider("SK 일렉링크"), a.target)
        assertEquals(391.0, a.unitPrice, 0.0)
        assertEquals(listOf(PowerTier.T100_TO_200), a.speed!!.tiers)
        assertTrue(a.speed!!.exact)

        val b = price("200kW 391원") as VoiceCommand.SetPrice
        assertEquals(391.0, b.unitPrice, 0.0)
        assertEquals(listOf(PowerTier.OVER_200), b.speed!!.tiers)

        val c = price("391 이백 킬로와트", expect = true) as VoiceCommand.SetPrice
        assertEquals("'원' 없이 말해도 출력 숫자를 단가로 잡지 않는다", 391.0, c.unitPrice, 0.0)
        assertEquals(listOf(PowerTier.OVER_200), c.speed!!.tiers)

        val d = price("킬로와트시당 삼백팔십오 원") as VoiceCommand.SetPrice
        assertEquals("킬로와트시는 단가 단위 — 출력이 아니다", null, d.speed)
        assertEquals(385.0, d.unitPrice, 0.0)
        assertNull((price("385원 kWh당") as VoiceCommand.SetPrice).speed)
        assertEquals("50kW 충전기는 50~100 구간", listOf(PowerTier.T50_TO_100), (price("오십 킬로 삼백이십오 원") as VoiceCommand.SetPrice).speed!!.tiers)
    }

    @Test fun speed_answer_when_asked() {
        fun kw(s: String) = (VoicePriceParser.parseSpeedAnswer(s) as SpeedAnswer.Speed).speed.tiers
        assertEquals(listOf(PowerTier.T100_TO_200), kw("백"))
        assertEquals(listOf(PowerTier.T100_TO_200), kw("백 킬로요"))
        assertEquals(listOf(PowerTier.OVER_200), kw("이백이요"))
        assertEquals(listOf(PowerTier.OVER_200), kw("350kW"))
        assertEquals(listOf(PowerTier.T50_TO_100), kw("오십짜리"))
        assertEquals(listOf(PowerTier.UNDER_30), kw("완속"))
        assertEquals(SpeedAnswer.DontKnow, VoicePriceParser.parseSpeedAnswer("잘 모르겠어"))
        assertNull("출력으로 볼 수 없는 값", VoicePriceParser.parseSpeedAnswer("오천"))
    }

    @Test fun implausible_values_are_rejected_not_saved() {
        val r = price("3850원")
        assertTrue(r is VoiceCommand.Rejected)
        assertTrue((r as VoiceCommand.Rejected).reason.contains("3850"))
        assertTrue(price("오십원") is VoiceCommand.Rejected)
        assertTrue("단가를 못 알아들음", price("이 충전소 단가", expect = true) is VoiceCommand.Rejected)
    }

    @Test fun yes_no() {
        assertEquals(VoiceCommand.Confirm(true), price("네"))
        assertEquals(VoiceCommand.Confirm(true), price("응 저장해"))
        assertEquals(VoiceCommand.Confirm(false), price("아니요"))
        assertEquals(VoiceCommand.Confirm(false), price("취소"))
        assertNull("관계없는 말은 무시", price("오늘 날씨 좋네"))
    }

    @Test fun confirm_question_reads_back_value() {
        val q = VoicePriceParser.confirmQuestion(VoiceCommand.SetPrice(PriceTarget.CurrentStation, 385.0, null), "SK일렉링크 판교점")
        assertEquals("SK일렉링크 판교점, 킬로와트시당 385원으로 저장할까요?", q)
        val withTiers = VoicePriceParser.confirmQuestion(
            VoiceCommand.SetPrice(PriceTarget.Provider("SK 일렉링크"), 391.0),
            null,
            listOf(PowerTier.T50_TO_100, PowerTier.T100_TO_200, PowerTier.OVER_200),
        )
        assertEquals("SK 일렉링크 50킬로와트 이상 충전기, 킬로와트시당 391원으로 저장할까요?", withTiers)
        assertEquals(
            "SK 일렉링크 요금은 충전기 출력마다 달라요. 충전기에 적힌 출력이 몇 킬로와트인가요? 예를 들어 50, 100.",
            VoicePriceParser.askKwQuestion("SK 일렉링크", SpokenSpeed.FAST.tiers),
        )
    }

    // ── '이 충전소'가 어느 기록인가 ──
    @Test fun current_station_is_recent_public_charge_waiting_for_price() {
        fun pub(id: String, date: String, ask: Boolean) = rec(id, date, kWh = 20.0, pay = 7000.0, provider = "E-pit", status = AmountStatus.ESTIMATED)
            .let { it.copy(car = CarMeta(RecordSource.CAR_LIVE, AmountStatus.ESTIMATED, endDate = it.date.plusMinutes(30), place = PlaceKind.PUBLIC, stationKey = "name:$id", askStationPrice = ask)) }
        val records = listOf(
            pub("old", "2026-10-20T09:00:00", ask = true),       // 너무 오래됨
            pub("done", "2026-10-22T11:00:00", ask = false),
            pub("waiting", "2026-10-22T08:00:00", ask = true),
            rec("home", "2026-10-22T07:00:00", kWh = 30.0, pay = 0.0, status = AmountStatus.ESTIMATED),
        )
        assertEquals("waiting", VoiceTargets.currentStationRecord(records, t("2026-10-22T12:00:00"))?.id)
        assertEquals("단가 대기 중인 게 없으면 가장 최근 공용 충전", "done",
            VoiceTargets.currentStationRecord(records.filter { it.id != "waiting" }, t("2026-10-22T12:00:00"))?.id)
        assertNull(VoiceTargets.currentStationRecord(records, t("2026-10-24T12:00:00")))
    }
}

class GleoTest {
    @Test fun parses_both_documented_param_shapes() {
        val direct = GleoActions.parseParams("""{"unitPrice":385,"target":"this_station"}""")
        assertEquals(385.0, GleoActions.number(direct, "unitPrice")!!, 0.0)
        val wrapped = GleoActions.parseParams(
            """{"packageName":"com.akaloopang.evauto","action":"SET_CHARGING_UNIT_PRICE","actionParameters":{"unitPrice":"삼백팔십오원","chargingSpeed":"ultra_fast"}}""",
        )
        assertEquals("숫자를 말로 넘겨도 읽는다", 385.0, GleoActions.number(wrapped, "unitPrice")!!, 0.0)
        assertEquals(ChargeType.ULTRA, GleoActions.chargeType(GleoActions.text(wrapped, "chargingSpeed")))
        assertEquals(SpokenSpeed.ULTRA_FAST, GleoActions.speed(wrapped))
        assertEquals("충전기 출력이 있으면 그게 우선", listOf(PowerTier.T100_TO_200),
            GleoActions.speed(GleoActions.parseParams("""{"unitPrice":391,"chargingSpeed":"fast","chargerKw":100}"""))!!.tiers)
        val doubleEncoded = GleoActions.parseParams("\"{\\\"amount\\\":47320,\\\"month\\\":\\\"2026-10\\\"}\"")
        assertEquals(47_320.0, GleoActions.number(doubleEncoded, "amount")!!, 0.0)
        assertEquals("2026-10", GleoActions.text(doubleEncoded, "month"))
        assertTrue(GleoActions.parseParams("not json").isEmpty())
    }

    @Test fun targets() {
        assertEquals(PriceTarget.CurrentStation, GleoActions.target(null))
        assertEquals(PriceTarget.Apartment, GleoActions.target("apartment"))
        assertEquals(PriceTarget.Provider("볼트업"), GleoActions.target("볼트업"))
    }

    @Test fun callback_follows_schema() {
        val text = GleoActions.callback(
            "SET_CHARGING_UNIT_PRICE", GleoActions.Status.SUCCESS,
            "Saved the unit price for the charging station.", listOf(mapOf("station" to "SK일렉링크 판교점", "unitPrice" to "385")),
        )
        val o = GleoActions.parseCallback(text)
        assertEquals("\"success\"", o["status"].toString())
        assertTrue("data는 JSON 배열 문자열", o["data"].toString().contains("판교점"))
    }
}
