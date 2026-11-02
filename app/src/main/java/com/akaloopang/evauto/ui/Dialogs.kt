package com.akaloopang.evauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.BillSettlement
import com.akaloopang.evauto.core.pricing.Receipt
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.StationNameSource
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.pricing.TierRange
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.voice.VoiceActions
import kotlinx.coroutines.launch
import java.time.YearMonth

/** 대화상자 안 보조 설명 */
@Composable
private fun Hint(text: String, warn: Boolean = false) {
    Text(text, fontSize = TextSize.label, color = if (warn) Pleos.colors.danger else Pleos.colors.sub)
}

/** 출력 구간 고르기 칩 (구간마다 요금이 다른 사업자) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierChips(candidates: List<PowerTier>, chosen: PowerTier?, onChoose: (PowerTier) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        candidates.forEach { t -> ChoiceChip(t.label, selected = chosen == t, onClick = { onChoose(t) }) }
    }
}

/**
 * 영수증 입력. 영수증에 적힌 값을 그대로 넣는다 — 금액은 절사·반올림된 최종값이라 다시 계산하지 않는다.
 */
@Composable
fun ReceiptDialog(app: EvAutoApp, record: ChargingRecord, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var kwh by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<PowerTier?>(null) }
    val station = app.repo.station(record.car?.stationKey)
    val receipt = amount.toDoubleOrNullTrim()?.let { Receipt(it, kwh.toDoubleOrNullTrim(), unit.toDoubleOrNullTrim()) }
    val learned = receipt?.let { app.repo.receiptUnitPrice(it) }
    // 영수증 단가가 어느 출력 구간 칸에 들어갈지 (구간마다 프로모션이 다를 수 있어 한 칸에만)
    val planned = remember(learned, chosen) {
        if (learned == null || station == null) null
        else app.voiceActions.plan(
            VoiceCommand.SetPrice(PriceTarget.CurrentStation, learned, chosen?.let { SpokenSpeed(listOf(it), exact = true) }),
            record.id,
        )
    }
    PleosDialog(
        title = "영수증 금액 입력",
        onDismiss = onDismiss,
        icon = EvIcons.Receipt,
        actions = {
            SecondaryButton("취소", onClick = onDismiss)
            PrimaryButton("확정", enabled = receipt != null, icon = EvIcons.Check, onClick = {
                val r = receipt ?: return@PrimaryButton
                val tier = (planned as? VoiceActions.Planned.Ready)?.tiers?.singleOrNull()
                scope.launch {
                    app.repo.applyReceipt(record.id, r, tier)
                    onDismiss()
                }
            })
        },
    ) {
        Text("${Format.title(record)} · 추정 ${Format.won(record.paymentAmount)}", fontSize = TextSize.title, color = Pleos.colors.text)
        NumberField("결제 금액 (원) — 영수증 그대로", amount, { amount = it })
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            NumberField("충전량 (kWh) · 있으면", kwh, { kwh = it; chosen = null }, Modifier.weight(1f))
            NumberField("단가 (원/kWh) · 있으면", unit, { unit = it; chosen = null }, Modifier.weight(1f))
        }
        when (planned) {
            is VoiceActions.Planned.Ready -> Hint("이 충전소 ${TierRange.label(planned.tiers)} 단가로도 저장돼 다음부터 자동으로 계산돼요.")
            is VoiceActions.Planned.NeedKw -> {
                Hint("충전기 출력을 고르면 이 충전소 단가로도 저장돼요. 안 고르면 이번 기록만 확정돼요.")
                TierChips(planned.candidates, chosen) { chosen = it }
            }
            else -> Hint("단가나 충전량을 넣으면 이 충전소 단가로도 저장돼 다음부터 자동으로 계산돼요.")
        }
    }
}

/**
 * 관리비 고지서 = 아파트 충전의 영수증. 그달 아파트 기록 합계를 고지서와 원 단위까지 맞춘다.
 */
@Composable
fun BillDialog(app: EvAutoApp, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 고지서는 보통 다음 달에 나오므로 기본값은 지난달
    var month by remember { mutableStateOf(YearMonth.now().minusMonths(1)) }
    var amount by remember { mutableStateOf("") }
    var kwh by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<BillSettlement.Result?>(null) }
    val homeProvider = app.repo.car.value.homeProvider
    val targets = app.repo.records.value.filter {
        BillSettlement.isTarget(it, month, homeProvider) && it.amountStatus != AmountStatus.RECEIPT
    }

    PleosDialog(
        title = "관리비 고지서 정산",
        onDismiss = onDismiss,
        icon = EvIcons.Receipt,
        actions = {
            if (result == null) {
                SecondaryButton("취소", onClick = onDismiss)
                PrimaryButton("정산", enabled = amount.toDoubleOrNullTrim() != null && targets.isNotEmpty(), icon = EvIcons.Check, onClick = {
                    val a = amount.toDoubleOrNullTrim()?.toLong() ?: return@PrimaryButton
                    scope.launch { result = app.repo.settleBill(month, a, kwh.toDoubleOrNullTrim()) }
                })
            } else {
                PrimaryButton("닫기", onClick = onDismiss)
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton("◀", onClick = { month = month.minusMonths(1); result = null })
            Text("${month.year}년 ${month.monthValue}월", fontSize = TextSize.section, fontWeight = FontWeight.Bold, color = Pleos.colors.text)
            SecondaryButton("▶", onClick = { month = month.plusMonths(1); result = null })
        }
        Hint("이 달 $homeProvider 충전 ${targets.size}건 · 추정 ${Format.won(targets.sumOf { it.paymentAmount })}")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            NumberField("고지서의 전기차 충전 요금 (원)", amount, { amount = it }, Modifier.weight(1f))
            NumberField("고지서의 충전량 (kWh) · 있으면", kwh, { kwh = it }, Modifier.weight(1f))
        }
        result?.let { r ->
            val diff = r.difference
            Text(
                "${r.updated.size}건에 나눠 반영했어요. 추정과의 차이 ${if (diff >= 0) "+" else ""}${Format.won(diff)}",
                fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = Pleos.colors.ok,
            )
        }
    }
}

/**
 * 처음 간 충전소 단가 1회 등록. 영수증이나 충전기 화면에 찍힌 kWh당 단가를 넣으면
 * 이 충전소·이 출력 구간의 확인 단가로 저장되고, 다음부터 같은 충전소에서는 자동으로 계산된다.
 */
@Composable
fun StationPriceDialog(app: EvAutoApp, record: ChargingRecord, onVoice: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var unit by remember { mutableStateOf("") }
    /** 사업자가 출력마다 요금이 달라 직접 고른 구간 */
    var chosen by remember { mutableStateOf<PowerTier?>(null) }
    val station = app.repo.station(record.car?.stationKey)
    val u = unit.toDoubleOrNullTrim()?.takeIf { it > 0 }
    // 입력한 단가로 어느 출력 구간 칸에 넣을지 바로 보여 준다 (말로 입력과 같은 규칙)
    val planned = remember(u, chosen) {
        if (u == null || station == null) null
        else app.voiceActions.plan(
            VoiceCommand.SetPrice(PriceTarget.CurrentStation, u, chosen?.let { SpokenSpeed(listOf(it), exact = true) }),
            record.id,
        )
    }
    PleosDialog(
        title = "이 충전소 단가 (1회)",
        onDismiss = onDismiss,
        icon = EvIcons.Place,
        actions = {
            if (onVoice != null && station != null) SecondaryButton("말로 입력", onClick = onVoice, icon = EvIcons.Mic)
            SecondaryButton("나중에", onClick = onDismiss)
            PrimaryButton(
                "저장",
                enabled = u != null && (station == null || planned is VoiceActions.Planned.Ready),
                icon = EvIcons.Check,
                onClick = {
                    val price = u ?: return@PrimaryButton
                    scope.launch {
                        val ready = planned as? VoiceActions.Planned.Ready
                        if (station != null && ready != null) {
                            app.repo.learnStationPrice(record.id, price, ready.tiers, ready.base, exact = chosen != null)
                        } else {
                            app.repo.confirm(record.id) // 위치 없음: 확인만
                        }
                        onDismiss()
                    }
                },
            )
        },
    ) {
        Text(station?.displayName ?: record.memo ?: Format.title(record), fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text)
        Hint("지금 적용: ${RecordFactory.priceLabel(record)}" + (if (record.unitPrice > 0) " ${"%.1f".format(record.unitPrice)}원/kWh" else ""))
        NumberField("영수증·충전기 화면의 단가 (원/kWh)", unit, { unit = it; chosen = null })
        when (planned) {
            is VoiceActions.Planned.Ready -> Text("적용 구간: ${TierRange.label(planned.tiers)}", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = Pleos.colors.accent)
            is VoiceActions.Planned.NeedKw -> {
                Hint("이 사업자는 충전기 출력마다 요금이 달라요. 충전기에 적힌 출력을 골라 주세요.")
                TierChips(planned.candidates, chosen) { chosen = it }
            }
            is VoiceActions.Planned.Fail -> Hint(planned.spoken, warn = true)
            null -> {}
        }
        Hint("한 번만 입력하면 다음부터 이 충전소에서는 자동으로 계산돼요.")
        if (station == null) Hint("충전소 위치를 알 수 없어 이 기록에만 적용돼요.", warn = true)
    }
}

/** 기록 상세 — 영수증 입력·충전소 단가·이름·삭제 */
@Composable
fun RecordDialog(app: EvAutoApp, record: ChargingRecord, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var receipt by remember { mutableStateOf(false) }
    var stationPrice by remember { mutableStateOf(false) }
    if (receipt) {
        ReceiptDialog(app, record) { receipt = false; onDismiss() }
        return
    }
    var voice by remember { mutableStateOf(false) }
    if (voice) {
        VoicePriceDialog(app, record.id, suggest = false) { voice = false; onDismiss() }
        return
    }
    if (stationPrice) {
        StationPriceDialog(app, record, onVoice = { stationPrice = false; voice = true }) { stationPrice = false; onDismiss() }
        return
    }
    val station = app.repo.station(record.car?.stationKey)
    var rename by remember { mutableStateOf(false) }
    if (rename && station != null) {
        RenameStationDialog(app, station) { rename = false }
        return
    }
    val meta = record.car
    PleosDialog(
        title = Format.title(record),
        onDismiss = onDismiss,
        icon = EvIcons.Bolt,
        actions = {
            TextAction("삭제", onClick = { scope.launch { app.repo.delete(record.id); onDismiss() } }, color = Pleos.colors.danger, icon = EvIcons.Delete)
            if (station != null) {
                SecondaryButton("충전소 단가", onClick = { stationPrice = true }, icon = EvIcons.Place)
                SecondaryButton("이름", onClick = { rename = true }, icon = EvIcons.Edit)
            }
            if (record.amountStatus != AmountStatus.RECEIPT) {
                PrimaryButton("영수증 입력", onClick = { receipt = true }, icon = EvIcons.Receipt)
            } else {
                PrimaryButton("닫기", onClick = onDismiss)
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                when {
                    record.isFree -> "0원"
                    record.paymentAmount > 0 -> Format.won(record.paymentAmount)
                    else -> "단가 없음"
                },
                fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = Pleos.colors.text,
            )
            StatusChip(record.amountStatus, record.isFree)
        }
        LabelValue("일시", Format.date(record.date))
        station?.let { st ->
            LabelValue("충전소", st.displayName)
            if (st.name != null) st.address?.let { LabelValue("주소", it) }
            st.coordinates?.let { LabelValue("위치(GPS)", it) }
        }
        LabelValue("충전량", Format.kwh(record.kWh))
        if (record.unitPrice > 0) LabelValue("단가", "${"%.2f".format(record.unitPrice)}원/kWh")
        if (meta != null) LabelValue("단가 출처", RecordFactory.priceLabel(record))
        meta?.socStart?.let { LabelValue("SOC", "$it% → ${meta?.socEnd ?: "-"}%") }
        meta?.observedPeakKw?.let {
            if (meta?.peakFromAverage == true) LabelValue("충전 출력", "평균 ${it.toInt()}kW 이상 (SOC 상승 속도)")
            else LabelValue("최대 충전 출력", "${it.toInt()}kW")
        }
        record.odometer?.let { LabelValue("주행거리", Format.km(it)) }
        if (record.amountStatus != AmountStatus.ESTIMATED) {
            meta?.estimatedAmount?.let { LabelValue("차량 추정이었던 금액", Format.won(it)) }
        }
    }
}

/** 자주 충전하는 곳 — 눌러서 이름을 바로 채운다 (집은 아파트 충전으로 따로 관리) */
private val PLACE_PRESETS = listOf("회사", "부모님 댁", "처가", "시댁", "마트", "백화점", "헬스장", "학원 근처", "단골 휴게소", "캠핑장")

/**
 * 충전소 이름 붙이기 (정차 중). 내비 없이 간 곳은 사업자·주소로만 보이므로 "회사", "집 앞 마트 SK"처럼 알아보기 쉬운 이름을 붙인다.
 * 같은 자리(GPS 150m)·같은 사업자 충전소에 모두 적용된다. 비우면 내비 이름·주소 표시로 돌아간다.
 * (선택) 요금을 받지 않는 충전소는 '무료 충전소'를 켜 두면 0원으로 기록하고 단가를 묻지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenameStationDialog(app: EvAutoApp, station: StationRef, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(if (station.nameSource == StationNameSource.USER) station.name.orEmpty() else "") }
    val wasFree = remember { app.repo.book.value.isFree(station) }
    var free by remember { mutableStateOf(wasFree) }
    // 칩을 누르면 키보드를 내린다 (차 화면을 가리지 않게)
    val focus = LocalFocusManager.current
    PleosDialog(
        title = "충전소 이름",
        onDismiss = onDismiss,
        icon = EvIcons.Edit,
        actions = {
            SecondaryButton("취소", onClick = onDismiss)
            PrimaryButton("저장", icon = EvIcons.Check, onClick = {
                scope.launch {
                    // 무료 표시를 먼저 — 이름 바꾸기가 장부의 표시 이름까지 맞춘다
                    if (free != wasFree) app.repo.setStationFree(station, free)
                    app.repo.renameStation(station.key, name)
                    onDismiss()
                }
            })
        },
    ) {
        LabelValue("지금 이름", station.displayName)
        station.operator?.let { LabelValue("운영 사업자", it) }
        station.address?.let { LabelValue("주소", it) }
        station.coordinates?.let { LabelValue("위치(GPS)", it) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("새 이름 (예: 회사, 집 앞 마트 SK)") },
            textStyle = TextStyle(fontSize = TextSize.title, color = Pleos.colors.text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PLACE_PRESETS.forEach { p -> ChoiceChip(p, selected = name == p, onClick = { name = p; focus.clearFocus() }) }
        }
        Hint("비우면 내비 목적지 이름이나 주소로 보여요.")
        ChoiceChip(if (free) "무료 충전소 · 켜짐" else "무료 충전소 · 꺼짐", selected = free, onClick = { free = !free; focus.clearFocus() })
        Hint(
            if (free) "이 충전소는 0원으로 기록하고 단가를 묻지 않아요. 확인을 기다리는 기록도 0원으로 바뀌어요."
            else "선택 사항이에요. 요금을 받지 않는 충전소일 때만 켜 주세요.",
        )
    }
}
