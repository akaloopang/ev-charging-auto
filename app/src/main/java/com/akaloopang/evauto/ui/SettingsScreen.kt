package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.publicdata.RegionCode
import com.akaloopang.evauto.core.stats.StationPick
import com.akaloopang.evauto.core.stats.StationPicker
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.session.NaviChargingStation
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.PriceSettings
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.RepricePolicy
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.RoundingRule
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TierRange
import com.akaloopang.evauto.core.session.HomeZone
import com.akaloopang.evauto.core.session.VehicleSnapshot
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.VoicePriceParser
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

// 설정은 Pleos 설정 화면처럼 왼쪽 메뉴에서 고르는 여러 페이지로 나눈다 (한 화면에 길게 늘어놓지 않는다)

/** 집 충전: 아파트 단가 · 집 충전기 사업자 · 집 위치 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeChargingPage(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val price by app.repo.price.collectAsStateWithLifecycle()
    val car by app.repo.car.collectAsStateWithLifecycle()
    val monitor by app.repo.monitor.collectAsStateWithLifecycle()
    var aptAc by remember(price) { mutableStateOf(price.apartmentAcUnitPrice.asInput()) }
    var aptDc by remember(price) { mutableStateOf(price.apartmentDcUnitPrice.asInput()) }
    var priceMsg by remember { mutableStateOf<String?>(null) }
    var homeMsg by remember { mutableStateOf<String?>(null) }
    var voice by remember { mutableStateOf(false) }
    if (voice) VoicePriceDialog(app, recordId = null, target = PriceTarget.Apartment) { voice = false }
    val savePrice = rememberPriceSaver(app) { priceMsg = it }

    PageColumn {
        PageHeader("집 충전", "아파트·집 충전기의 단가와 위치예요. 앱이 꺼져 있던 밤사이 충전 금액을 계산하는 데 써요.")

        Section(
            "아파트 충전 단가",
            "관리비 고지서의 kWh당 단가를 넣으세요. 고지서가 나오면 홈의 '관리비 고지서로 정산'으로 원 단위까지 맞춰요.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                NumberField("완속 (원/kWh)", aptAc, { aptAc = it }, Modifier.weight(1f))
                NumberField("급속 (원/kWh) · 아파트에 있으면", aptDc, { aptDc = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("저장", icon = EvIcons.Check, onClick = {
                    savePrice(
                        price.copy(
                            apartmentAcUnitPrice = aptAc.toDoubleOrNullTrim()?.takeIf { it > 0 },
                            apartmentDcUnitPrice = aptDc.toDoubleOrNullTrim()?.takeIf { it > 0 },
                        ),
                    )
                })
                SecondaryButton("말로 입력", onClick = { voice = true }, icon = EvIcons.Mic)
                priceMsg?.let { Text(it, fontSize = TextSize.caption, color = Pleos.colors.ok) }
            }
        }
        SectionDivider()

        Section("집 충전기 사업자", "관리비로 청구되면 '아파트', 사업자 카드로 결제하는 충전기면 그 사업자를 고르세요.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                price.providers.filter { it != ProviderCatalog.OTHER }.forEach { p ->
                    ChoiceChip(p, selected = car.homeProvider == p, onClick = { scope.launch { app.repo.saveCar(car.copy(homeProvider = p)) } })
                }
            }
        }
        SectionDivider()

        val home = car.home
        Section(
            "집 위치",
            if (home != null) "저장됨 · 반경 ${home.radiusM.toInt()}m 안의 완속 충전은 집 충전으로 기록해요. 이 안의 급속은 집 근처 공용 충전소로 봐요."
            else "아직 없어요. 첫 아파트 충전 때 '네, 집 충전기'를 누르거나, 집에 있을 때 아래 버튼을 누르면 저장돼요.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("지금 위치를 집으로", icon = EvIcons.Place, onClick = {
                    val loc = monitor.lastSeen?.location
                    if (loc == null) homeMsg = "현재 위치를 아직 못 받았어요"
                    else scope.launch { app.repo.saveCar(car.copy(home = HomeZone(loc))); homeMsg = "지금 위치를 집으로 저장했어요" }
                })
                if (home != null) {
                    SecondaryButton("집 위치 지우기", icon = EvIcons.Delete, onClick = { scope.launch { app.repo.saveCar(car.copy(home = null)) } })
                }
                homeMsg?.let { Text(it, fontSize = TextSize.caption, color = Pleos.colors.sub) }
            }
        }
    }
}

/**
 * 단가 저장 — **지난 기록은 그때 단가 그대로**가 기본. 설정 단가로 계산한 지난 기록의 금액이 달라질 상황이면
 * "단가가 바뀐 건가요, 잘못 입력한 건가요?"를 묻는다 (어제까지 190원·오늘부터 250원이면 어제 기록은 190원이 맞다).
 */
@Composable
private fun rememberPriceSaver(app: EvAutoApp, onSaved: (String) -> Unit): (PriceSettings) -> Unit {
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Pair<PriceSettings, Int>?>(null) }
    fun save(p: PriceSettings, fixPast: Boolean, n: Int) = scope.launch {
        val unpriced = app.repo.records.value.count(RepricePolicy::unpriced)
        app.repo.savePrice(p, fixPast)
        ask = null
        onSaved(
            when {
                fixPast -> "저장했어요. 지난 기록 ${n}건도 새 단가로 고쳤어요."
                unpriced > 0 -> "저장했어요. 단가가 비어 있던 기록 ${unpriced}건을 채웠고, 다음 충전부터 이 단가로 계산해요."
                else -> "저장했어요. 다음 충전부터 이 단가로 계산해요. 지난 기록은 그대로예요."
            },
        )
    }
    ask?.let { (p, n) ->
        PleosDialog(
            title = "지난 기록은 어떻게 할까요?",
            onDismiss = { ask = null },
            icon = EvIcons.Info,
            actions = {
                SecondaryButton("잘못 입력했어요 · ${n}건도 고치기", onClick = { save(p, fixPast = true, n) })
                PrimaryButton("그대로 두기", icon = EvIcons.Check, onClick = { save(p, fixPast = false, n) })
            },
        ) {
            Text("이미 기록된 충전 ${n}건은 예전 단가로 계산돼 있어요.", fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text)
            Text(
                "단가가 오르거나 내린 거라면 지난 기록은 그대로 두세요 — 그때 낸 금액이 맞으니까요. " +
                    "처음부터 잘못 입력한 값이었다면 지난 기록도 새 단가로 고칠 수 있어요. 영수증·관리비로 확정한 금액은 어느 쪽이든 바뀌지 않아요.",
                fontSize = TextSize.body, color = Pleos.colors.sub,
            )
        }
    }
    return { p ->
        val n = app.repo.affectedBySetting(p)
        if (n > 0) ask = p to n else save(p, fixPast = false, 0)
    }
}

/** 사업자 단가: 사업자를 칩으로 고르고 출력 5구간 칸을 채운다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderPricePage(app: EvAutoApp) {
    val price by app.repo.price.collectAsStateWithLifecycle()
    val providers = price.providers.filter { it != ProviderCatalog.APARTMENT && it != ProviderCatalog.OTHER }
    var selected by remember { mutableStateOf(providers.firstOrNull()) }

    PageColumn {
        PageHeader(
            "사업자 단가",
            "같은 사업자도 충전기 출력마다 요금이 달라요. 빈 칸은 공시 안내가로 계산하고, 칸을 채우면 내 단가(멤버십·할인가)가 우선해요.",
        )
        Section("사업자", "● 표시는 내 단가를 넣은 사업자예요.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                providers.forEach { p ->
                    val custom = price.providerPrices.any { it.provider == p && it.expanded().isNotEmpty() }
                    ChoiceChip(if (custom) "$p ●" else p, selected = selected == p, onClick = { selected = p })
                }
            }
        }
        SectionDivider()
        selected?.let { p -> ProviderTierEditor(app, p) }
    }
}

@Composable
private fun ProviderTierEditor(app: EvAutoApp, provider: String) {
    val scope = rememberCoroutineScope()
    val price by app.repo.price.collectAsStateWithLifecycle()
    val car by app.repo.car.collectAsStateWithLifecycle()
    val current = price.providerPrices.firstOrNull { it.provider == provider }
    // 예전 완속/급속/초급속 값도 해당 구간 칸에 펼쳐 보여 준다 → 저장하면 구간 칸으로 옮겨진다
    val shown = current?.expanded().orEmpty()
    val fields = remember(provider, current) { PowerTier.entries.associateWith { t -> mutableStateOf(shown[t].asInput()) } }
    val official = app.repo.tariff?.find(provider)?.let { if (car.useMemberTariff) it.member else it.nonMember }.orEmpty()
    var voice by remember { mutableStateOf(false) }
    var msg by remember(provider) { mutableStateOf<String?>(null) }
    if (voice) VoicePriceDialog(app, recordId = null, target = PriceTarget.Provider(provider)) { voice = false }
    val savePrice = rememberPriceSaver(app) { msg = it }

    Section(
        "$provider · 출력 구간별 단가 (원/kWh)",
        if (official.isEmpty()) "공시 요금표에 없는 사업자예요. 아는 구간만 채우세요."
        else "칸 아래 회색 글씨는 공시 ${if (car.useMemberTariff) "회원가" else "비회원가"}예요.",
    ) {
        // 차량 화면 폭: 3칸 + 2칸
        PowerTier.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { t ->
                    val f = fields.getValue(t)
                    val name = if (t == PowerTier.UNDER_30) "완속 (30kW 미만)" else t.label
                    NumberField(
                        name, f.value, { f.value = it }, Modifier.weight(1f),
                        hint = official[t]?.let { "공시 ${VoicePriceParser.formatPrice(it)}원" } ?: "공시 없음",
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("$provider 단가 저장", icon = EvIcons.Check, onClick = {
                val tiers = PowerTier.entries.mapNotNull { t -> fields.getValue(t).value.toDoubleOrNullTrim()?.takeIf { it > 0 }?.let { t to it } }.toMap()
                val updated = ProviderUnitPrice(provider = provider, tierPrices = tiers)
                val list = price.providerPrices.filterNot { it.provider == provider } + listOfNotNull(updated.takeIf { tiers.isNotEmpty() })
                savePrice(price.copy(providerPrices = list))
            })
            SecondaryButton("말로 입력", onClick = { voice = true }, icon = EvIcons.Mic)
            msg?.let { Text(it, fontSize = TextSize.caption, color = Pleos.colors.ok) }
        }
    }
}

/** 충전소 단가: 충전소마다 따로 확인한 단가 (GPS·이름으로 구분) */
@Composable
fun StationPricePage(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val book by app.repo.book.collectAsStateWithLifecycle()
    val stations by app.repo.stations.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<StationRef?>(null) }
    renaming?.let { st -> RenameStationDialog(app, st) { renaming = null } }
    val groups = book.grouped()
    val c = Pleos.colors

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            PageHeader(
                "충전소 단가",
                "같은 사업자라도 충전소마다 단가가 달라요(예: 집 근처 345원, 여행지 430원). 처음 간 충전소에서 한 번 알려 준 단가를 충전소·출력 구간별로 기억해요.",
            )
        }
        if (groups.isEmpty()) {
            item {
                Column(Modifier.padding(top = 32.dp)) {
                    EmptyState(
                        "아직 확인한 충전소 단가가 없어요",
                        "공용 충전이 끝나면 앱이 단가를 물어봐요. 영수증 금액을 넣어도 자동으로 저장돼요.",
                        icon = EvIcons.Place,
                    )
                }
            }
        }
        items(groups, key = { g -> g.station.key + g.tiers.joinToString { it.name } + g.unitPrice + g.confirmedOn }) { g ->
            // 장부에는 확인 당시 정보가, 충전소 목록에는 최신 이름이 있다
            val st = stations.firstOrNull { it.key == g.station.key } ?: g.station
            val range = when {
                g.isFree -> "모든 충전기"
                g.tiers.isNotEmpty() -> TierRange.label(g.tiers)
                else -> g.chargeType?.speedName ?: "구간 모름"
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(c.tile), contentAlignment = Alignment.Center) {
                    Icon(EvIcons.Place, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(st.displayName, fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(
                        listOfNotNull(st.operator, st.coordinates?.let { "GPS $it" }, "확인 ${g.confirmedOn}").joinToString(" · "),
                        fontSize = TextSize.caption, color = c.sub,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (g.isFree) "무료" else "${VoicePriceParser.formatPrice(g.unitPrice)}원", fontSize = TextSize.section, fontWeight = FontWeight.Bold, color = if (g.isFree) c.accent else c.text)
                    Text(range, fontSize = TextSize.caption, color = c.sub)
                }
                SecondaryButton("이름", onClick = { renaming = st }, icon = EvIcons.Edit)
                SecondaryButton("삭제", onClick = { scope.launch { app.repo.forgetStation(g.station) } }, icon = EvIcons.Delete)
            }
            SectionDivider()
        }
    }
}

/** 차량·계산: 배터리 · 원 단위 처리 · 공시가 기준 · 단가 적용 순서 · 백업 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleCalcPage(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val car by app.repo.car.collectAsStateWithLifecycle()
    var capacity by remember(car) { mutableStateOf(car.batteryCapacityKwh.asInput()) }
    val snap by app.monitor.lastSnapshot.collectAsStateWithLifecycle()
    var target by remember(car) { mutableStateOf(car.targetSoc.toString()) }
    var carMsg by remember { mutableStateOf<String?>(null) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val c = Pleos.colors

    PageColumn {
        PageHeader("차량·계산", "배터리 정보와 금액 계산 방식이에요.")

        val vehicleCapacity = snap?.batteryCapacityKwh
        Section(
            "배터리",
            "충전량(kWh)은 배터리 용량 × SOC 변화로 계산해요. 용량을 넣으면 그 값을, 비워 두면 차량이 알려 주는 값을 써요." +
                (vehicleCapacity?.let { " · 차량이 알려 준 용량 ${"%.1f".format(it)}kWh" } ?: ""),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                NumberField("배터리 용량 (kWh)", capacity, { capacity = it }, Modifier.weight(1f), hint = "넣으면 차량 값보다 우선")
                NumberField("목표 충전량 (%)", target, { target = it }, Modifier.weight(1f), hint = "충전 중 '목표까지 남은 시간·금액'에 써요")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("저장", icon = EvIcons.Check, onClick = {
                    scope.launch {
                        app.repo.saveCar(
                            car.copy(
                                batteryCapacityKwh = capacity.toDoubleOrNullTrim()?.takeIf { it > 0 },
                                targetSoc = (target.toIntOrNull() ?: car.targetSoc).coerceIn(10, 100),
                            ),
                        )
                        carMsg = "저장했어요."
                    }
                })
                carMsg?.let { Text(it, fontSize = TextSize.caption, color = c.ok) }
            }
        }
        SectionDivider()

        Section("추정 금액 원 단위", "영수증 금액은 절사·반올림된 그대로 써요. 차량 추정 금액만 이 방식으로 맞춰요.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundingRule.entries.forEach { rule ->
                    ChoiceChip(
                        when (rule) {
                            RoundingRule.HALF_UP_1 -> "1원 단위 반올림"
                            RoundingRule.FLOOR_1 -> "1원 미만 절사"
                            RoundingRule.FLOOR_10 -> "10원 미만 절사"
                        },
                        selected = car.roundingRule == rule,
                        onClick = { scope.launch { app.repo.saveCar(car.copy(roundingRule = rule)) } },
                    )
                }
            }
        }
        SectionDivider()

        val t = app.repo.tariff
        Section(
            "공시 안내가 기준",
            if (t != null) "무공해차 통합누리집 사업자별 요금표 · ${t.collectedOn} 기준 · ${t.entries.size}개 사업자"
            else "공시 요금표를 불러오지 못했어요",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChoiceChip("회원가", selected = car.useMemberTariff, onClick = { scope.launch { app.repo.saveCar(car.copy(useMemberTariff = true)) } })
                ChoiceChip("비회원가", selected = !car.useMemberTariff, onClick = { scope.launch { app.repo.saveCar(car.copy(useMemberTariff = false)) } })
            }
        }
        SectionDivider()

        Section("단가 적용 순서", "위에 있는 단가가 있으면 그것을 써요. 영수증 금액이 들어오면 언제나 영수증이 최종값이에요.") {
            OrderRow(1, "이 충전소 확인 단가", "처음 간 충전소에서 한 번 알려 준 단가 (충전소·출력 구간별)")
            OrderRow(2, "내 설정 단가", "사업자 단가(멤버십·할인가), 아파트 단가")
            OrderRow(3, "같은 사업자 다른 충전소 단가", "\"지난번 ○○점처럼 420원으로 할까요?\" 하고 확인받아요")
            OrderRow(4, "공시 안내가", "처음 가는 충전소도 바로 근사 금액을 보여 줘요")
        }
        SectionDivider()

        Section(
            "음성 안내",
            "Pleos 음성(TTS)으로 알려 줘요: 내비로 가는 충전소의 예상 충전비 · 충전 기록 · 목적지 도착 때 이번 주행 비용 · 달이 바뀐 뒤 지난달 충전비",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChoiceChip("켜기", selected = car.voiceAnnounce, onClick = { scope.launch { app.repo.saveCar(car.copy(voiceAnnounce = true)) } })
                ChoiceChip("끄기", selected = !car.voiceAnnounce, onClick = { scope.launch { app.repo.saveCar(car.copy(voiceAnnounce = false)) } })
            }
        }
        SectionDivider()

        PublicDataSection(app)
        SectionDivider()

        Section("백업", "충전 기록과 단가 설정을 JSON 파일로 내보내요. 휴대폰 앱으로 옮기거나 보관할 때 써요.") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("백업 파일 내보내기", icon = EvIcons.Download, onClick = {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val f = app.repo.exportBackup(File(dir, "${LocalDate.now()}_ev_charging_backup.json"))
                    backupMsg = "저장 위치: ${f.absolutePath}"
                })
            }
            backupMsg?.let { Text(it, fontSize = TextSize.caption, color = c.sub) }
        }
    }
}

/** 공공데이터: 충전소 정보(한국환경공단) · 평균 유가(오피넷). 키는 local.properties에 넣어 빌드한다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublicDataSection(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val c = Pleos.colors
    val pd = app.publicData
    val region by pd.region.collectAsStateWithLifecycle()
    val cache by pd.cache.collectAsStateWithLifecycle()
    val busy by pd.busy.collectAsStateWithLifecycle()
    val msg by pd.message.collectAsStateWithLifecycle()
    Section(
        "공공데이터",
        "충전소 공식 이름·운영기관·출력과 지금 빈 충전기 수(한국환경공단), 오늘 평균 유가(오피넷). " +
            "내 위치는 보내지 않아요 — 시·도 단위 목록을 받아 기기 안에서 내 충전소 주변만 남겨요.",
    ) {
        LabelValue("충전소 정보", if (pd.chargerEnabled) "사용 가능" else "키 없음 — 공공데이터포털 키를 넣고 빌드하면 켜져요")
        LabelValue("평균 유가", if (pd.fuelEnabled) "사용 가능" else "키 없음 — 오피넷 키를 넣고 빌드하면 켜져요")
        if (pd.chargerEnabled) {
            Text("지역 (비우면 충전소 주소로 정해요)", fontSize = TextSize.label, color = c.sub)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceChip("자동", selected = region == null, onClick = { pd.setRegion(null) })
                RegionCode.choices.forEach { (label, codes) ->
                    ChoiceChip(label, selected = region != null && region in codes, onClick = { pd.setRegion(codes.first()) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(if (busy) "받는 중…" else "충전소 정보 새로 받기", enabled = !busy, icon = EvIcons.Refresh, onClick = {
                    scope.launch { pd.refresh(forceFull = true) }
                })
                Text(
                    msg ?: cache?.let { "${RegionCode.label(it.zcode) ?: it.zcode} · 내 충전소 주변 ${it.chargers.size}대 · ${it.infoAt.toLocalDate()} 받음" } ?: "아직 받지 않았어요",
                    fontSize = TextSize.label, color = c.sub,
                )
            }
        }
    }
}

@Composable
private fun OrderRow(n: Int, title: String, desc: String) {
    val c = Pleos.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(c.inverse), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = TextSize.title, fontWeight = FontWeight.Bold, color = c.onInverse)
        }
        Column {
            Text(title, fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(desc, fontSize = TextSize.caption, color = c.sub)
        }
    }
}

/** 시연용(디버그 빌드, 또는 release에서 버전 5번): 차량 신호 확인 · 야간 충전 시연 · 시험 기록 정리 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoPage(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val c = Pleos.colors
    var message by remember { mutableStateOf<String?>(null) }
    var ignitionOff by remember { mutableStateOf(false) }
    val conn by app.monitor.connection.collectAsStateWithLifecycle()
    val snap by app.monitor.lastSnapshot.collectAsStateWithLifecycle()
    val live by app.monitor.live.collectAsStateWithLifecycle()
    val drive by app.assistant.drive.collectAsStateWithLifecycle()
    val naviStatus by app.navi.status.collectAsStateWithLifecycle()
    val naviPlan by app.assistant.plan.collectAsStateWithLifecycle()

    PageColumn {
        PageHeader("시연", "에뮬레이터 시연용이에요. 실제 차량에서는 쓰지 않아요.")
        message?.let { InfoBanner(it) }

        Section(
            "운전 도우미 시연",
            "내비 목적지를 충전소로 정한 상황을 흉내 내요. 홈 화면에 예상 충전비가 뜨고 한 번 읽어 줘요. 실제 길안내는 차 내비로 넘겨요.",
        ) {
            Text(
                naviStatus + (naviPlan?.let { " · 안내 중인 충전소: ${it.station.name} (${it.station.maxChargingPowerKw}kW, 도착 ${it.arrivalSoc ?: "-"}% → 목표 ${it.endSoc ?: "-"}%)" } ?: " · 충전소 안내 없음"),
                fontSize = TextSize.label, color = c.text,
            )
            Text(driveDiag(drive), fontSize = TextSize.label, color = c.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton("시연용 석 달 기록 (지우고 새로)", icon = EvIcons.History, onClick = {
                    scope.launch {
                        app.repo.addDemoMonths()
                        message = "8월부터 어젯밤까지 아파트·공용 충전 ${app.repo.records.value.size}건을 넣었어요. 홈·통계에서 확인하세요."
                    }
                })
                SecondaryButton("다른 SK 충전소 충전 (시청점)", icon = EvIcons.Place, onClick = {
                    scope.launch {
                        app.repo.addDemoOtherStation()
                        message = "SK일렉링크 시청점 충전을 넣었어요. 홈에서 '지난번 판교점 단가로 할까요?'를 확인하세요."
                    }
                })
                SecondaryButton("회사 주차장 완속 충전 (7kW)", icon = EvIcons.Place, onClick = {
                    scope.launch {
                        app.repo.addDemoOfficeCharge()
                        message = "회사 지하주차장 완속 충전을 넣었어요. 홈 카드의 '이름 붙이기'로 '회사' 같은 이름을 붙여 보세요."
                    }
                })
                SecondaryButton("시청점으로 안내 중 (100kW · 30→80%)", icon = EvIcons.Directions, onClick = {
                    app.assistant.demoPlan(
                        NaviChargingPlan(NaviChargingStation("SK일렉링크 시청점", 100, chargerCount = 4, targetSoc = 80), arrivalSoc = 30, endSoc = 80),
                    )
                    message = "홈 화면에 '가는 충전소' 카드가 떠요. 단가를 아는 충전소면 금액까지 읽어 줘요."
                })
                SecondaryButton("판교점으로 안내 중 (100kW · 30→80%)", icon = EvIcons.Directions, onClick = {
                    app.assistant.demoPlan(
                        NaviChargingPlan(NaviChargingStation("SK일렉링크 판교점", 100, chargerCount = 4, targetSoc = 80), arrivalSoc = 30, endSoc = 80),
                    )
                    message = "홈 화면에 '가는 충전소' 카드가 떠요. 이어서 Virtual Console로 충전하면 판교점 충전으로 기록해요."
                })
                SecondaryButton("안내 끝내기", onClick = { app.assistant.demoPlan(null); message = "충전소 안내를 끝냈어요." })
                SecondaryButton("지난달 브리핑 듣기", icon = EvIcons.Speaker, onClick = { app.assistant.demoBriefing() })
                SecondaryButton("자주 가는 충전소로 실제 길안내", icon = EvIcons.Directions, onClick = {
                    val st = StationPicker.pick(app.repo.records.value, app.repo.stations.value, StationPick.MOST_VISITED)?.station
                    val at = st?.location
                    message = when {
                        st == null || at == null -> "위치가 남은 충전소 기록이 없어요. 아래 '예시 기록 넣기'를 먼저 누르세요."
                        app.navi.routeTo(at.lat, at.lon, st.displayName, st.address) -> "${st.displayName}까지 길안내를 내비에 요청했어요."
                        else -> "내비게이션에 연결하지 못했어요. (${app.navi.lastRouteError ?: "이유 모름"})"
                    }
                })
            }
        }
        SectionDivider()

        Section(
            "아파트 야간 충전 시연",
            "① 시동 끄기 → ② Virtual Console에서 SOC 올리기(주행거리 그대로) → ③ 시동 켜기. 시동 끈 시각을 8시간 전으로 옮겨 실제 밤사이 충전처럼 만들어요.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton("① 시동 끄기", enabled = !ignitionOff, onClick = {
                    scope.launch {
                        app.monitor.demoIgnitionOff()
                        ignitionOff = true
                        message = "시동을 껐어요. Virtual Console에서 SOC를 올린 뒤 '③ 시동 켜기'를 누르세요."
                    }
                })
                PrimaryButton("③ 시동 켜기", enabled = ignitionOff, onClick = {
                    app.monitor.demoIgnitionOn()
                    ignitionOff = false
                    message = "시동을 켰어요. 꺼진 동안의 충전을 찾아 홈 화면에 기록해요."
                })
            }
        }
        SectionDivider()

        Section("차량 신호", "Virtual Console에서 바꾼 값이 앱에 들어오는지 확인해요.") {
            Text(conn, fontSize = TextSize.label, color = c.text)
            Text(snap?.let(::vehicleDiag) ?: "차량 값: 아직 없음", fontSize = TextSize.label, color = c.text)
            Text("충전 판정: " + if (live != null) "충전 중" else "대기", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = if (live != null) c.accent else c.text)
        }
        SectionDivider()

        Section("시험 기록", "촬영 전에 시험 기록을 지우고, 화면 확인용 예시 기록을 넣을 수 있어요.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton("기록 모두 지우기", icon = EvIcons.Delete, onClick = {
                    scope.launch { app.repo.clearRecords(); message = "충전 기록을 모두 지웠어요." }
                })
                SecondaryButton("예시 기록 넣기", icon = EvIcons.History, onClick = {
                    scope.launch { app.repo.addDemoData(); message = "예시 기록 3건을 넣었어요. 홈·충전 기록에서 확인하세요." }
                })
                SecondaryButton("다른 SK 충전소 충전 넣기", icon = EvIcons.Place, onClick = {
                    scope.launch {
                        app.repo.addDemoOtherStation()
                        message = "SK일렉링크 시청점 충전을 넣었어요. 판교점 단가를 먼저 확인했다면 '지난번 판교점처럼 ○○원으로 할까요?'로 물어봐요."
                    }
                })
                SecondaryButton("처음 설정 다시 보기", icon = EvIcons.Apartment, onClick = {
                    scope.launch { app.repo.saveCar(app.repo.car.value.copy(setupDone = false)) }
                })
            }
        }
    }
}

/** 시연용: 주행 정보 한 줄 */
private fun driveDiag(d: com.akaloopang.evauto.core.session.DriveInfo): String = buildString {
    append("주행 정보 — 주행가능 ${d.rangeKm?.let { "%.0fkm".format(it) } ?: "-"}")
    append(" · 이번 주행 ${d.tripKm?.let { "%.1fkm".format(it) } ?: "-"}")
    append(" · 전비 ${d.efficiencyKmPerKwh?.let { "%.1fkm/kWh".format(it) } ?: "-"}")
    append(" (원시 ${d.currentEfficiencyRaw ?: "-"}/${d.sinceChargeEfficiencyRaw ?: "-"}/${d.cumulativeEfficiencyRaw ?: "-"})")
    append(" · 용량 ${d.capacityKwh?.let { "%.1fkWh".format(it) } ?: "-"}")
}

/** 시연용: 차량 값 한 줄 */
private fun vehicleDiag(s: VehicleSnapshot): String = buildString {
    append("${s.time.toLocalTime().withNano(0)} — ")
    append("SOC ${s.socPercent?.let { "%.1f%%".format(it) } ?: "-"}")
    append(" · 주행 ${s.odometerKm?.let { "%.1fkm".format(it) } ?: "-"}")
    append(" · 충전상태 ${s.chargeState ?: "-"}")
    append(" · 포트 ${s.portConnected?.let { if (it) "연결" else "분리" } ?: "-"}")
    append(" · 출력 ${s.chargeRateKw?.let { "%.1fkW".format(it) } ?: "-"}")
    append(" · 위치 ${s.location?.let { "%.4f, %.4f".format(it.lat, it.lon) } ?: "-"}")
}
