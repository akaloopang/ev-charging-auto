package com.akaloopang.evauto.core.publicdata

import com.akaloopang.evauto.core.session.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 한국환경공단 전기자동차 충전소 정보 (공공데이터포털 Open API, B552584/EvCharger).
 *
 *  - getChargerInfo: 충전기 목록 (충전소 이름·주소·좌표·운영기관·출력·상태)
 *  - getChargerStatus: 최근 [period]분 사이 상태가 바뀐 충전기만
 *
 * 내 위치를 서버에 보내지 않는다 — 시·도 단위(zcode)로 목록을 받아 기기 안에서 가까운 충전소를 찾는다.
 * 키는 사용자가 공공데이터포털에서 발급받아 넣는다(앱에 키를 넣어 배포하지 않는다).
 */
data class PublicCharger(
    val statId: String,
    val chgerId: String,
    val stationName: String,
    val address: String? = null,
    val location: GeoPoint? = null,
    /** 운영기관 (예: "SK일렉링크", "환경부") */
    val operator: String? = null,
    /** 충전 용량(kW) */
    val outputKw: Int? = null,
    /** 충전기 타입 코드 (02 AC완속, 04 DC콤보 …) */
    val chargerType: String? = null,
    /** 1 통신이상 · 2 충전대기 · 3 충전중 · 4 운영중지 · 5 점검중 · 9 상태미확인 */
    val stat: Int? = null,
    val statUpdatedAt: LocalDateTime? = null,
    val useTime: String? = null,
    val parkingFree: Boolean? = null,
    /** 이용자 제한 (아파트 등 입주민 전용) */
    val limited: Boolean? = null,
) {
    val available: Boolean get() = stat == STAT_AVAILABLE
    val charging: Boolean get() = stat == STAT_CHARGING
    val outOfService: Boolean get() = stat == 1 || stat == 4 || stat == 5
    val isAc: Boolean get() = chargerType == "02" || chargerType == "08"

    companion object {
        const val STAT_AVAILABLE = 2
        const val STAT_CHARGING = 3
    }
}

/** 충전소 하나 (충전기 여러 대) */
data class PublicStation(
    val statId: String,
    val name: String,
    val address: String?,
    val location: GeoPoint?,
    val operator: String?,
    val chargers: List<PublicCharger>,
) {
    val maxKw: Int? get() = chargers.mapNotNull { it.outputKw }.maxOrNull()
    val availableCount: Int get() = chargers.count { it.available }
    val chargingCount: Int get() = chargers.count { it.charging }
    val outOfServiceCount: Int get() = chargers.count { it.outOfService }
    val statusKnown: Boolean get() = chargers.any { it.stat != null && it.stat != 9 }
    val lastUpdated: LocalDateTime? get() = chargers.mapNotNull { it.statUpdatedAt }.maxOrNull()

    /** 출력 구간별 빈 충전기 (급속만 / 완속만 보고 싶을 때) */
    fun available(fast: Boolean?): Int = chargers.count { it.available && (fast == null || fast == !it.isAc) }
    fun total(fast: Boolean?): Int = chargers.count { fast == null || fast == !it.isAc }
}

data class PublicPage(
    val items: List<PublicCharger>,
    val totalCount: Int,
    val pageNo: Int,
    val numOfRows: Int,
    val resultCode: String?,
    val resultMsg: String?,
) {
    val ok: Boolean get() = resultCode == null || resultCode == "00"
    val hasMore: Boolean get() = pageNo * numOfRows < totalCount
}

object EvChargerApi {
    const val BASE = "https://apis.data.go.kr/B552584/EvCharger"
    const val MAX_ROWS = 9999

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val timeFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * 공공데이터포털 키는 '인코딩 키'(%가 들어 있음)와 '디코딩 키' 두 가지로 준다. 어느 것을 넣어도 되게 한다.
     */
    fun encodeKey(key: String): String = if (key.contains('%')) key.trim() else URLEncoder.encode(key.trim(), "UTF-8")

    fun infoUrl(key: String, zcode: String, page: Int, rows: Int = MAX_ROWS): String =
        "$BASE/getChargerInfo?serviceKey=${encodeKey(key)}&pageNo=$page&numOfRows=$rows&zcode=$zcode&dataType=JSON"

    /** 최근 [periodMinutes]분 사이 상태가 바뀐 충전기 */
    fun statusUrl(key: String, zcode: String, periodMinutes: Int, page: Int, rows: Int = MAX_ROWS): String =
        "$BASE/getChargerStatus?serviceKey=${encodeKey(key)}&pageNo=$page&numOfRows=$rows&period=$periodMinutes&zcode=$zcode&dataType=JSON"

    /**
     * 응답 해석. 공식 JSON은 {"resultCode","resultMsg","totalCount","pageNo","numOfRows","items":{"item":[…]}} 이고,
     * 게이트웨이를 거치면 {"response":{"header":…,"body":…}}로 감싸여 오기도 해서 둘 다 받는다.
     * 키 오류 등은 XML로 오기도 한다 → 빈 목록과 오류 메시지.
     */
    fun parse(text: String): PublicPage {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            val msg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>|<resultMsg>(.*?)</resultMsg>").find(trimmed)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            return PublicPage(emptyList(), 0, 1, 0, "XML", msg ?: "응답 형식 오류")
        }
        var root = json.parseToJsonElement(trimmed).jsonObject
        (root["response"] as? JsonObject)?.let { resp ->
            val header = resp["header"] as? JsonObject
            val body = resp["body"] as? JsonObject
            root = JsonObject(buildMap {
                header?.let { putAll(it) }
                body?.let { putAll(it) }
            })
        }
        val itemsEl: JsonElement? = (root["items"] as? JsonObject)?.get("item") ?: root["items"]
        val items = when (itemsEl) {
            is JsonArray -> itemsEl.mapNotNull { (it as? JsonObject)?.let(::charger) }
            is JsonObject -> listOfNotNull(charger(itemsEl))
            else -> emptyList()
        }
        return PublicPage(
            items = items,
            totalCount = root.int("totalCount") ?: items.size,
            pageNo = root.int("pageNo") ?: 1,
            numOfRows = root.int("numOfRows") ?: items.size,
            resultCode = root.str("resultCode"),
            resultMsg = root.str("resultMsg"),
        )
    }

    private fun charger(o: JsonObject): PublicCharger? {
        val statId = o.str("statId") ?: return null
        val lat = o.str("lat")?.toDoubleOrNull()
        val lng = o.str("lng")?.toDoubleOrNull()
        return PublicCharger(
            statId = statId,
            chgerId = o.str("chgerId") ?: "",
            stationName = o.str("statNm") ?: "",
            address = o.str("addr"),
            location = if (lat != null && lng != null && lat != 0.0 && lng != 0.0) GeoPoint(lat, lng) else null,
            operator = o.str("busiNm") ?: o.str("bnm"),
            outputKw = o.str("output")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
            chargerType = o.str("chgerType"),
            stat = o.str("stat")?.toIntOrNull(),
            statUpdatedAt = o.str("statUpdDt")?.let { runCatching { LocalDateTime.parse(it, timeFmt) }.getOrNull() },
            useTime = o.str("useTime"),
            parkingFree = o.str("parkingFree")?.let { it == "Y" },
            limited = o.str("limitYn")?.let { it == "Y" },
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

    /** 충전기 → 충전소로 묶기 */
    fun group(chargers: List<PublicCharger>): List<PublicStation> =
        chargers.filter { it.stationName.isNotBlank() }
            .groupBy { it.statId }
            .map { (id, cs) ->
                val first = cs.first()
                PublicStation(id, first.stationName, first.address, cs.firstNotNullOfOrNull { it.location }, first.operator, cs.sortedBy { it.chgerId })
            }

    /** 내가 아는 위치들 가까이(반경 [radiusM]) 있는 충전기만 남긴다 — 시·도 전체를 기기에 쌓아 두지 않으려고 */
    fun near(chargers: List<PublicCharger>, points: List<GeoPoint>, radiusM: Double = KEEP_RADIUS_M): List<PublicCharger> =
        if (points.isEmpty()) emptyList()
        else chargers.filter { c -> c.location != null && points.any { it.distanceTo(c.location) <= radiusM } }

    /** [at]에서 가장 가까운 충전소 (반경 [radiusM] 안). 충전한 자리와 공공데이터 충전소를 짝짓는다 */
    fun nearest(stations: List<PublicStation>, at: GeoPoint, radiusM: Double = MATCH_RADIUS_M): PublicStation? =
        stations.filter { it.location != null }
            .map { it to it.location!!.distanceTo(at) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }?.first

    /** 상태 변경분을 합친다 (충전소·충전기 id가 같은 것만, 더 최근 값으로) */
    fun applyStatus(chargers: List<PublicCharger>, updates: List<PublicCharger>): List<PublicCharger> {
        if (updates.isEmpty()) return chargers
        val byId = updates.associateBy { it.statId + "/" + it.chgerId }
        return chargers.map { c ->
            val u = byId[c.statId + "/" + c.chgerId] ?: return@map c
            val newer = c.statUpdatedAt == null || u.statUpdatedAt == null || !u.statUpdatedAt.isBefore(c.statUpdatedAt)
            if (newer && u.stat != null) c.copy(stat = u.stat, statUpdatedAt = u.statUpdatedAt ?: c.statUpdatedAt) else c
        }
    }

    /** 기기에 남겨 둘 반경 — 내 충전소 주변 */
    const val KEEP_RADIUS_M = 500.0
    /** 충전한 자리와 같은 충전소로 볼 거리 */
    const val MATCH_RADIUS_M = 150.0
    /** 상태 변경분 조회 범위(분). 마지막 갱신이 이보다 오래됐으면 목록을 새로 받는다 */
    const val STATUS_PERIOD_MIN = 10
}

/**
 * 시·도 코드(zcode). 주소(기기 Geocoder가 준 도로명·지번 주소)의 시·도 이름으로 고른다.
 * 강원·전북은 특별자치도 출범으로 코드가 바뀌어(42→51, 45→52) 새 코드부터 시도하고 비면 옛 코드로.
 */
object RegionCode {
    private val table: List<Pair<List<String>, List<String>>> = listOf(
        listOf("서울") to listOf("11"),
        listOf("부산") to listOf("26"),
        listOf("대구") to listOf("27"),
        listOf("인천") to listOf("28"),
        listOf("광주광역시", "광주") to listOf("29"),
        listOf("대전") to listOf("30"),
        listOf("울산") to listOf("31"),
        listOf("세종") to listOf("36"),
        listOf("경기") to listOf("41"),
        listOf("강원") to listOf("51", "42"),
        listOf("충청북도", "충북") to listOf("43"),
        listOf("충청남도", "충남") to listOf("44"),
        listOf("전라북도", "전북") to listOf("52", "45"),
        listOf("전라남도", "전남") to listOf("46"),
        listOf("경상북도", "경북") to listOf("47"),
        listOf("경상남도", "경남") to listOf("48"),
        listOf("제주") to listOf("50"),
    )

    /** 화면에서 직접 고르는 시·도 (이름, 코드 후보) — 기기에 지오코더가 없을 때 */
    val choices: List<Pair<String, List<String>>> = table.map { (names, codes) -> names.last() to codes }

    fun label(code: String?): String? = choices.firstOrNull { code != null && code in it.second }?.first

    /** 주소 → 시도 코드 후보 (앞의 것부터 시도). 모르면 빈 목록 */
    fun fromAddress(address: String?): List<String> {
        val tokens = address?.split(' ', ',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "대한민국" && it != "South" && it != "Korea" }
            ?: return emptyList()
        // 시·도는 주소의 첫 낱말 — "경기도 광주시"의 광주를 광주광역시로 잘못 읽지 않게 첫 낱말만 본다
        val first = tokens.firstOrNull() ?: return emptyList()
        return table.firstOrNull { (names, _) -> names.any { first.startsWith(it) } }?.second.orEmpty()
    }
}

/** 기기에 저장해 두는 공공데이터 (내 충전소 주변 충전기만) */
data class PublicCache(
    val zcode: String,
    /** 목록(getChargerInfo)을 마지막으로 받은 때 */
    val infoAt: LocalDateTime,
    /** 상태를 마지막으로 맞춘 때 */
    val statusAt: LocalDateTime,
    val chargers: List<PublicCharger>,
) {
    val stations: List<PublicStation> get() = EvChargerApi.group(chargers)

    fun encode(): String = kotlinx.serialization.json.buildJsonObject {
        put("zcode", JsonPrimitive(zcode))
        put("infoAt", JsonPrimitive(infoAt.toString()))
        put("statusAt", JsonPrimitive(statusAt.toString()))
        put("chargers", JsonArray(chargers.map { c ->
            JsonObject(buildMap {
                put("statId", JsonPrimitive(c.statId))
                put("chgerId", JsonPrimitive(c.chgerId))
                put("statNm", JsonPrimitive(c.stationName))
                c.address?.let { put("addr", JsonPrimitive(it)) }
                c.location?.let { put("lat", JsonPrimitive(it.lat.toString())); put("lng", JsonPrimitive(it.lon.toString())) }
                c.operator?.let { put("busiNm", JsonPrimitive(it)) }
                c.outputKw?.let { put("output", JsonPrimitive(it.toString())) }
                c.chargerType?.let { put("chgerType", JsonPrimitive(it)) }
                c.stat?.let { put("stat", JsonPrimitive(it.toString())) }
                c.statUpdatedAt?.let { put("statUpdDt", JsonPrimitive(it.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))) }
                c.useTime?.let { put("useTime", JsonPrimitive(it)) }
                c.parkingFree?.let { put("parkingFree", JsonPrimitive(if (it) "Y" else "N")) }
                c.limited?.let { put("limitYn", JsonPrimitive(if (it) "Y" else "N")) }
            })
        }))
    }.toString()

    companion object {
        fun decode(text: String): PublicCache? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            val s = { k: String -> (o[k] as JsonPrimitive).content }
            val items = (o["chargers"] as? JsonArray)?.toString() ?: "[]"
            PublicCache(
                zcode = s("zcode"),
                infoAt = LocalDateTime.parse(s("infoAt")),
                statusAt = LocalDateTime.parse(s("statusAt")),
                chargers = EvChargerApi.parse("{\"items\":{\"item\":$items}}").items,
            )
        }.getOrNull()
    }
}
