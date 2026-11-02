package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.StationNameSource
import com.akaloopang.evauto.core.voice.VoicePriceParser
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.session.LiveMeter
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.stats.MonthSummary
import com.akaloopang.evauto.core.stats.SavingsCalculator
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 홈: 한눈에 ① 충전 중 미터 ② 이번 달 요약(충전비·충전량·km당) ③ 확인이 필요한 충전 ④ 최근 충전.
 * 요약을 위로 올려 첫 화면에서 가계부 숫자와 할 일이 같이 보이게 하고, 아래는 최근 기록으로 채운다.
 */
@Composable
fun HomeScreen(app: EvAutoApp, onOpenSettings: () -> Unit, onOpenRecords: () -> Unit = {}) {
    val live by app.monitor.live.collectAsStateWithLifecycle()
    val liveSource by app.monitor.livePriceSource.collectAsStateWithLifecycle()
    val records by app.repo.records.collectAsStateWithLifecycle()
    val stations by app.repo.stations.collectAsStateWithLifecycle()
    val price by app.repo.price.collectAsStateWithLifecycle()
    val car by app.repo.car.collectAsStateWithLifecycle()
    val monitor by app.repo.monitor.collectAsStateWithLifecycle()
    val expected by app.assistant.expected.collectAsStateWithLifecycle()
    val trip by app.assistant.trip.collectAsStateWithLifecycle()
    val naviPlan by app.assistant.plan.collectAsStateWithLifecycle()

    var receiptFor by remember { mutableStateOf<ChargingRecord?>(null) }
    var stationPriceFor by remember { mutableStateOf<ChargingRecord?>(null) }
    var voiceFor by remember { mutableStateOf<ChargingRecord?>(null) }
    var detailFor by remember { mutableStateOf<ChargingRecord?>(null) }
    var showBill by remember { mutableStateOf(false) }

    val pending = records.filter { it.car != null && it.car?.confirmed == false }.take(5)
    val pendingIds = pending.map { it.id }.toSet()
    val recent = records.filter { it.id !in pendingIds }.sortedByDescending { it.date }.take(4)
    val month = YearMonth.now()
    val summary = MonthSummary.of(records, month)
    val savings = SavingsCalculator.compute(
        records.filter { YearMonth.from(it.date) == month },
        price.effectiveGasEfficiency, price.effectiveGasPrice,
    )
    val needAptPrice = car.homeCharging && !price.hasApartmentAcRate && car.homeProvider == ProviderCatalog.APARTMENT
    val showBillButton = car.homeProvider == ProviderCatalog.APARTMENT || summary.apartmentCount > 0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            PageHeader(
                "${month.monthValue}월 충전",
                subtitle = when {
                    records.isEmpty() -> "충전기를 꽂고 뽑기만 하면 자동으로 기록돼요"
                    summary.count == 0 -> "이번 달 충전이 아직 없어요"
                    else -> "${summary.count}건 · 아파트 ${summary.apartmentCount}건 · 금액 확정 ${(summary.settledRatio * 100).toInt()}%"
                },
                trailing = if (showBillButton) {
                    { SecondaryButton("관리비 고지서 정산", onClick = { showBill = true }, icon = EvIcons.Receipt) }
                } else null,
            )
        }

        live?.let { r -> item { LiveMeterPanel(r, liveSource.label) } }

        // 내비가 충전소로 안내 중이면 예상 충전비, 주행 중이면 이번 주행 비용 (Pleos NaviHelper·Vehicle)
        if (expected != null || trip != null || naviPlan != null) item { DriveCards(app) }

        if (records.isEmpty()) {
            item {
                EmptyState(
                    "아직 충전 기록이 없어요",
                    "충전이 끝나면 여기에 금액과 함께 나타나요. 앱이 꺼져 있던 밤사이 아파트 충전도 다음에 시동을 걸 때 찾아서 기록해요.",
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatTile(
                        "이번 달 충전비", Format.wonNumber(summary.totalAmount), Modifier.weight(1f), unit = "원",
                        sub = "영수증 ${summary.receiptCount}건 · 관리비 정산 ${summary.billSettledCount}건",
                    )
                    StatTile(
                        "충전량", Format.kwhNumber(summary.totalKwh), Modifier.weight(1f), unit = "kWh",
                        sub = when {
                            summary.apartmentCount == 0 -> "${summary.count}건"
                            summary.apartmentAmount > 0 -> "아파트 ${summary.apartmentCount}건 · ${Format.won(summary.apartmentAmount)}"
                            else -> "아파트 ${summary.apartmentCount}건 · 단가 미입력"
                        },
                    )
                    val perKm = savings.evCostPerKm
                    StatTile(
                        "km당 충전비", perKm?.toInt()?.toString() ?: "-", Modifier.weight(1f), unit = if (perKm != null) "원" else null,
                        sub = when {
                            perKm == null -> "주행 기록이 쌓이면 계산돼요"
                            savings.savings > 0 -> "내연기관차보다 ${Format.won(savings.savings)} 절약"
                            else -> "이번 달 주행 기준"
                        },
                    )
                }
            }
        }

        if (needAptPrice) {
            item {
                InfoBanner(
                    "아파트 충전 단가를 넣으면 밤사이 충전 금액이 자동으로 계산돼요.",
                    actionText = "단가 넣기", onAction = onOpenSettings, icon = EvIcons.Apartment,
                )
            }
        }

        if (pending.isNotEmpty()) {
            item { SectionTitle("확인이 필요한 충전", pending.size) }
            items(pending, key = { it.id }) { r ->
                PendingCard(
                    app = app,
                    record = r,
                    askHome = r.car?.place == PlaceKind.HOME && car.home == null && monitor.homeCandidate != null,
                    providers = providerChoices(app),
                    onReceipt = { receiptFor = r },
                    onStationPrice = { stationPriceFor = r },
                    onVoice = { voiceFor = r },
                )
            }
        }

        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("최근 충전") }
                    TextAction("전체 기록", onClick = onOpenRecords, icon = EvIcons.History)
                }
            }
            item {
                Column {
                    SectionDivider()
                    recent.forEach { r ->
                        RecordRow(r, stations.firstOrNull { it.key == r.car?.stationKey }) { detailFor = r }
                    }
                }
            }
        }
    }

    receiptFor?.let { r -> ReceiptDialog(app, r) { receiptFor = null } }
    stationPriceFor?.let { r -> StationPriceDialog(app, r, onVoice = { stationPriceFor = null; voiceFor = r }) { stationPriceFor = null } }
    // 카드의 '말로 고치기/입력' — 카드에 이미 짐작한 단가와 [맞아요]가 있으니 바로 금액을 묻는다
    voiceFor?.let { r -> VoicePriceDialog(app, r.id, suggest = false) { voiceFor = null } }
    detailFor?.let { r -> RecordDialog(app, r) { detailFor = null } }
    if (showBill) BillDialog(app) { showBill = false }
}

/** 충전 중 미터: 큰 SOC + 진행 막대 + 지금까지 금액·충전량·목표까지 */
@Composable
fun LiveMeterPanel(r: LiveMeter.Reading, priceLabel: String? = null) {
    val c = Pleos.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft).padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(380.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(EvIcons.Bolt, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                    Text("충전 중", fontSize = TextSize.title, fontWeight = FontWeight.Bold, color = c.accent)
                }
                Text("${r.socNow?.toInt() ?: "-"}%", fontSize = TextSize.hero, fontWeight = FontWeight.Bold, color = c.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MeterLine("지금까지", r.amountSoFar?.let(Format::won) ?: "단가 미설정", big = true)
                MeterLine("충전량", Format.kwh(r.kWhSoFar) + (r.rateKw?.let { " · ${it.toInt()}kW" } ?: ""))
                if (r.reachedTarget) {
                    MeterLine("목표", "${r.targetSoc}% 도달")
                } else {
                    val time = r.minutesToTarget?.let { "약 ${it}분" } ?: "-"
                    val more = r.amountToTarget?.let { " · 약 ${Format.won(it)} 더" } ?: ""
                    MeterLine("${r.targetSoc}%까지", time + more)
                }
                if (priceLabel != null) Text("단가: $priceLabel", fontSize = TextSize.caption, color = c.sub)
            }
        }
        ProgressBar(((r.socNow ?: 0.0) / 100.0).toFloat())
    }
}

@Composable
private fun MeterLine(label: String, value: String, big: Boolean = false) {
    val c = Pleos.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = TextSize.label, color = c.sub, modifier = Modifier.width(220.dp))
        Text(value, fontSize = if (big) TextSize.headline else TextSize.title, fontWeight = FontWeight.Bold, color = c.text)
    }
}

/** 확인이 필요한 충전 한 건: 왼쪽에 내용, 오른쪽에 버튼 세로로 */
@Composable
private fun PendingCard(
    app: EvAutoApp,
    record: ChargingRecord,
    askHome: Boolean,
    providers: List<String>,
    onReceipt: () -> Unit,
    onStationPrice: () -> Unit,
    onVoice: () -> Unit,
) {
    val c = Pleos.colors
    val scope = rememberCoroutineScope()
    var pickProvider by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val meta = record.car
    val stations by app.repo.stations.collectAsStateWithLifecycle()
    val station = stations.firstOrNull { it.key == meta?.stationKey }
    if (rename && station != null) RenameStationDialog(app, station) { rename = false }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.tile).padding(36.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(Format.title(record), fontSize = TextSize.section, fontWeight = FontWeight.Bold, color = c.text)
                StatusChip(record.amountStatus, record.isFree)
            }
            station?.let { st ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(EvIcons.Place, contentDescription = null, tint = c.accent, modifier = Modifier.size(34.dp))
                    Text(st.displayName, fontSize = TextSize.body, color = c.accent, fontWeight = FontWeight.SemiBold)
                    // 자주 가는 곳(회사·부모님 댁 등)은 이름을 붙여 두면 다음부터 그 이름으로 보인다
                    if (st.nameSource != StationNameSource.USER) TextAction("이름 붙이기", onClick = { rename = true }, icon = EvIcons.Edit)
                }
            }
            Text(
                "${Format.date(record.date)} · SOC ${meta?.socStart ?: "-"}% → ${meta?.socEnd ?: "-"}% · ${Format.kwh(record.kWh)}",
                fontSize = TextSize.body, color = c.sub,
            )
            if (record.isFree) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("무료 충전소", fontSize = TextSize.title, color = c.sub, modifier = Modifier.padding(bottom = 6.dp))
                    Text("0원", fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text)
                }
            } else if (record.paymentAmount > 0) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("추정", fontSize = TextSize.title, color = c.sub, modifier = Modifier.padding(bottom = 6.dp))
                    Text(Format.won(record.paymentAmount), fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text)
                }
            } else {
                Text("단가를 넣으면 금액이 계산돼요", fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            if (record.unitPrice > 0) {
                Text(
                    RecordFactory.priceLabel(record) + " · ${"%.1f".format(record.unitPrice)}원/kWh",
                    fontSize = TextSize.label, color = c.sub,
                )
            }
            if (meta?.askStationPrice == true) Note(stationPriceQuestion(record))
            if (meta?.priceMaybeOutdated == true) Note("이 사업자의 공시 요금이 바뀌었어요. 다음 영수증으로 단가를 다시 확인해 주세요.", warn = true)
            if (meta?.lowConfidence == true) Note("앱이 꺼진 사이 주행이 있어 충전량이 실제보다 적게 잡혔을 수 있어요.", warn = true)
            if (askHome) {
                Text("집(아파트) 충전기가 맞나요? 한 번만 확인하면 다음부터 자동이에요.", fontSize = TextSize.body, fontWeight = FontWeight.SemiBold, color = c.text)
            }
        }

        // 처음 간 충전소인데 짐작한 단가가 있으면 '맞아요'가 곧 "이 단가가 맞다" → 이 충전소 단가로 저장된다
        val guessed = meta?.askStationPrice == true && record.unitPrice > 0
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrimaryButton(
                when {
                    askHome -> "네, 집 충전기"
                    guessed -> "맞아요 · ${VoicePriceParser.formatPrice(record.unitPrice)}원"
                    else -> "맞아요"
                },
                onClick = { scope.launch { app.repo.confirm(record.id, place = if (record.car?.place == PlaceKind.HOME) PlaceKind.HOME else null) } },
                modifier = Modifier.fillMaxWidth(),
                icon = EvIcons.Check,
            )
            Box {
                SecondaryButton("사업자 변경", onClick = { pickProvider = true }, modifier = Modifier.fillMaxWidth(), icon = EvIcons.Swap)
                DropdownMenu(expanded = pickProvider, onDismissRequest = { pickProvider = false }) {
                    providers.forEach { p ->
                        DropdownMenuItem(text = { Text(p) }, onClick = {
                            pickProvider = false
                            scope.launch {
                                app.repo.confirm(
                                    record.id, provider = p,
                                    place = if (p == app.repo.car.value.homeProvider) PlaceKind.HOME else PlaceKind.PUBLIC,
                                )
                            }
                        })
                    }
                }
            }
            if (meta?.askStationPrice == true) {
                SecondaryButton(if (guessed) "말로 고치기" else "말로 단가 입력", onClick = onVoice, modifier = Modifier.fillMaxWidth(), icon = EvIcons.Mic)
                SecondaryButton(if (guessed) "숫자로 고치기" else "숫자로 입력", onClick = onStationPrice, modifier = Modifier.fillMaxWidth(), icon = EvIcons.Edit)
            } else {
                SecondaryButton("영수증 금액", onClick = onReceipt, modifier = Modifier.fillMaxWidth(), icon = EvIcons.Receipt)
            }
        }
    }
}

/** 처음 간 충전소: 어떤 단가로 계산했는지 + 무엇을 하면 되는지 */
private fun stationPriceQuestion(record: ChargingRecord): String {
    val meta = record.car
    val p = VoicePriceParser.formatPrice(record.unitPrice)
    val tail = "충전기 화면·영수증 단가와 같으면 '맞아요', 다르면 고쳐 주세요. 한 번만 확인하면 다음부터 자동이에요."
    return when {
        record.unitPrice <= 0 -> "처음 온 충전소예요. 단가를 한 번만 알려 주면 다음부터 자동으로 계산돼요."
        meta?.priceSource == PriceSource.OTHER_STATION -> {
            val whenText = meta?.priceRefDate?.let { " (${it.monthValue}/${it.dayOfMonth} 확인)" } ?: ""
            "처음 온 충전소예요. 지난번 ${meta?.priceRef ?: "같은 사업자 충전소"}$whenText 단가 ${p}원으로 계산했어요. $tail"
        }
        meta?.priceSource == PriceSource.OFFICIAL_TARIFF -> "처음 온 충전소예요. ${record.provider} 공시 요금 ${p}원으로 계산했어요. $tail"
        else -> "처음 온 충전소예요. 내 설정 단가 ${p}원으로 계산했어요. $tail"
    }
}

@Composable
private fun Note(text: String, warn: Boolean = false) {
    val c = Pleos.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(EvIcons.Info, contentDescription = null, tint = if (warn) c.danger else c.accent, modifier = Modifier.padding(top = 2.dp).size(30.dp))
        Text(text, fontSize = TextSize.label, color = if (warn) c.danger else c.text)
    }
}

/** 사업자 선택지: 앱 사업자 목록 + 공시 요금표에만 있는 사업자 */
fun providerChoices(app: EvAutoApp): List<String> {
    val base = app.repo.price.value.providers
    val tariff = app.repo.tariff ?: return base
    val mapped = TariffTable.CATALOG_TO_OFFICIAL.values.toSet()
    val extra = tariff.entries.map { it.operator }
        .filter { op -> op !in mapped && ProviderCatalog.normalize(op, base) == null }
        .sorted()
    return base + extra
}

/** 주행 중 화면 — 요약만, 조작은 말로 단가 입력 하나 */
@Composable
fun DrivingSummary(app: EvAutoApp) {
    val c = Pleos.colors
    val live by app.monitor.live.collectAsStateWithLifecycle()
    val records by app.repo.records.collectAsStateWithLifecycle()
    val summary = MonthSummary.of(records, YearMonth.now())
    var talk by remember { mutableStateOf(false) }
    val waiting = records.firstOrNull { it.car?.askStationPrice == true }

    Column(Modifier.fillMaxSize().padding(PagePadding), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        PageHeader("주행 중", subtitle = "확인·입력은 정차(P단) 후에 할 수 있어요")
        live?.let { LiveMeterPanel(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatTile("이번 달 충전비", Format.wonNumber(summary.totalAmount), Modifier.weight(1f), unit = "원")
            StatTile("충전", "${summary.count}", Modifier.weight(1f), unit = "건", sub = if (summary.pendingConfirmCount > 0) "확인할 충전 ${summary.pendingConfirmCount}건" else null)
        }
        // 단가를 기다리는 충전이 있으면 주행 중에도 말로 입력할 수 있다 (큰 버튼 하나)
        if (waiting != null) {
            Spacer(Modifier.padding(4.dp))
            PrimaryButton("말로 단가 입력", onClick = { talk = true }, modifier = Modifier.fillMaxWidth(), icon = EvIcons.Mic)
            Text("방금 충전한 충전소의 단가를 말씀해 주시면 다음부터 자동으로 계산돼요.", fontSize = TextSize.caption, color = c.sub)
        }
    }
    if (talk && waiting != null) VoicePriceDialog(app, waiting.id) { talk = false }
}
