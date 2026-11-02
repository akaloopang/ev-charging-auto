package com.akaloopang.evauto.core.voice

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Gleo AI(플레오스 음성 비서) 연동 — Gleo Interface.
 *
 * 사용자가 "글레오, 충전 기록에 이 충전소 단가 385원으로 저장해 줘"라고 말하면 Gleo AI(LLM)가
 * 앱 스키마(assets/schema.json)의 function을 골라 파라미터를 채우고, Intent extra "ACTION_PARAM"에
 * JSON으로 담아 보낸다. 앱은 처리 결과를 Callback broadcast로 돌려주고 Gleo가 읽어 준다.
 */
object GleoActions {
    const val SET_UNIT_PRICE = "SET_CHARGING_UNIT_PRICE"
    const val SETTLE_APARTMENT_BILL = "SETTLE_APARTMENT_CHARGING_BILL"
    const val GET_COST_SUMMARY = "GET_CHARGING_COST_SUMMARY"
    /** 내연기관차 대비 절감액 */
    const val GET_SAVINGS = "GET_CHARGING_SAVINGS"
    /** 내 기록 속 충전소(가장 저렴했던·자주 가는·지난번·이름)로 길안내 */
    const val NAVIGATE_TO_STATION = "NAVIGATE_TO_CHARGING_STATION"
    /** 지금 내비로 가는 충전소의 예상 충전비 */
    const val GET_EXPECTED_COST = "GET_EXPECTED_CHARGING_COST"

    val ALL = listOf(SET_UNIT_PRICE, SETTLE_APARTMENT_BILL, GET_COST_SUMMARY, GET_SAVINGS, NAVIGATE_TO_STATION, GET_EXPECTED_COST)

    /** Gleo AI가 앱 응답을 받는 broadcast action */
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val EXTRA_PARAM = "ACTION_PARAM"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * ACTION_PARAM 해석. 문서 예시가 두 가지 모양이라 둘 다 받는다:
     *  - 파라미터 객체 그대로: {"unitPrice":385}
     *  - 전체 객체: {"packageName":..,"action":..,"actionParameters":{"unitPrice":385}}
     * 문자열로 한 번 더 감싸진 JSON("\"{...}\"")도 풀어 준다.
     */
    fun parseParams(raw: String?): Map<String, JsonPrimitive> {
        if (raw.isNullOrBlank()) return emptyMap()
        var element = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return emptyMap()
        // 문자열로 감싼 JSON이면 한 번 더 파싱
        if (element is JsonPrimitive && element.isString) {
            element = runCatching { json.parseToJsonElement(element.content) }.getOrNull() ?: return emptyMap()
        }
        val obj = element as? JsonObject ?: return emptyMap()
        val params = (obj["actionParameters"] as? JsonObject) ?: obj
        return params.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }.toMap()
    }

    fun number(params: Map<String, JsonPrimitive>, key: String): Double? {
        val p = params[key] ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.let { KoreanNumber.parse(it.replace("원", "")) }
    }

    fun text(params: Map<String, JsonPrimitive>, key: String): String? = params[key]?.contentOrNull?.takeIf { it.isNotBlank() }

    /** chargingSpeed 파라미터 → 충전 방식 */
    fun chargeType(raw: String?): ChargeType? = when (raw?.lowercase()?.replace(" ", "")?.replace("-", "_")) {
        null, "" -> null
        "slow", "ac", "완속" -> ChargeType.AC
        "fast", "dc", "급속" -> ChargeType.DC
        "ultra_fast", "ultrafast", "ultra", "초급속" -> ChargeType.ULTRA
        else -> null
    }

    /**
     * 충전 속도: chargerKw(충전기에 적힌 출력)가 있으면 그 구간, 없으면 chargingSpeed 단어(slow/fast/ultra_fast).
     * 둘 다 없으면 null — 앱이 공시 요금표·최근 충전으로 정하고, 갈리면 ASKING으로 출력을 되묻는다.
     */
    fun speed(params: Map<String, JsonPrimitive>): SpokenSpeed? {
        number(params, "chargerKw")?.let { kw -> SpokenSpeed.ofKw(kw)?.let { return it } }
        return chargeType(text(params, "chargingSpeed"))?.let { SpokenSpeed.of(it) }
    }

    /** target 파라미터 → 적용 대상 */
    fun target(raw: String?): PriceTarget = when (raw?.lowercase()?.replace(" ", "")) {
        null, "", "this_station", "current_station", "station", "이충전소", "여기" -> PriceTarget.CurrentStation
        "apartment", "home", "아파트", "집" -> PriceTarget.Apartment
        else -> PriceTarget.Provider(raw)
    }

    enum class Status(val key: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail"), ERROR("error") }

    /** Callback Schema JSON */
    fun callback(functionName: String, status: Status, message: String, data: List<Map<String, String>> = emptyList()): String {
        val o = buildJsonObject {
            put("functionName", functionName)
            put("status", status.key)
            put("message", message)
            if (data.isNotEmpty()) {
                val arr: JsonArray = buildJsonArray { data.forEach { m -> add(buildJsonObject { m.forEach { (k, v) -> put(k, v) } }) } }
                put("data", json.encodeToString(JsonArray.serializer(), arr))
            }
            put("mode", "voice")
        }
        return json.encodeToString(JsonObject.serializer(), o)
    }

    /** 테스트·로그용: callback 문자열 → 객체 */
    fun parseCallback(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}
