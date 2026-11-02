package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.session.ChargeCostEstimate
import com.akaloopang.evauto.core.session.NaviChargingPlan
import com.akaloopang.evauto.core.stats.TripCost
import kotlin.math.roundToInt

/**
 * 홈 상단의 운전 카드 — 내비가 충전소로 안내 중이면 '예상 충전비', 주행 중이면 '이번 주행 비용'.
 * 둘 다 없으면 아무것도 그리지 않는다.
 */
@Composable
fun DriveCards(app: EvAutoApp) {
    val expected by app.assistant.expected.collectAsStateWithLifecycle()
    val trip by app.assistant.trip.collectAsStateWithLifecycle()
    val plan by app.assistant.plan.collectAsStateWithLifecycle()
    val skipReason by app.assistant.skipReason.collectAsStateWithLifecycle()
    if (expected == null && trip == null && plan == null) return
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        expected?.let { ExpectedCostCard(it, onSpeak = app.assistant::speakExpected) }
        // 충전소로 가는 중인데 예상 충전비가 없으면 이유를 한 줄로 (도착 때 이미 목표 이상 등)
        val p = plan
        val why = skipReason
        if (expected == null && p != null && why != null) NoChargeRow(p, why)
        trip?.let { TripCostRow(it, onSpeak = app.assistant::speakTrip) }
    }
}

@Composable
private fun ExpectedCostCard(e: ChargeCostEstimate, onSpeak: () -> Unit) {
    val c = Pleos.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft)
            .border(2.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Icon(EvIcons.Directions, contentDescription = null, tint = c.accent, modifier = Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "가는 충전소 · ${e.stationName}", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                e.amount?.let { "약 ${Format.won(it)}" } ?: "단가 확인 필요",
                fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text,
            )
            val soc = "${e.fromSoc.roundToInt()}% → ${e.toSoc.roundToInt()}%" + if (e.arrivalFromNavi) "" else " (지금 배터리 기준)"
            val energy = e.kWh?.let { " · 약 ${Format.kwh(it)}" } ?: ""
            val unit = e.unitPrice?.let { " · ${it.roundToInt()}원/kWh ${sourceText(e)}" } ?: " · 이 충전소 단가는 충전 후 한 번만 알려 주세요"
            Text(soc + energy + unit, fontSize = TextSize.label, color = c.sub)
        }
        SecondaryButton("읽어 주기", onClick = onSpeak, icon = EvIcons.Speaker)
    }
}

private fun sourceText(e: ChargeCostEstimate): String = when (e.priceSource) {
    PriceSource.STATION_CONFIRMED -> "(이 충전소 확인 단가)"
    PriceSource.OTHER_STATION -> "(${e.refStation?.displayName ?: "같은 사업자"} 단가)"
    PriceSource.MY_SETTING -> "(내 설정)"
    PriceSource.OFFICIAL_TARIFF -> "(공시 안내가)"
    PriceSource.NONE -> ""
}

@Composable
private fun NoChargeRow(p: NaviChargingPlan, reason: String) {
    val c = Pleos.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(horizontal = 36.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Icon(EvIcons.Directions, contentDescription = null, tint = c.accent, modifier = Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("가는 충전소 · ${p.station.name}", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reason, fontSize = TextSize.label, color = c.sub)
        }
    }
}

@Composable
private fun TripCostRow(t: TripCost, onSpeak: () -> Unit) {
    val c = Pleos.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(horizontal = 36.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Icon(EvIcons.Car, contentDescription = null, tint = c.sub, modifier = Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "이번 주행 ${Format.km(t.distanceKm)} · 전기 요금 약 ${Format.won(t.cost.toDouble())}",
                fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text,
            )
            val gas = t.gasCost?.let { " · 내연기관차라면 ${Format.won(it.toDouble())}" } ?: ""
            Text(
                "전비 ${"%.1f".format(t.efficiencyKmPerKwh)}km/kWh × 내 평균 단가 ${t.unitPrice.roundToInt()}원/kWh$gas",
                fontSize = TextSize.label, color = c.sub,
            )
        }
        TextAction("읽어 주기", onClick = onSpeak, icon = EvIcons.Speaker)
    }
}
