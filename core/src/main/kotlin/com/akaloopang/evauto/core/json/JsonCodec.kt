package com.akaloopang.evauto.core.json

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.StationPrice
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationNameSource
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.RoundingRule
import com.akaloopang.evauto.core.session.ActiveSession
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeState
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.VehicleSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * JSON 변환. 백업 파일 형식:
 *   { "version": 1, "exportedAt": "...", "records": [...], "settings": {...} }
 * 차량 앱만 쓰는 값은 "car"(기록), "carSettings"(설정) 키에 따로 둔다 — 다른 도구가 읽을 때 모르는 키는 무시하면 된다.
 *
 * @Serializable 컴파일러 플러그인 없이 JsonElement 트리로 직접 만든다(의존성 최소화).
 */
object JsonCodec {
    const val BACKUP_VERSION = 1

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** ISO-8601 로컬 시각, 밀리초 */
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    fun formatDate(d: LocalDateTime): String = d.truncatedTo(ChronoUnit.MILLIS).format(DATE_FMT)

    /** 로컬 ISO 시각, 또는 Z/오프셋이 붙은 시각(→ [zone] 로컬로 변환) 모두 받는다. */
    fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime {
        val s = raw.trim()
        val hasOffset = s.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(s.substringAfter('T', ""))
        return if (hasOffset) OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime()
        else LocalDateTime.parse(s)
    }

    // ── 작은 도우미 ───────────────────────────────────────
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
    private fun num(v: Double?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun num(v: Int?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun txt(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun flag(v: Boolean?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull

    // ── 기록 ─────────────────────────────────────────────
    fun encodeRecord(r: ChargingRecord): JsonObject = buildJsonObject {
        put("id", r.id)
        put("date", formatDate(r.date))
        put("provider", r.provider)
        put("kWh", r.kWh)
        put("unitPrice", r.unitPrice)
        put("paymentAmount", r.paymentAmount)
        put("chargeType", txt(r.chargeType?.key))
        put("chargeSpeedKw", num(r.chargeSpeedKw))
        put("chargingMinutes", num(r.chargingMinutes))
        put("odometer", num(r.odometer))
        put("memo", txt(r.memo))
        r.car?.let { put("car", encodeCarMeta(it)) }
    }

    private fun encodeCarMeta(c: CarMeta): JsonObject = buildJsonObject {
        put("source", c.source.key)
        put("amountStatus", c.amountStatus.key)
        put("socStart", num(c.socStart))
        put("socEnd", num(c.socEnd))
        put("endDate", txt(c.endDate?.let(::formatDate)))
        put("estimatedKwh", num(c.estimatedKwh))
        put("estimatedAmount", num(c.estimatedAmount))
        put("batteryKwh", num(c.batteryKwh))
        put("observedPeakKw", num(c.observedPeakKw))
        if (c.peakFromAverage) put("peakFromAverage", true)
        put("place", c.place.key)
        put("confirmed", c.confirmed)
        put("lowConfidence", c.lowConfidence)
        put("stationKey", txt(c.stationKey))
        put("priceSource", txt(c.priceSource?.key))
        put("tier", txt(c.tier?.name))
        put("tierExact", c.tierExact)
        put("askStationPrice", c.askStationPrice)
        put("priceMaybeOutdated", c.priceMaybeOutdated)
        c.priceRef?.let { put("priceRef", it) }
        c.priceRefDate?.let { put("priceRefDate", it.toString()) }
    }

    fun decodeRecord(o: JsonObject, zone: ZoneId = ZoneId.systemDefault()): ChargingRecord = ChargingRecord(
        id = o.str("id") ?: error("id 없음"),
        date = parseDate(o.str("date") ?: error("date 없음"), zone),
        provider = o.str("provider") ?: "",
        kWh = o.dbl("kWh") ?: 0.0,
        unitPrice = o.dbl("unitPrice") ?: 0.0,
        paymentAmount = o.dbl("paymentAmount") ?: 0.0,
        chargeType = ChargeType.fromKey(o.str("chargeType")),
        chargeSpeedKw = o.dbl("chargeSpeedKw"),
        chargingMinutes = o.int("chargingMinutes"),
        odometer = o.dbl("odometer"),
        memo = o.str("memo"),
        car = (o["car"] as? JsonObject)?.let { decodeCarMeta(it, zone) },
    )

    private fun decodeCarMeta(o: JsonObject, zone: ZoneId): CarMeta = CarMeta(
        source = RecordSource.fromKey(o.str("source")) ?: RecordSource.MANUAL,
        amountStatus = AmountStatus.fromKey(o.str("amountStatus")) ?: AmountStatus.ESTIMATED,
        socStart = o.int("socStart"),
        socEnd = o.int("socEnd"),
        endDate = o.str("endDate")?.let { parseDate(it, zone) },
        estimatedKwh = o.dbl("estimatedKwh"),
        estimatedAmount = o.dbl("estimatedAmount"),
        batteryKwh = o.dbl("batteryKwh"),
        observedPeakKw = o.dbl("observedPeakKw"),
        peakFromAverage = o.bool("peakFromAverage") ?: false,
        place = PlaceKind.fromKey(o.str("place")) ?: PlaceKind.UNKNOWN,
        confirmed = o.bool("confirmed") ?: false,
        lowConfidence = o.bool("lowConfidence") ?: false,
        stationKey = o.str("stationKey"),
        priceSource = PriceSource.fromKey(o.str("priceSource")),
        tier = o.str("tier")?.let { n -> PowerTier.entries.firstOrNull { it.name == n } },
        tierExact = o.bool("tierExact") ?: false,
        askStationPrice = o.bool("askStationPrice") ?: false,
        priceMaybeOutdated = o.bool("priceMaybeOutdated") ?: false,
        priceRef = o.str("priceRef"),
        priceRefDate = o.str("priceRefDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    // ── 단가 설정 ─────────────────────────────────────────
    fun encodePriceSettings(p: PriceSettings): JsonObject = buildJsonObject {
        put("apartmentAcUnitPrice", num(p.apartmentAcUnitPrice))
        put("apartmentDcUnitPrice", num(p.apartmentDcUnitPrice))
        put("apartmentUltraUnitPrice", num(p.apartmentUltraUnitPrice))
        put("providers", buildJsonArray { p.providers.forEach { add(JsonPrimitive(it)) } })
        put("providerPrices", buildJsonArray {
            p.providerPrices.forEach { pp ->
                add(buildJsonObject {
                    put("provider", pp.provider)
                    put("acUnitPrice", num(pp.acUnitPrice))
                    put("dcUnitPrice", num(pp.dcUnitPrice))
                    put("ultraUnitPrice", num(pp.ultraUnitPrice))
                    // 출력 구간별 단가 (이 앱에서 추가 — 모바일 앱은 모르는 키라 무시한다)
                    if (pp.tierPrices.isNotEmpty()) {
                        put("tierPrices", buildJsonObject { pp.tierPrices.entries.sortedBy { it.key.ordinal }.forEach { (t, v) -> put(t.name, v) } })
                    }
                })
            }
        })
        put("gasEfficiency", num(p.gasEfficiency))
        put("gasPrice", num(p.gasPrice))
    }

    fun decodePriceSettings(o: JsonObject): PriceSettings {
        val legacy = o.dbl("apartmentUnitPrice") // 구버전 단일 아파트 단가
        fun pos(v: Double?) = v?.takeIf { it > 0 }
        val saved = (o["providers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return PriceSettings(
            apartmentAcUnitPrice = pos(o.dbl("apartmentAcUnitPrice") ?: legacy),
            apartmentDcUnitPrice = pos(o.dbl("apartmentDcUnitPrice") ?: legacy),
            apartmentUltraUnitPrice = pos(o.dbl("apartmentUltraUnitPrice")),
            providers = ProviderCatalog.mergeProviders(saved),
            providerPrices = (o["providerPrices"] as? JsonArray)?.mapNotNull { e ->
                val po = e as? JsonObject ?: return@mapNotNull null
                ProviderUnitPrice(
                    provider = po.str("provider") ?: return@mapNotNull null,
                    acUnitPrice = po.dbl("acUnitPrice"),
                    dcUnitPrice = po.dbl("dcUnitPrice"),
                    ultraUnitPrice = po.dbl("ultraUnitPrice"),
                    tierPrices = (po["tierPrices"] as? JsonObject)?.mapNotNull { (k, v) ->
                        val tier = PowerTier.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                        val price = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                        tier to price
                    }?.toMap() ?: emptyMap(),
                )
            } ?: emptyList(),
            gasEfficiency = pos(o.dbl("gasEfficiency")),
            gasPrice = pos(o.dbl("gasPrice")),
        )
    }

    // ── 차량 설정 ─────────────────────────────────────────
    fun encodeCarSettings(c: CarSettings): JsonObject = buildJsonObject {
        put("batteryCapacityKwh", num(c.batteryCapacityKwh))
        put("home", c.home?.let { h ->
            buildJsonObject { put("lat", h.center.lat); put("lon", h.center.lon); put("radiusM", h.radiusM) }
        } ?: JsonNull)
        put("homeProvider", c.homeProvider)
        put("targetSoc", c.targetSoc)
        put("roundingRule", c.roundingRule.key)
        put("useMemberTariff", c.useMemberTariff)
        put("setupDone", c.setupDone)
        put("homeCharging", c.homeCharging)
        put("voiceAnnounce", c.voiceAnnounce)
    }

    fun decodeCarSettings(o: JsonObject): CarSettings = CarSettings(
        batteryCapacityKwh = o.dbl("batteryCapacityKwh")?.takeIf { it > 0 },
        home = (o["home"] as? JsonObject)?.let { h ->
            val lat = h.dbl("lat"); val lon = h.dbl("lon")
            if (lat == null || lon == null) null
            else HomeZone(GeoPoint(lat, lon), h.dbl("radiusM") ?: HomeZone.DEFAULT_RADIUS_M)
        },
        homeProvider = o.str("homeProvider") ?: ProviderCatalog.APARTMENT,
        targetSoc = (o.int("targetSoc") ?: 80).coerceIn(10, 100),
        roundingRule = RoundingRule.fromKey(o.str("roundingRule")),
        useMemberTariff = o.bool("useMemberTariff") ?: true,
        setupDone = o.bool("setupDone") ?: false,
        homeCharging = o.bool("homeCharging") ?: true,
        voiceAnnounce = o.bool("voiceAnnounce") ?: true,
    )

    // ── 백업 파일 ─────────────────────────────────────────
    data class Backup(
        val records: List<ChargingRecord>,
        val price: PriceSettings,
        val car: CarSettings?,
        val stationPrices: StationPriceBook = StationPriceBook(),
        /** 기록에 남은 충전소 키 → 충전소 정보 (확인 단가 등록용) */
        val stations: List<StationRef> = emptyList(),
    )

    fun encodeBackup(
        records: List<ChargingRecord>,
        price: PriceSettings,
        car: CarSettings?,
        exportedAt: LocalDateTime,
        stationPrices: StationPriceBook = StationPriceBook(),
        stations: List<StationRef> = emptyList(),
    ): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("version", BACKUP_VERSION)
            put("exportedAt", formatDate(exportedAt))
            put("records", JsonArray(records.map(::encodeRecord)))
            put("settings", encodePriceSettings(price))
            car?.let { put("carSettings", encodeCarSettings(it)) }
            if (stationPrices.prices.isNotEmpty()) put("stationPrices", JsonArray(stationPrices.prices.map(::encodeStationPrice)))
            if (stations.isNotEmpty()) put("stations", JsonArray(stations.map(::encodeStation)))
        })

    fun encodeStation(st: StationRef): JsonObject = buildJsonObject {
        put("key", st.key)
        put("name", txt(st.name))
        put("nameSource", txt(st.nameSource?.key))
        put("operator", txt(st.operator))
        put("address", txt(st.address))
        put("lat", num(st.location?.lat))
        put("lon", num(st.location?.lon))
    }

    fun decodeStation(o: JsonObject): StationRef? {
        val key = o.str("key") ?: return null
        val loc = o.dbl("lat")?.let { lat -> o.dbl("lon")?.let { GeoPoint(lat, it) } }
        val name = o.str("name")
        return StationRef(
            key, name, loc, o.str("operator"),
            address = o.str("address"),
            // 예전 백업: 이름이 있으면 내비 이름이었다
            nameSource = StationNameSource.fromKey(o.str("nameSource")) ?: name?.let { StationNameSource.NAVI },
        )
    }

    fun encodeStationPrice(p: StationPrice): JsonObject = buildJsonObject {
        put("station", encodeStation(p.station))
        put("tier", txt(p.tier?.name))
        put("chargeType", txt(p.chargeType?.key))
        put("unitPrice", p.unitPrice)
        put("confirmedOn", p.confirmedOn.toString())
    }

    fun decodeStationPrice(o: JsonObject): StationPrice? {
        val st = (o["station"] as? JsonObject)?.let(::decodeStation) ?: return null
        val tier = o.str("tier")?.let { n -> PowerTier.entries.firstOrNull { it.name == n } }
        val type = ChargeType.fromKey(o.str("chargeType"))
        // 0원은 무료 충전소 표시(구간·방식 없음)일 때만 받는다
        val price = o.dbl("unitPrice")?.takeIf { it > 0 || (it == 0.0 && tier == null && type == null) } ?: return null
        return StationPrice(
            station = st,
            tier = tier,
            chargeType = type,
            unitPrice = price,
            confirmedOn = o.str("confirmedOn")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        )
    }

    /** 백업 형식이 아니면 null. 깨진 기록 1건 때문에 전체가 실패하지 않게 건너뛴다. */
    fun decodeBackup(text: String, zone: ZoneId = ZoneId.systemDefault()): Backup? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val arr = root["records"] as? JsonArray ?: return null
        val records = arr.mapNotNull { e -> runCatching { decodeRecord(e.jsonObject, zone) }.getOrNull() }
        val price = (root["settings"] as? JsonObject)?.let(::decodePriceSettings) ?: PriceSettings()
        val car = (root["carSettings"] as? JsonObject)?.let(::decodeCarSettings)
        val stationPrices = (root["stationPrices"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decodeStationPrice) } ?: emptyList()
        val stations = (root["stations"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decodeStation) } ?: emptyList()
        return Backup(records, price, car, StationPriceBook(stationPrices), stations)
    }

    // ── 앱 상태 (마지막으로 본 차량 상태 · 진행 중 세션) ─────────
    fun encodeSnapshot(s: VehicleSnapshot): JsonObject = buildJsonObject {
        put("time", formatDate(s.time))
        put("soc", num(s.socPercent))
        put("odometer", num(s.odometerKm))
        put("chargeState", txt(s.chargeState?.name))
        put("portConnected", flag(s.portConnected))
        put("chargeRateKw", num(s.chargeRateKw))
        put("ignitionOn", flag(s.ignitionOn))
        put("lat", num(s.location?.lat))
        put("lon", num(s.location?.lon))
        put("capacity", num(s.batteryCapacityKwh))
    }

    fun decodeSnapshot(o: JsonObject): VehicleSnapshot = VehicleSnapshot(
        time = LocalDateTime.parse(o.str("time")),
        socPercent = o.dbl("soc"),
        odometerKm = o.dbl("odometer"),
        chargeState = o.str("chargeState")?.let { n -> ChargeState.entries.firstOrNull { it.name == n } },
        portConnected = o.bool("portConnected"),
        chargeRateKw = o.dbl("chargeRateKw"),
        ignitionOn = o.bool("ignitionOn"),
        location = o.dbl("lat")?.let { lat -> o.dbl("lon")?.let { lon -> GeoPoint(lat, lon) } },
        batteryCapacityKwh = o.dbl("capacity"),
    )

    fun encodeActive(a: ActiveSession): JsonObject = buildJsonObject {
        put("start", formatDate(a.start))
        put("socStart", num(a.socStart))
        put("odometerStart", num(a.odometerStart))
        put("lastChargingTime", formatDate(a.lastChargingTime))
        put("socLatest", num(a.socLatest))
        put("odometerLatest", num(a.odometerLatest))
        put("peakKw", num(a.peakKw))
        put("integratedKwh", a.integratedKwh)
        put("integratedMinutes", a.integratedMinutes)
        put("lat", num(a.location?.lat)); put("lon", num(a.location?.lon))
        put("lastLat", num(a.lastKnownLocation?.lat)); put("lastLon", num(a.lastKnownLocation?.lon))
        put("capacity", num(a.batteryCapacityKwh))
        put("stateReported", a.stateReported)
    }

    fun decodeActive(o: JsonObject): ActiveSession = ActiveSession(
        start = LocalDateTime.parse(o.str("start")),
        socStart = o.dbl("socStart"),
        odometerStart = o.dbl("odometerStart"),
        lastChargingTime = LocalDateTime.parse(o.str("lastChargingTime")),
        socLatest = o.dbl("socLatest"),
        odometerLatest = o.dbl("odometerLatest"),
        peakKw = o.dbl("peakKw"),
        integratedKwh = o.dbl("integratedKwh") ?: 0.0,
        integratedMinutes = o.dbl("integratedMinutes") ?: 0.0,
        location = o.dbl("lat")?.let { lat -> o.dbl("lon")?.let { GeoPoint(lat, it) } },
        lastKnownLocation = o.dbl("lastLat")?.let { lat -> o.dbl("lastLon")?.let { GeoPoint(lat, it) } },
        batteryCapacityKwh = o.dbl("capacity"),
        stateReported = o.bool("stateReported") ?: false,
    )

    fun parseObject(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    fun stringify(o: JsonObject): String = json.encodeToString(JsonObject.serializer(), o)
}
