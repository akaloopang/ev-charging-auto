package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.stats.ChargingStats
import com.akaloopang.evauto.core.stats.GasCarClass
import com.akaloopang.evauto.core.stats.OdoSegments
import com.akaloopang.evauto.core.stats.PeriodEfficiency
import com.akaloopang.evauto.core.stats.PeriodStat
import com.akaloopang.evauto.core.stats.SavingsCalculator
import com.akaloopang.evauto.core.stats.SavingsIssue
import com.akaloopang.evauto.core.stats.SavingsResult
import com.akaloopang.evauto.core.publicdata.FuelPrice
import com.akaloopang.evauto.core.publicdata.PublicStation
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 통계: 이번 달 · 내연기관차 대비 절약 · 월별 충전비 · 사업자/충전소/방식별 · 실측 충전 속도 · 단가 기록 · 충전 누락 점검.
 * 원래 EV충전 기록 앱의 통계를 옮기되, 주행거리를 차에서 자동으로 받으므로 절약액을 따로 입력 없이 계산한다.
 */
@Composable
fun StatsScreen(app: EvAutoApp) {
    val records by app.repo.records.collectAsStateWithLifecycle()
    val stations by app.repo.stations.collectAsStateWithLifecycle()
    val price by app.repo.price.collectAsStateWithLifecycle()
    val publicCache by app.publicData.cache.collectAsStateWithLifecycle()
    val pubBusy by app.publicData.busy.collectAsStateWithLifecycle()
    val pubMsg by app.publicData.message.collectAsStateWithLifecycle()
    var naviMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val month = YearMonth.from(today)

    if (records.isEmpty()) {
        PageColumn {
            PageHeader("통계")
            EmptyState(
                "아직 통계가 없어요",
                "충전 기록이 쌓이면 달별 충전비, 사업자·충전소별 단가, 내연기관차 대비 절약액을 보여 드려요.",
                EvIcons.Chart,
            )
        }
        return
    }

    val windows = remember(records) { OdoSegments.windows(records) }
    val savings = remember(records, price) {
        SavingsCalculator.compute(records, price.effectiveGasEfficiency, price.effectiveGasPrice, windows)
    }
    val monthEff = remember(windows) { ChargingStats.monthlyEfficiency(windows) }
    val cmp = ChargingStats.compareMonth(records, today)
    val projected = ChargingStats.projectedMonthPayment(records, today)
    val monthPriced = PeriodStat.of(records.filter { YearMonth.from(it.date) == month && ChargingStats.priced(it) })
    val first = records.minOf { it.date }.toLocalDate()

    PageColumn {
        PageHeader("통계", subtitle = "전체 ${records.size}건 · ${first.year}.${first.monthValue}.${first.dayOfMonth}부터")

        // 1. 이번 달
        Section("${month.monthValue}월 · 1일~${today.dayOfMonth}일") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile(
                    "충전비", Format.wonNumber(cmp.thisMonth.payment), Modifier.weight(1f), unit = "원",
                    sub = cmp.diffPct?.let { pct ->
                        val sign = if (pct >= 0) "+" else "−"
                        "전월 같은 기간보다 $sign${abs(pct).roundToInt()}%"
                    } ?: if (cmp.lastMonthFull.isEmpty) "전월 기록 없음" else "전월 같은 기간 0원",
                )
                StatTile(
                    "충전량", Format.kwhNumber(cmp.thisMonth.kWh), Modifier.weight(1f), unit = "kWh",
                    sub = "${cmp.thisMonth.count}회" + (if (monthPriced.kWh > 0) " · 평균 ${monthPriced.unitPrice.roundToInt()}원/kWh" else ""),
                )
                StatTile(
                    "이달 예상", projected?.let(Format::wonNumber) ?: "-", Modifier.weight(1f),
                    unit = if (projected != null) "원" else null,
                    sub = if (projected != null) "지금 페이스라면" else "${ChargingStats.MIN_DAY_FOR_PROJECTION}일부터 계산해요",
                )
            }
        }
        SectionDivider()

        // 2. 내연기관차 대비
        SavingsSection(app, savings, monthEff[month], price)
        SectionDivider()

        // 3. 월별 충전비
        Section("월별 충전비", "최근 6개월 · 막대 위 숫자는 그달 충전비") {
            MonthBars(ChargingStats.lastMonths(records, month))
            val unitByMonth = ChargingStats.monthly(records.filter(ChargingStats::priced)).entries.take(6)
            if (unitByMonth.isNotEmpty()) {
                Text(
                    "평균 단가  " + unitByMonth.reversed().joinToString("  ·  ") { (m, s) -> "${m.monthValue}월 ${s.unitPrice.roundToInt()}원" },
                    fontSize = TextSize.label, color = Pleos.colors.sub,
                )
            }
        }
        SectionDivider()

        // 4. 사업자별
        val byProvider = ChargingStats.byProvider(records)
        val total = byProvider.sumOf { it.stat.payment }.coerceAtLeast(1.0)
        Section("사업자별", "결제액 큰 순 · 평균 단가는 결제 0원 제외") {
            byProvider.take(6).forEach { g ->
                GroupRow(
                    g.label,
                    "${g.stat.count}회 · ${Format.kwh(g.stat.kWh)}" + unitText(g.priced),
                    g.stat.payment, (g.stat.payment / total).toFloat(),
                )
            }
        }

        // 5. 충전소별
        val byStation = ChargingStats.byStation(records) { key -> stations.firstOrNull { it.key == key }?.displayName }
        if (byStation.isNotEmpty()) {
            SectionDivider()
            // 두 번 이상 간 곳 중 평균 단가가 가장 낮은 곳
            val best = byStation.filter { it.stat.count >= 2 && it.priced.kWh > 0 }.minByOrNull { it.priced.unitPrice }
            val maxCount = byStation.maxOf { it.stat.count }.toFloat()
            Section("충전소별", "자주 간 순 · GPS와 이름으로 같은 충전소를 묶어요 · 길안내는 차 내비로 바로 넘겨요") {
                byStation.take(5).forEach { g ->
                    val ref = stations.firstOrNull { it.key == g.key }
                    val pub = if (publicCache != null) app.publicData.stationFor(ref) else null
                    GroupRow(
                        g.label, "${g.stat.count}회 · ${Format.kwh(g.stat.kWh)}" + unitText(g.priced),
                        g.stat.payment, g.stat.count / maxCount,
                        badge = if (g.key == best?.key) "가장 저렴" else null,
                        extra = pub?.let(::publicText),
                        onNavigate = ref?.location?.let { at ->
                            {
                                naviMsg = if (app.navi.routeTo(at.lat, at.lon, ref.displayName, ref.address)) "${ref.displayName}까지 길안내를 시작했어요"
                                else "내비게이션에 연결하지 못했어요 (차량 내비가 있는 환경에서 동작해요)"
                            }
                        },
                    )
                }
                if (byStation.size > 5) Text("외 ${byStation.size - 5}곳", fontSize = TextSize.label, color = Pleos.colors.sub)
                naviMsg?.let { Text(it, fontSize = TextSize.label, color = Pleos.colors.accent) }
                if (app.publicData.chargerEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton(
                            if (pubBusy) "받는 중…" else "빈 충전기 확인", enabled = !pubBusy, icon = EvIcons.Refresh,
                            onClick = { scope.launch { app.publicData.refresh() } },
                        )
                        Text(
                            pubMsg ?: publicCache?.let { "공공데이터 ${it.statusAt.hour}:${"%02d".format(it.statusAt.minute)} 기준" }
                                ?: "한국환경공단 공공데이터로 충전소 이름·빈 충전기를 확인해요",
                            fontSize = TextSize.label, color = Pleos.colors.sub,
                        )
                    }
                }
            }
        }
        SectionDivider()

        // 6. 충전 방식별
        val unitByType = ChargingStats.unitPriceByType(records)
        Section("충전 방식별") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ChargingStats.byChargeType(records).forEach { (type, s) ->
                    StatTile(
                        type.speedName, Format.wonNumber(s.payment), Modifier.weight(1f), unit = "원",
                        sub = "${s.count}회 · ${Format.kwh(s.kWh)}" + (unitByType[type]?.let { " · 평균 ${it.roundToInt()}원" } ?: ""),
                    )
                }
            }
        }

        // 7. 실측 충전 속도
        val speeds = ChargingStats.chargingSpeed(records)
        if (speeds.isNotEmpty()) {
            SectionDivider()
            Section("실측 충전 속도", "차가 잰 충전 시간 기준 · 배터리가 찰수록 느려지는 만큼 충전기 최대 출력보다 낮게 나와요") {
                speeds.take(5).forEach { sp ->
                    LabelValue(
                        "${sp.provider} ${sp.type?.speedName ?: ""}".trim(),
                        "평균 ${sp.avgKw.roundToInt()}kW · 1회 ${sp.avgMinutes.roundToInt()}분 · ${sp.count}회",
                    )
                }
            }
        }

        // 8. 단가 기록
        val cheap = ChargingStats.cheapest(records)
        val dear = ChargingStats.priciest(records)
        if (cheap != null && dear != null && cheap.id != dear.id) {
            SectionDivider()
            Section("단가 기록", "실제로 결제한 기록만") {
                LabelValue("가장 싼 충전", priceRecordText(cheap))
                LabelValue("가장 비싼 충전", priceRecordText(dear))
            }
        }
        SectionDivider()

        // 9. 충전 누락 점검
        val missing = ChargingStats.missingCharge(windows)
        Section("충전 누락 점검", "달린 거리에 비해 충전 기록이 너무 적은 구간을 찾아요") {
            when {
                windows.isEmpty() -> Text("충전이 두 번 이상 기록되면 그 사이 주행거리로 점검해요.", fontSize = TextSize.body, color = Pleos.colors.sub)
                missing.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(EvIcons.Check, contentDescription = null, tint = Pleos.colors.ok, modifier = Modifier.size(40.dp))
                    Column {
                        Text("빠진 충전이 없어요 · 주행 구간 ${windows.size}개 점검", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text)
                        Text("시동이 꺼진 동안 한 아파트 충전도 다음 시동 때 자동으로 찾아 기록해요.", fontSize = TextSize.label, color = Pleos.colors.sub)
                    }
                }
                else -> missing.take(3).forEach { seg ->
                    LabelValue(
                        "${Format.date(seg.start.date)} ~ ${Format.date(seg.end.date)}",
                        "${Format.km(seg.distanceKm)} 주행에 충전 ${Format.kwh(seg.kWh)} — 빠진 충전이 있는지 확인해 주세요",
                    )
                }
            }
        }

        // 10. 연도별 (두 해 이상일 때)
        val yearly = ChargingStats.yearly(records)
        if (yearly.size > 1) {
            SectionDivider()
            Section("연도별") {
                yearly.forEach { (y, s) -> LabelValue("${y}년", "${Format.won(s.payment)} · ${s.count}회 · ${Format.kwh(s.kWh)}") }
            }
        }
    }
}

/** 공공데이터 한 줄: 공식 이름 · 운영기관 · 최대 출력 · 지금 빈 충전기 */
private fun publicText(p: PublicStation): String {
    val parts = mutableListOf("공공데이터: ${p.name}")
    p.operator?.let { parts += it }
    p.maxKw?.let { parts += "최대 ${it}kW" }
    if (p.statusKnown) {
        val fast = p.total(fast = true)
        parts += if (fast > 0 && fast < p.chargers.size) "빈 급속 ${p.available(fast = true)}/$fast · 빈 완속 ${p.available(fast = false)}/${p.total(fast = false)}"
        else "지금 빈 충전기 ${p.availableCount}/${p.chargers.size}"
    }
    return parts.joinToString(" · ")
}

private fun unitText(priced: PeriodStat): String = if (priced.kWh > 0) " · 평균 ${priced.unitPrice.roundToInt()}원/kWh" else ""

private fun priceRecordText(r: ChargingRecord): String =
    "${"%.1f".format(r.unitPrice)}원/kWh · ${r.provider} · ${Format.date(r.date)}"

/** 내연기관차 대비 절약 + 비교 기준(차급 → 연비, 연료 가격) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavingsSection(app: EvAutoApp, s: SavingsResult, thisMonth: PeriodEfficiency?, price: PriceSettings) {
    val c = Pleos.colors
    val scope = rememberCoroutineScope()
    var gasPrice by remember(price.gasPrice) { mutableStateOf(price.gasPrice.asInput()) }
    // 차급을 누르면 연비 칸도 그 값으로 바뀐다 (직접 고칠 수 있다)
    var gasEff by remember(price.gasEfficiency) { mutableStateOf(price.gasEfficiency.asInput()) }
    var saved by remember { mutableStateOf<String?>(null) }
    var fuel by remember { mutableStateOf<List<FuelPrice>>(emptyList()) }
    Section("내연기관차 대비 절약", "같은 거리를 내연기관차로 달렸을 때의 연료비 − 실제 충전비 · 주행거리는 차에서 자동으로 받아요") {
        if (s.available) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft).padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(EvIcons.Savings, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                        Text(if (s.savings >= 0) "지금까지 아낀 돈" else "지금까지 더 쓴 돈", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.accent)
                    }
                    Text(Format.won(abs(s.savings)), fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = if (s.savings >= 0) c.text else c.danger)
                    Text(
                        "주행 ${Format.km(s.distanceKm)}" + (s.efficiencyKmPerKwh?.let { " · 전비 ${"%.1f".format(it)}km/kWh" } ?: ""),
                        fontSize = TextSize.body, color = c.sub,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.evCostPerKm?.let { LabelValue("1km당 전기", "${it.roundToInt()}원", labelWidth = 200.dp) }
                    s.gasCostPerKm?.let { LabelValue("1km당 연료", "${it.roundToInt()}원", labelWidth = 200.dp) }
                    thisMonth?.savings(s.gasEfficiencyUsed, s.gasPriceUsed)?.let {
                        LabelValue("이번 달", if (it >= 0) "약 ${Format.won(it)} 절약" else "약 ${Format.won(-it)} 더 씀", labelWidth = 200.dp)
                    }
                }
            }
            if (s.excludedCount > 0) {
                Text("충전 누락이 의심되는 구간 ${s.excludedCount}곳(${Format.km(s.excludedKm)})은 빼고 계산했어요.", fontSize = TextSize.label, color = c.sub)
            }
        } else {
            InfoBanner(
                when (s.issue) {
                    SavingsIssue.NOT_ENOUGH_ODOMETER -> "충전이 두 번 이상 기록되면 그 사이 주행거리로 자동 계산돼요."
                    SavingsIssue.ODOMETER_NOT_INCREASING -> "충전 기록 사이에 주행거리가 늘지 않았어요. 차를 타고 다음 충전을 하면 계산돼요."
                    SavingsIssue.NO_CHARGES_IN_RANGE -> "주행 구간 사이에 충전 기록이 없어요."
                    SavingsIssue.INVALID_SETTINGS, null -> "아래 비교 기준의 연비·연료 가격을 확인해 주세요."
                },
                icon = EvIcons.Savings,
            )
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("비교 기준", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(
                "차급을 고르면 연비가 채워져요. 내 차 연비를 알면 숫자로 직접 넣으세요. 하이브리드는 차종마다 15~21km/L로 달라요.",
                fontSize = TextSize.label, color = c.sub,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GasCarClass.entries.forEach { g ->
                // 칩에는 차급 이름만 — 연비 숫자는 아래 연비 칸에 보인다
                ChoiceChip(
                    g.label,
                    selected = price.effectiveGasEfficiency == g.kmPerL,
                    onClick = {
                        gasEff = g.kmPerL.asInput()
                        scope.launch { app.repo.saveComparison(g.kmPerL, price.gasPrice); saved = "${g.label} ${g.kmPerL.roundToInt()}km/L로 계산해요" }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                "연비 (km/L)", gasEff, { gasEff = it }, Modifier.width(360.dp),
                hint = "비우면 ${PriceSettings.DEFAULT_GAS_EFFICIENCY.roundToInt()}km/L",
            )
            NumberField(
                "연료 가격 (원/L)", gasPrice, { gasPrice = it }, Modifier.width(360.dp),
                hint = "비우면 ${PriceSettings.DEFAULT_GAS_PRICE.roundToInt()}원",
            )
            PrimaryButton("저장", icon = EvIcons.Check, onClick = {
                val eff = gasEff.toDoubleOrNullTrim()?.takeIf { it > 0 }
                val fuel = gasPrice.toDoubleOrNullTrim()?.takeIf { it > 0 }
                scope.launch {
                    app.repo.saveComparison(eff, fuel)
                    saved = "연비 ${"%.1f".format(eff ?: PriceSettings.DEFAULT_GAS_EFFICIENCY).removeSuffix(".0")}km/L · " +
                        "연료 ${Format.wonNumber(fuel ?: PriceSettings.DEFAULT_GAS_PRICE)}원/L로 계산해요"
                }
            })
        }
        if (app.publicData.fuelEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("오늘 전국 평균 유가", icon = EvIcons.Refresh, onClick = {
                    scope.launch { fuel = app.publicData.fuelPrices() }
                })
                fuel.forEach { f ->
                    ChoiceChip("${f.product.label} ${f.price.roundToInt()}원", selected = gasPrice == f.price.roundToInt().toString(), onClick = {
                        gasPrice = f.price.roundToInt().toString()
                        saved = "오피넷 ${f.date?.let { "${it.monthValue}/${it.dayOfMonth} " } ?: ""}${f.product.label} 평균가예요. 저장을 누르면 반영돼요"
                    })
                }
            }
        }
        saved?.let { Text(it, fontSize = TextSize.label, color = c.ok) }
    }
}

/** 이름 · 보조 줄 · 금액 · 비율 막대 */
@Composable
private fun GroupRow(
    label: String,
    sub: String,
    amount: Double,
    fraction: Float,
    badge: String? = null,
    extra: String? = null,
    onNavigate: (() -> Unit)? = null,
) {
    val c = Pleos.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(label, fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (badge != null) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.ok.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(badge, fontSize = TextSize.caption, fontWeight = FontWeight.Bold, color = c.ok)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(Format.won(amount), fontSize = TextSize.section, fontWeight = FontWeight.Bold, color = c.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub, fontSize = TextSize.label, color = c.sub, modifier = Modifier.weight(1f))
            if (onNavigate != null) TextAction("길안내", icon = EvIcons.Directions, onClick = onNavigate)
        }
        if (extra != null) Text(extra, fontSize = TextSize.label, color = c.info)
        ProgressBar(fraction, height = 12.dp)
    }
}

/** 월별 막대 그래프 (이번 달 강조) */
@Composable
private fun MonthBars(data: List<Pair<YearMonth, PeriodStat>>) {
    val c = Pleos.colors
    val max = data.maxOf { it.second.payment }.coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(320.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEachIndexed { i, (ym, st) ->
            val current = i == data.lastIndex
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    shortWon(st.payment), fontSize = TextSize.label, fontWeight = FontWeight.SemiBold,
                    color = if (current) c.text else c.sub, maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height((200 * (st.payment / max)).toFloat().coerceAtLeast(4f).dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .background(if (current) c.accent else c.line),
                )
                Spacer(Modifier.height(10.dp))
                Text("${ym.monthValue}월", fontSize = TextSize.label, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, color = if (current) c.text else c.sub)
            }
        }
    }
}

/** 막대 위 짧은 금액: 12,300 → "1.2만", 8,300 → "8,300" */
private fun shortWon(v: Double): String = when {
    v <= 0 -> "0"
    v >= 10_000 -> "${"%.1f".format(v / 10_000).removeSuffix(".0")}만"
    else -> Format.wonNumber(v)
}
