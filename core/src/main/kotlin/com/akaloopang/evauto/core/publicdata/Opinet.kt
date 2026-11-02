package com.akaloopang.evauto.core.publicdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 오늘 전국 평균 유가 한 줄 */
data class FuelPrice(
    val product: FuelProduct,
    /** 원/L */
    val price: Double,
    val date: LocalDate?,
    /** 전일 대비(원) */
    val diff: Double? = null,
)

/** 오피넷 제품 코드 */
enum class FuelProduct(val code: String, val label: String) {
    GASOLINE("B027", "휘발유"),
    DIESEL("D047", "경유"),
    LPG("K015", "LPG"),
    PREMIUM("B034", "고급휘발유");

    companion object {
        fun of(code: String?): FuelProduct? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 한국석유공사 오피넷 무료 API — 전국 주유소 평균가격(avgAllPrice).
 * 내연기관차 대비 절감액의 '연료 가격'을 오늘 평균가로 채우는 데 쓴다. 키는 사용자가 오피넷에서 발급받는다.
 */
object Opinet {
    const val BASE = "https://www.opinet.co.kr/api"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun averageUrl(key: String): String = "$BASE/avgAllPrice.do?out=json&code=${URLEncoder.encode(key.trim(), "UTF-8")}"

    /** {"RESULT":{"OIL":[{"TRADE_DT":"20261022","PRODCD":"B027","PRODNM":"휘발유","PRICE":"1690.12","DIFF":"-0.53"}, …]}} */
    fun parseAverage(text: String): List<FuelPrice> {
        val root = runCatching { json.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return emptyList()
        val result = root["RESULT"] as? JsonObject ?: root
        val oil = when (val o = result["OIL"]) {
            is JsonArray -> o.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(o)
            else -> emptyList()
        }
        return oil.mapNotNull { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()
            val product = FuelProduct.of(s("PRODCD")) ?: return@mapNotNull null
            val price = s("PRICE")?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            FuelPrice(
                product = product,
                price = price,
                date = s("TRADE_DT")?.let { runCatching { LocalDate.parse(it, dateFmt) }.getOrNull() },
                diff = s("DIFF")?.replace("+", "")?.toDoubleOrNull(),
            )
        }.sortedBy { it.product.ordinal }
    }
}
