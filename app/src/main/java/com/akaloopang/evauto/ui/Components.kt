package com.akaloopang.evauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.akaloopang.evauto.core.model.AmountStatus

// ── 페이지 뼈대 ───────────────────────────────────────

/** 스크롤되는 한 페이지 (Pleos 설정 화면처럼 큰 제목 + 구역) */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimen.pagePadH, vertical = Dimen.pagePadV),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        content = content,
    )
}

val PagePadding = PaddingValues(horizontal = Dimen.pagePadH, vertical = Dimen.pagePadV)

/** 페이지 큰 제목 + 한 줄 설명 */
@Composable
fun PageHeader(title: String, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = Pleos.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = TextSize.headline, fontWeight = FontWeight.Bold, color = c.text)
            if (subtitle != null) Text(subtitle, fontSize = TextSize.label, color = c.sub)
        }
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 구역: 제목 + 회색 설명 + 내용 (Pleos의 '선호 충전소', '경로' 구역) */
@Composable
fun Section(
    title: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Pleos.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (title != null || description != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title != null) Text(title, fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.text)
                if (description != null) Text(description, fontSize = TextSize.caption, color = c.sub)
            }
        }
        content()
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(color = Pleos.colors.line, thickness = 1.dp)
}

/** 목록 위 소제목 (예: '확인이 필요한 충전 2건') */
@Composable
fun SectionTitle(text: String, count: Int? = null) {
    val c = Pleos.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text, fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.text)
        if (count != null) CountBadge(count)
    }
}

@Composable
fun CountBadge(n: Int) {
    val c = Pleos.colors
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(c.inverse).padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text("$n", fontSize = TextSize.caption, fontWeight = FontWeight.Bold, color = c.onInverse) }
}

// ── 버튼·칩 ──────────────────────────────────────────

/** 검정(밤에는 흰색) 채운 주 버튼 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = Pleos.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Dimen.buttonH),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.inverse, contentColor = c.onInverse),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
    ) { ButtonContent(text, icon) }
}

/** 테두리 버튼 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = Pleos.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Dimen.buttonH),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) c.line else c.line.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
    ) { ButtonContent(text, icon) }
}

/** 글자만 있는 버튼 (보조 동작) */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = Pleos.colors.accent, icon: ImageVector? = null) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = Dimen.buttonH),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
    }
    Text(text, fontSize = TextSize.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Pleos '선호 충전소' 칩: 선택하면 검정 반전 */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pleos.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .heightIn(min = Dimen.chipH)
            .clip(shape)
            .background(if (selected) c.inverse else c.bg)
            .border(2.dp, if (selected) c.inverse else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = TextSize.label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) c.onInverse else if (enabled) c.text else c.sub,
            maxLines = 1,
        )
    }
}

// ── 값 표시 ──────────────────────────────────────────

/** 수치 타일: 작은 이름 + 큰 값 + 보조 줄 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, unit: String? = null) {
    val c = Pleos.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.tile).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = TextSize.label, fontWeight = FontWeight.Medium, color = c.sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = TextSize.display, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
            if (unit != null) Text(unit, fontSize = TextSize.title, color = c.text, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
        if (sub != null) Text(sub, fontSize = TextSize.caption, color = c.sub)
    }
}

/** 이름 — 값 한 줄 (상세 화면) */
@Composable
fun LabelValue(label: String, value: String, big: Boolean = false, labelWidth: Dp = 320.dp) {
    val c = Pleos.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = TextSize.label, color = c.sub, modifier = Modifier.width(labelWidth))
        Text(
            value,
            fontSize = if (big) TextSize.section else TextSize.body,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Medium,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 금액 상태 — 추정 / 관리비 정산 / 영수증 / 무료(무료 충전소) */
@Composable
fun StatusChip(status: AmountStatus, free: Boolean = false) {
    val c = Pleos.colors
    val (text, color) = when {
        // 무료 충전소: 0원이 확실하니 '추정'으로 보이지 않게
        free -> "무료" to c.ok
        else -> when (status) {
            AmountStatus.ESTIMATED -> "추정" to c.warn
            AmountStatus.BILL_SETTLED -> "관리비 정산" to c.ok
            AmountStatus.RECEIPT -> "영수증" to c.info
        }
    }
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Text(text, color = color, fontSize = TextSize.caption, fontWeight = FontWeight.Bold) }
}

/** 굵은 진행 막대 (충전량) */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 22.dp, color: Color = Pleos.colors.accent) {
    val c = Pleos.colors
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(color))
    }
}

/** 안내 띠: 아이콘 + 문장 + 버튼 */
@Composable
fun InfoBanner(text: String, actionText: String? = null, onAction: () -> Unit = {}, icon: ImageVector = EvIcons.Info) {
    val c = Pleos.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(44.dp))
        Text(text, fontSize = TextSize.body, color = c.text, modifier = Modifier.weight(1f))
        if (actionText != null) PrimaryButton(actionText, onAction)
    }
}

/** 빈 화면 안내 */
@Composable
fun EmptyState(title: String, body: String, icon: ImageVector = EvIcons.Bolt) {
    val c = Pleos.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = c.sub, modifier = Modifier.size(72.dp))
        Text(title, fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(body, fontSize = TextSize.body, color = c.sub)
    }
}

/** 예전 화면 호환용 카드 (타일 바탕) */
@Composable
fun BigCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Pleos.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (title != null) Text(title, fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text)
        content()
    }
}

// ── 입력 ─────────────────────────────────────────────

/** 숫자 입력 칸 (큰 글씨). [hint]는 칸 아래 회색 안내(예: 공시가) */
@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        supportingText = if (hint != null) { { Text(hint) } } else null,
        textStyle = TextStyle(fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

fun String.toDoubleOrNullTrim(): Double? = trim().toDoubleOrNull()

fun Double?.asInput(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> this.toLong().toString()
    else -> this.toString()
}

// ── 대화상자 ─────────────────────────────────────────

/**
 * 큰 대화상자 (기본 AlertDialog는 차량 화면에서 좁고 글씨가 작다).
 * [actions]는 오른쪽 아래에 놓인다.
 */
@Composable
fun PleosDialog(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Pleos.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 차량 화면(멀티 디스플레이)에서는 시스템 배경 어둡게 하기가 적용되지 않아 흰 화면 위에 흰 상자가 묻힌다.
        // 그래서 직접 어두운 막을 깔고, 막을 누르면 닫는다.
        val noRipple = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = c.bg,
            shadowElevation = 16.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
            // 화면 양옆 여백만 남기고 최대 1200dp (차량 화면 기준 약 70%)
            modifier = Modifier
                .padding(horizontal = 64.dp, vertical = 32.dp)
                .widthIn(max = 1200.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* 상자 안을 눌러도 닫히지 않게 */ },
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (icon != null) Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(52.dp))
                    Text(title, fontSize = TextSize.dialogTitle, fontWeight = FontWeight.Bold, color = c.text)
                }
                content()
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        }
    }
}
