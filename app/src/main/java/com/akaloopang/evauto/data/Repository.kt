package com.akaloopang.evauto.data

import android.util.Log
import com.akaloopang.evauto.core.json.JsonCodec
import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.pricing.AmountPolicy
import com.akaloopang.evauto.core.pricing.BillSettlement
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.Receipt
import com.akaloopang.evauto.core.pricing.RepricePolicy
import com.akaloopang.evauto.core.pricing.StationNameSource
import com.akaloopang.evauto.core.pricing.StationPriceBook
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.session.ActiveSession
import com.akaloopang.evauto.core.session.CarSettings
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.session.PricingContext
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.session.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 차량 내 로컬 저장소. 서버로 보내지 않는다.
 *
 *  - data.json    : 기록 + 단가 설정 + 차량 설정 + 충전소 확인 단가 (백업 파일과 같은 형식)
 *  - monitor.json : 마지막으로 본 차량 상태, 진행 중 충전, 집 위치 후보
 */
class Repository(private val dir: File) {

    data class MonitorState(
        val lastSeen: VehicleSnapshot? = null,
        val active: ActiveSession? = null,
        /** '집 충전기가 맞나요?' 확인을 기다리는 위치 (첫 아파트 충전) */
        val homeCandidate: GeoPoint? = null,
    )

    private val mutex = Mutex()
    private val dataFile = File(dir, "data.json")
    private val monitorFile = File(dir, "monitor.json")

    /** 앱에 들어 있는 공시 요금표 스냅샷 */
    val tariff: TariffTable? = runCatching { TariffTable.loadBundled() }.getOrNull()

    private val _records = MutableStateFlow<List<ChargingRecord>>(emptyList())
    val records: StateFlow<List<ChargingRecord>> = _records.asStateFlow()

    private val _price = MutableStateFlow(PriceSettings())
    val price: StateFlow<PriceSettings> = _price.asStateFlow()

    private val _car = MutableStateFlow(CarSettings())
    val car: StateFlow<CarSettings> = _car.asStateFlow()

    private val _book = MutableStateFlow(StationPriceBook())
    /** 충전소별 확인 단가 */
    val book: StateFlow<StationPriceBook> = _book.asStateFlow()

    /** 기록에 남은 충전소 키 → 충전소 정보 (기록에는 좌표를 남기지 않고 여기에만 둔다) */
    private val _stations = MutableStateFlow<List<StationRef>>(emptyList())

    private val _monitor = MutableStateFlow(MonitorState())
    val monitor: StateFlow<MonitorState> = _monitor.asStateFlow()

    fun pricing(): PricingContext = PricingContext(_price.value, _book.value, tariff, _car.value.useMemberTariff)

    fun station(key: String?): StationRef? = key?.let { k -> _stations.value.firstOrNull { it.key == k } }

    /** 등록된 충전소 전체 (GPS·이름·주소) */
    val stations: StateFlow<List<StationRef>> = _stations.asStateFlow()

    /**
     * 충전소 이름 바꾸기 (예: "집 앞 마트 SK"). 같은 자리(GPS)·같은 사업자로 알아본 충전소에 모두 적용하고,
     * 확인 단가 장부의 표시 이름도 맞춘다. 빈 이름이면 사용자 이름을 지운다(내비 이름·주소로 표시).
     */
    suspend fun renameStation(key: String, newName: String) {
        val base = station(key) ?: return
        val name = newName.trim().takeIf { it.isNotEmpty() }
        val renamed = _stations.value.map { st ->
            if (st.key == key || st.sameAs(base)) {
                if (name != null) st.copy(name = name, nameSource = StationNameSource.USER)
                else if (st.nameSource == StationNameSource.USER) st.copy(name = null, nameSource = null)
                else st
            } else st
        }
        _stations.value = renamed
        var book = _book.value
        renamed.filter { it.key == key || it.sameAs(base) }.forEach { book = book.rename(it) }
        _book.value = book
        mutex.withLock { writeData() }
    }

    /** 앱 시작 시 한 번. 파일이 작아서 바로 읽는다. */
    fun load() {
        readText(dataFile)?.let { text ->
            JsonCodec.decodeBackup(text)?.let { b ->
                _records.value = b.records.sortedByDescending { it.date }
                _price.value = b.price
                _car.value = b.car ?: CarSettings()
                _book.value = b.stationPrices
                _stations.value = b.stations
            }
        }
        readText(monitorFile)?.let(JsonCodec::parseObject)?.let { o ->
            _monitor.value = MonitorState(
                lastSeen = (o["lastSeen"] as? JsonObject)?.let { runCatching { JsonCodec.decodeSnapshot(it) }.getOrNull() },
                active = (o["active"] as? JsonObject)?.let { runCatching { JsonCodec.decodeActive(it) }.getOrNull() },
                homeCandidate = (o["homeCandidate"] as? JsonObject)?.let { h ->
                    val lat = h["lat"]?.toString()?.toDoubleOrNull()
                    val lon = h["lon"]?.toString()?.toDoubleOrNull()
                    if (lat != null && lon != null) GeoPoint(lat, lon) else null
                },
            )
        }
    }

    // ── 기록 ─────────────────────────────────────────────
    suspend fun add(record: ChargingRecord, station: StationRef? = null) {
        if (station != null && _stations.value.none { it.key == station.key }) {
            // 전에 간 충전소(같은 GPS·사업자)에 사용자가 붙인 이름이 있으면 이어받는다
            val known = _stations.value.firstOrNull { it.nameSource == StationNameSource.USER && it.sameAs(station) }
            val st = if (known != null && station.nameSource != StationNameSource.NAVI) {
                station.copy(name = known.name, nameSource = known.nameSource, address = station.address ?: known.address)
            } else station
            _stations.value = _stations.value + st
        }
        edit { list -> listOf(record) + list }
    }

    suspend fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    /** 개발용: 시연 전에 시험 기록을 모두 지운다 (단가 설정·충전소 단가는 둔다) */
    suspend fun clearRecords() = edit { emptyList() }

    /**
     * 차량 화면의 1탭 확인. 사업자·장소를 고쳤으면 추정 금액을 다시 계산한다.
     * 첫 아파트 충전을 '집 맞아요'로 확인하면 그 위치를 집으로 저장한다.
     *
     * 처음 간 충전소에서 "지난번 ○○점처럼 420원으로 할까요?"(또는 공시가)에 '맞아요'를 누르면
     * 그 단가를 이 충전소 확인 단가로 저장한다 — 다음부터는 묻지 않고 자동으로 계산한다.
     */
    suspend fun confirm(id: String, provider: String? = null, chargeType: ChargeType? = null, place: PlaceKind? = null) {
        if (place == PlaceKind.HOME) {
            val candidate = _monitor.value.homeCandidate
            if (_car.value.home == null && candidate != null) {
                saveCar(_car.value.copy(home = HomeZone(candidate)))
                saveMonitor(_monitor.value.copy(homeCandidate = null))
            }
        }
        val before = _records.value.firstOrNull { it.id == id } ?: return
        val meta = before.car
        val acceptsStationPrice = provider == null && chargeType == null && meta?.askStationPrice == true &&
            before.isEstimated && before.unitPrice > 0 && station(meta?.stationKey) != null
        if (acceptsStationPrice) {
            val tier = meta?.tier
            learnStationPrice(id, before.unitPrice, tiers = if (meta?.tierExact == true && tier != null) listOf(tier) else null)
            return
        }
        edit { list ->
            list.map { r ->
                if (r.id != id) return@map r
                val withPlace = r.copy(car = r.car?.let { c -> c.copy(place = place ?: c.place) })
                val repriced = RecordFactory.reprice(
                    withPlace, pricing(), _car.value.roundingRule,
                    station = station(r.car?.stationKey),
                    provider = provider ?: r.provider,
                    chargeType = chargeType ?: r.chargeType,
                )
                repriced.copy(car = repriced.car?.copy(confirmed = true))
            }
        }
    }

    /**
     * 처음 간 충전소의 단가를 1회 입력 (영수증·충전기 화면의 kWh당 단가, 또는 말로).
     * 그 충전소의 출력 구간 칸에 확인 단가로 저장하고, 같은 충전소의 '추정' 기록을 모두 다시 계산한다.
     *
     * @param tiers 넣을 구간 칸 (말로 입력에서 이미 정해 읽어 준 것). null이면 차량 정보·공시 요금표로 자동 판정
     * @param base 단가가 직접 가리킨 구간 — 차가 하한값만 알던 기록의 구간을 바로잡는 데 쓴다
     * @param exact 사용자가 충전기 출력을 직접 말했으면 true
     */
    suspend fun learnStationPrice(
        recordId: String,
        unitPrice: Double,
        tiers: List<PowerTier>? = null,
        base: List<PowerTier>? = null,
        exact: Boolean = false,
    ) {
        val record = _records.value.firstOrNull { it.id == recordId } ?: return
        val st = station(record.car?.stationKey) ?: return
        val refined = if (tiers == null) {
            val (book, r) = RecordFactory.learnStationPriceAuto(_book.value, record, st, unitPrice, LocalDate.now(), pricing())
            _book.value = book
            r
        } else {
            _book.value = RecordFactory.learnStationPrice(_book.value, record, st, unitPrice, LocalDate.now(), tiers)
            RecordFactory.refineTier(record, base ?: tiers, exact)
        }
        // 이 기록 + 같은 충전소에서 아직 확인하지 않은 기록만 새 단가로. 확인한 지난 기록은 그때 단가 그대로.
        repriceWhere(replace = refined.takeIf { it !== record }, confirmId = recordId) { r ->
            r.id == recordId || (RepricePolicy.pending(r) && atStation(r, st))
        }
    }

    /**
     * (선택) 무료 충전소 — 요금을 받지 않는 곳으로 표시하면 이 충전소는 앞으로 0원으로 기록하고 단가를 묻지 않는다.
     * 이 기록과 같은 충전소의 아직 확인하지 않은 기록도 0원으로 바꾼다.
     */
    suspend fun markStationFree(recordId: String) {
        val record = _records.value.firstOrNull { it.id == recordId } ?: return
        val st = station(record.car?.stationKey) ?: return
        _book.value = _book.value.markFree(st, LocalDate.now())
        repriceWhere(confirmId = recordId) { r -> r.id == recordId || (RepricePolicy.pending(r) && atStation(r, st)) }
    }

    /** 충전소 이름 화면의 '무료 충전소' 켜기·끄기. 끄면 다음 충전부터 다시 단가를 묻는다. 확인한 지난 기록은 그대로. */
    suspend fun setStationFree(station: StationRef, free: Boolean) {
        if (_book.value.isFree(station) == free) return
        _book.value = if (free) _book.value.markFree(station, LocalDate.now()) else _book.value.remove(station)
        repriceWhere { r -> RepricePolicy.pending(r) && atStation(r, station) }
    }

    /** 확인 단가 삭제 (단가가 바뀌었거나 잘못 입력했을 때). 지난 기록 금액은 그대로 — 다음 충전부터 다시 묻는다. */
    suspend fun forgetStation(station: StationRef) {
        _book.value = _book.value.remove(station)
        repriceWhere { r -> RepricePolicy.pending(r) && atStation(r, station) }
    }

    /**
     * 영수증 금액으로 확정 — 절사·반올림된 값 그대로.
     * 영수증에 단가가 적혀 있으면(또는 금액·충전량으로 단가를 알 수 있으면) 그 충전소 확인 단가로도 저장한다.
     */
    /**
     * @param tier 사용자가 고른 충전기 출력 구간. null이면 차량 정보·공시 요금표로 구간이 하나로 정해질 때만
     *   충전소 단가로 저장한다(갈리면 이번 기록만 확정 — 다음 방문 때 다시 묻는다).
     */
    suspend fun applyReceipt(id: String, receipt: Receipt, tier: PowerTier? = null) {
        val record = _records.value.firstOrNull { it.id == id } ?: return
        val learned = receiptUnitPrice(receipt)
        val st = station(record.car?.stationKey)
        // 단가로 어느 출력 구간이었는지도 알 수 있다 (예: 채비 410원 → 50~100kW)
        var refined = record
        if (learned != null && st != null) {
            if (tier != null) {
                _book.value = RecordFactory.learnStationPrice(_book.value, record, st, learned, LocalDate.now(), listOf(tier))
                refined = RecordFactory.refineTier(record, listOf(tier), exact = true)
            } else {
                val (book, r) = RecordFactory.learnStationPriceAuto(_book.value, record, st, learned, LocalDate.now(), pricing())
                _book.value = book
                refined = r
            }
        }
        edit { list ->
            list.map { r0 ->
                val r = if (r0.id == refined.id && refined !== record) refined else r0
                if (r.id != id) r
                else {
                    // 영수증에 충전량이 없을 때 역산에 쓸 단가: 확인된 단가(충전소·내 설정)만 쓴다. 공시 안내가로는 역산하지 않는다.
                    val trusted = r.car?.priceSource?.let { it == PriceSource.STATION_CONFIRMED || it == PriceSource.MY_SETTING } ?: true
                    AmountPolicy.applyReceipt(r, receipt, r.unitPrice.takeIf { it > 0 && trusted })
                        .let { it.copy(car = it.car?.copy(confirmed = true, askStationPrice = false)) }
                }
            }
        }
        if (learned != null && st != null) repriceWhere { r -> RepricePolicy.pending(r) && atStation(r, st) }
    }

    /** 영수증의 kWh당 단가 (적혀 있으면 그대로, 없으면 금액 ÷ 충전량을 0.1원 단위로) */
    fun receiptUnitPrice(receipt: Receipt): Double? = receipt.unitPrice?.takeIf { it > 0 }
        ?: receipt.kWh?.takeIf { it > 0 }?.let { kwh ->
            // 금액은 절사·반올림돼 있어 나눈 값에 소수 오차가 생긴다 → 0.1원 단위로 맞춘다
            BigDecimal.valueOf(receipt.amount / kwh).setScale(1, RoundingMode.HALF_UP).toDouble()
        }

    /** 관리비 고지서로 그달 아파트 충전 정산 */
    suspend fun settleBill(month: YearMonth, amount: Long, kWh: Double?): BillSettlement.Result {
        var result: BillSettlement.Result? = null
        edit { list ->
            val res = BillSettlement.settle(list, month, amount, kWh, _car.value.homeProvider)
            result = res
            val byId = res.updated.associateBy { it.id }
            list.map { byId[it.id] ?: it }
        }
        return result!!
    }

    /**
     * 개발·시연용 예시 기록 — 실제 차량 신호 없이(일반 AVD) 화면 흐름을 확인할 때. 디버그 빌드에서만 버튼이 보인다.
     *  1) 어젯밤 아파트 완속(앱 꺼진 사이 충전) 2) 이틀 전 채비 휴게소(출력 하한값만 앎) 3) 방금 SK일렉링크 100kW(처음 간 충전소)
     */
    suspend fun addDemoData(now: LocalDateTime = LocalDateTime.now()) {
        val homeLoc = GeoPoint(37.5894, 127.0167)
        if (_car.value.home == null || _car.value.batteryCapacityKwh == null) {
            saveCar(_car.value.copy(home = _car.value.home ?: HomeZone(homeLoc), batteryCapacityKwh = _car.value.batteryCapacityKwh ?: 77.4))
        }
        val car = _car.value
        val home = car.home?.center ?: homeLoc
        fun session(start: LocalDateTime, minutes: Long, soc0: Double, soc1: Double, odo: Double, peak: Double, loc: GeoPoint, source: RecordSource) =
            ChargeSession(
                source = source, start = start, end = start.plusMinutes(minutes), socStart = soc0, socEnd = soc1,
                odometerStart = odo, odometerEnd = odo, peakKw = peak, location = loc, batteryCapacityKwh = car.batteryCapacityKwh,
            )
        val day = now.toLocalDate()
        val drafts = listOf(
            RecordFactory.build(
                session(day.minusDays(1).atTime(22, 10), 9 * 60, 35.0, 85.0, 12_150.0, 7.0, home, RecordSource.CAR_INFERRED),
                pricing(), car, _records.value,
            ),
            RecordFactory.build(
                session(now.minusDays(2).withHour(14), 30, 30.0, 65.0, 12_000.0, 85.0, GeoPoint(37.0, 127.2), RecordSource.CAR_LIVE),
                pricing(), car, _records.value, NaviChargingStation("채비 안성휴게소", maxChargingPowerKw = 0),
            ),
            RecordFactory.build(
                session(now.minusMinutes(40), 30, 25.0, 70.0, 12_300.0, 95.0, GeoPoint(37.4012, 127.1086), RecordSource.CAR_LIVE),
                pricing(), car, _records.value, NaviChargingStation("SK일렉링크 판교점", maxChargingPowerKw = 100),
            ),
        )
        drafts.forEach { add(it.record, it.station) }
    }

    /**
     * 시연용: 지난 두세 달 기록을 새로 만든다. 기존 충전 기록과 충전소·충전소 확인 단가는 지우고(시연 흐름이 늘 같게),
     * 사업자·아파트 단가 설정은 둔다.
     * 아파트 완속(앱 꺼진 동안) · 회사 근처 급속 · 휴게소 초급속 · 집 근처 공용 급속이 실제 주행처럼 섞이고,
     * 공용 충전은 영수증으로, 지난달까지의 아파트 충전은 관리비 고지서로 확정한다.
     * 가장 최근 아파트 충전과 공용 충전 한 건씩은 '확인 대기'로 남겨 홈 화면 흐름을 보여 준다.
     */
    suspend fun addDemoMonths(today: LocalDate = LocalDate.now()) {
        _book.value = StationPriceBook()
        _stations.value = emptyList()
        clearRecords()
        if (_car.value.home == null || _car.value.batteryCapacityKwh == null) {
            saveCar(_car.value.copy(home = _car.value.home ?: HomeZone(DemoData.HOME), batteryCapacityKwh = _car.value.batteryCapacityKwh ?: 77.4))
        }
        if (_car.value.homeProvider == ProviderCatalog.APARTMENT && !_price.value.hasApartmentAcRate) {
            savePrice(_price.value.copy(apartmentAcUnitPrice = 250.0))
        }
        val home = _car.value.home?.center ?: DemoData.HOME
        for (stop in DemoData.timeline(today, _car.value.batteryCapacityKwh ?: 77.4)) {
            // 집 위치를 바꿔 둔 사용자도 아파트 충전이 집으로 잡히게 집 좌표에서 충전한 것으로 만든다
            val session = if (stop.navi == null) stop.session.copy(location = home) else stop.session
            val d = RecordFactory.build(session, pricing(), _car.value, _records.value, stop.navi)
            add(d.record, d.station)
            val price = stop.receiptUnitPrice ?: continue
            val r = _records.value.firstOrNull { it.id == d.record.id } ?: continue
            // 영수증 금액은 사업자 규칙대로 1원 미만 절사된 값
            applyReceipt(r.id, Receipt(amount = kotlin.math.floor(r.kWh * price), kWh = r.kWh, unitPrice = price))
        }
        val thisMonth = YearMonth.from(today)
        val homeProvider = _car.value.homeProvider
        if (homeProvider == ProviderCatalog.APARTMENT) {
            val past = _records.value.filter { it.provider == homeProvider && YearMonth.from(it.date) < thisMonth }
            past.groupBy { YearMonth.from(it.date) }.forEach { (m, rs) ->
                settleBill(m, DemoData.billFor(rs.sumOf { it.paymentAmount }), null)
            }
        }
        val keep = listOfNotNull(
            _records.value.filter { it.car?.place == PlaceKind.HOME }.maxByOrNull { it.date }?.id,
            _records.value.filter { it.car?.place == PlaceKind.PUBLIC }.maxByOrNull { it.date }?.id,
        ).toSet()
        edit { list -> list.map { r -> val c = r.car; if (r.id in keep || c == null) r else r.copy(car = c.copy(confirmed = true)) } }
    }

    /**
     * 시연용: 같은 사업자(SK일렉링크)의 **다른** 충전소 충전 1건. 판교점 단가를 먼저 확인해 두면
     * "지난번 판교점처럼 ○○원으로 할까요?"로 묻는 흐름을 볼 수 있다.
     */
    suspend fun addDemoOtherStation(now: LocalDateTime = LocalDateTime.now()) {
        val car = _car.value
        val odo = (_records.value.mapNotNull { it.odometer }.maxOrNull() ?: 12_300.0) + 120.0
        val session = ChargeSession(
            source = RecordSource.CAR_LIVE, start = now.minusMinutes(25), end = now.minusMinutes(3),
            socStart = 40.0, socEnd = 75.0, odometerStart = odo, odometerEnd = odo, peakKw = 98.0,
            location = GeoPoint(37.5663, 126.9779), batteryCapacityKwh = car.batteryCapacityKwh ?: 77.4,
        )
        val d = RecordFactory.build(session, pricing(), car, _records.value, NaviChargingStation("SK일렉링크 시청점", maxChargingPowerKw = 100))
        add(d.record, d.station)
    }

    /** 시연: 회사 지하주차장 완속 충전 (출근해서 퇴근 전까지) — 홈 카드의 '이름 붙이기' 확인용 */
    suspend fun addDemoOfficeCharge(now: LocalDateTime = LocalDateTime.now()) {
        val car = _car.value
        val odo = (_records.value.mapNotNull { it.odometer }.maxOrNull() ?: 12_300.0) + 25.0
        val session = ChargeSession(
            source = RecordSource.CAR_LIVE, start = now.minusHours(3).minusMinutes(20), end = now.minusMinutes(5),
            socStart = 38.0, socEnd = 66.0, odometerStart = odo, odometerEnd = odo, peakKw = 7.0,
            location = GeoPoint(37.3948, 127.1112), batteryCapacityKwh = car.batteryCapacityKwh ?: 77.4,
        )
        val d = RecordFactory.build(session, pricing(), car, _records.value, NaviChargingStation("회사 지하주차장 충전기", maxChargingPowerKw = 7))
        add(d.record, d.station)
    }

    // ── 설정 ─────────────────────────────────────────────
    /**
     * 단가 설정 저장. **지난 기록은 그때 단가 그대로** 두고 다음 충전부터 새 단가를 쓴다
     * (어제까지 190원·오늘부터 250원이면 어제 기록은 190원이어야 가계부를 믿을 수 있다).
     * 단가가 없어 비어 있던 기록만 채운다.
     *
     * @param fixPast 잘못 입력한 값을 고치는 경우 — 설정 단가로 계산한 추정 기록도 새 값으로 고친다
     */
    suspend fun savePrice(p: PriceSettings, fixPast: Boolean = false) {
        _price.value = p
        repriceWhere { r -> RepricePolicy.unpriced(r) || (fixPast && RepricePolicy.fromSetting(r)) }
    }

    /**
     * 이 설정으로 바꾸면 금액이 달라질 지난 기록 수 (설정 단가로 계산한 추정 기록 중).
     * 0보다 크면 "지난 기록은 그대로 둘까요, 잘못 입력해서 고칠까요?"를 묻는다.
     */
    fun affectedBySetting(p: PriceSettings): Int {
        val ctx = PricingContext(p, _book.value, tariff, _car.value.useMemberTariff)
        return _records.value.count { r ->
            RepricePolicy.fromSetting(r) &&
                RecordFactory.reprice(r, ctx, _car.value.roundingRule, station = station(r.car?.stationKey)).unitPrice != r.unitPrice
        }
    }

    /** 내연기관차 비교 기준 (연비 km/L, 연료 가격 원/L) — 기록 금액과 무관하므로 다시 계산하지 않는다 */
    suspend fun saveComparison(gasEfficiency: Double?, gasPrice: Double?) {
        _price.value = _price.value.copy(gasEfficiency = gasEfficiency, gasPrice = gasPrice)
        mutex.withLock { writeData() }
    }

    suspend fun saveCar(c: CarSettings) {
        val tariffModeChanged = c.useMemberTariff != _car.value.useMemberTariff
        _car.value = c
        // 회원/비회원 기준은 공시가로 짐작한 미확인 기록에만 영향 — 확인한 기록은 그대로
        if (tariffModeChanged) repriceWhere { RepricePolicy.pendingGuess(it) } else mutex.withLock { writeData() }
    }

    fun saveMonitor(state: MonitorState) {
        _monitor.value = state
        val o = buildJsonObject {
            state.lastSeen?.let { put("lastSeen", JsonCodec.encodeSnapshot(it)) }
            state.active?.let { put("active", JsonCodec.encodeActive(it)) }
            state.homeCandidate?.let { h -> put("homeCandidate", buildJsonObject { put("lat", h.lat); put("lon", h.lon) }) }
        }
        writeAtomic(monitorFile, JsonCodec.stringify(o))
    }

    /** 백업 파일 내보내기 */
    fun exportBackup(target: File): File {
        writeAtomic(target, backupText())
        return target
    }

    // ── 내부 ─────────────────────────────────────────────
    /**
     * [which]에 해당하는 기록만 지금 단가로 다시 계산한다 ([RepricePolicy]). 나머지는 그때 단가 그대로.
     * @param replace 다시 계산하기 전에 바꿔 넣을 기록 (구간을 바로잡은 기록)
     * @param confirmId 사용자가 단가를 직접 알려 준 기록 — 확인한 것으로 표시
     */
    private suspend fun repriceWhere(
        replace: ChargingRecord? = null,
        confirmId: String? = null,
        which: (ChargingRecord) -> Boolean,
    ) = edit { list ->
        val ctx = pricing()
        list.map { r -> if (replace != null && r.id == replace.id) replace else r }
            .map { r -> if (which(r)) RecordFactory.reprice(r, ctx, _car.value.roundingRule, station = station(r.car?.stationKey)) else r }
            .map { r -> if (confirmId != null && r.id == confirmId) r.copy(car = r.car?.copy(confirmed = true)) else r }
    }

    /** 기록이 이 충전소(같은 GPS·이름·사업자)에서 충전한 것인가 */
    private fun atStation(r: ChargingRecord, st: StationRef): Boolean =
        station(r.car?.stationKey)?.let { it.key == st.key || it.sameAs(st) } == true

    private suspend fun edit(transform: (List<ChargingRecord>) -> List<ChargingRecord>) = mutex.withLock {
        _records.value = transform(_records.value).sortedByDescending { it.date }
        writeData()
    }

    private fun backupText(): String = JsonCodec.encodeBackup(
        _records.value, _price.value, _car.value, LocalDateTime.now(), _book.value, _stations.value,
    )

    private fun writeData() = writeAtomic(dataFile, backupText())

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e("Repository", "저장 실패: ${file.name}", it) }
    }

    private fun readText(file: File): String? =
        if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
}
