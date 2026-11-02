package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.TierRange
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.VoicePriceParser
import com.akaloopang.evauto.voice.VoicePriceSession
import com.akaloopang.evauto.voice.VoicePriceSession.Mode
import com.akaloopang.evauto.voice.VoicePriceSession.State

/**
 * 말로 단가 입력 화면. 주행 중에도 손을 쓰지 않고 쓸 수 있게 큰 글씨·최소 버튼만 둔다.
 * [recordId]가 있으면 그 충전 기록의 충전소, 없으면 최근 공용 충전소(또는 [target])에 적용한다.
 * 충전기 출력을 물을 때는 출력 구간 버튼도 보여 준다(정차 중이면 눌러서 고르는 게 더 빠르다).
 */
@Composable
fun VoicePriceDialog(
    app: EvAutoApp,
    recordId: String?,
    target: PriceTarget = PriceTarget.CurrentStation,
    suggest: Boolean = true,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(recordId, target) {
        VoicePriceSession(app.speech, app.voiceActions, scope, recordId, target, app.repo.tariff, app.repo.price.value.providers, suggest)
    }
    val state by session.state.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.start() }
    DisposableEffect(session) { onDispose { app.speech.stopAll() } }
    val c = Pleos.colors

    PleosDialog(
        title = "말로 단가 입력",
        onDismiss = { session.cancel(); onDismiss() },
        icon = EvIcons.Mic,
        actions = {
            when (state) {
                is State.Confirming -> {
                    SecondaryButton("다시 말하기", onClick = { session.retry() }, icon = EvIcons.Mic)
                    SecondaryButton("닫기", onClick = { session.cancel(); onDismiss() })
                    PrimaryButton("저장", onClick = { session.confirm() }, icon = EvIcons.Check)
                }
                is State.Suggesting -> {
                    SecondaryButton("닫기", onClick = { session.cancel(); onDismiss() })
                    SecondaryButton("아니에요", onClick = { session.rejectSuggestion() })
                    PrimaryButton(
                        "맞아요 · ${VoicePriceParser.formatPrice((state as State.Suggesting).suggestion.unitPrice)}원",
                        onClick = { session.acceptSuggestion() }, icon = EvIcons.Check,
                    )
                }
                is State.Done -> PrimaryButton("닫기", onClick = onDismiss)
                is State.Failed -> {
                    SecondaryButton("닫기", onClick = { session.cancel(); onDismiss() })
                    PrimaryButton("다시 말하기", onClick = { session.retry() }, icon = EvIcons.Mic)
                }
                else -> {
                    SecondaryButton("닫기", onClick = { session.cancel(); onDismiss() })
                    SecondaryButton("그만", onClick = { session.cancel() })
                }
            }
        },
    ) {
        when (val s = state) {
            State.Idle -> BigLine("준비 중…")
            is State.Speaking -> BigLine(s.text)
            is State.Listening -> {
                val hint = when (s.mode) {
                    Mode.PRICE -> "듣고 있어요…"
                    Mode.CONFIRM -> "저장할까요? \"응\" 또는 \"아니\""
                    Mode.KW -> "충전기 출력을 말씀하거나 아래에서 고르세요"
                    Mode.SUGGEST -> "\"응\" 또는 금액을 말씀해 주세요"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(EvIcons.Mic, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(44.dp))
                    }
                    Text(hint, fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.accent)
                }
                if (s.partial.isNotBlank()) Text("\"${s.partial}\"", fontSize = TextSize.headline, fontWeight = FontWeight.Bold, color = c.text)
                if (s.mode == Mode.KW) TierButtons(s.candidates, session)
            }
            is State.AskingKw -> {
                BigLine(s.question)
                TierButtons(s.candidates, session)
            }
            is State.Confirming -> {
                BigLine(s.ready.question)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${VoicePriceParser.formatPrice(s.ready.cmd.unitPrice)}원/kWh", fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text)
                    if (s.ready.tiers.isNotEmpty()) Text("적용 구간: ${TierRange.label(s.ready.tiers)}", fontSize = TextSize.title, color = c.sub)
                }
            }
            is State.Suggesting -> {
                val sg = s.suggestion
                Text("${sg.station} 단가, 이걸로 할까요?", fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.text)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${VoicePriceParser.formatPrice(sg.unitPrice)}원/kWh", fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text)
                    Text(sg.shown, fontSize = TextSize.title, color = c.sub, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("충전기 화면이나 영수증의 단가와 같으면 '맞아요', 다르면 금액을 말씀해 주세요.", fontSize = TextSize.label, color = c.sub)
                if (s.listening) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(EvIcons.Mic, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(40.dp))
                        }
                        Text("\"응\" 또는 금액을 말씀해 주세요", fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.accent)
                    }
                    if (s.partial.isNotBlank()) Text("\"${s.partial}\"", fontSize = TextSize.headline, fontWeight = FontWeight.Bold, color = c.text)
                }
            }
            is State.Done -> BigLine(s.message, color = if (s.ok) c.ok else c.danger)
            is State.Failed -> BigLine(s.message)
        }
    }
}

@Composable
private fun BigLine(text: String, color: androidx.compose.ui.graphics.Color = Pleos.colors.text) {
    Text(text, fontSize = TextSize.section, fontWeight = FontWeight.Medium, color = color)
}

/** 출력 구간 고르기 칩 + [출력 모름] */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierButtons(candidates: List<PowerTier>, session: VoicePriceSession) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        candidates.forEach { t -> ChoiceChip(t.label, selected = false, onClick = { session.chooseTier(t) }) }
        ChoiceChip("출력 모름", selected = false, onClick = { session.dontKnowKw() })
    }
}
