package com.akaloopang.evauto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 시스템 화면(설정·충전소)과 같은 느낌의 디자인.
 *  - 흰 바탕(밤에는 어두운 바탕) + 검정 글자, 선택된 항목은 검정 반전
 *  - 글자는 크게: 차량 화면은 팔 길이만큼 떨어져서 보므로 Pleos 시스템 화면과 같은 크기감(본문 32sp, 제목 52sp — 설명 글자도 26sp 이상)
 *  - 카드 상자보다 구역 제목 + 구분선으로 나눠 빈 공간 없이 채운다
 * 낮/밤은 시스템 설정(차량 조명 모드)을 따른다.
 */
@Immutable
data class PleosColors(
    val bg: Color,
    /** 수치 타일·카드 바탕 */
    val tile: Color,
    val line: Color,
    val text: Color,
    /** 보조 설명 글자 */
    val sub: Color,
    /** 선택된 메뉴·주 버튼 (검정 반전) */
    val inverse: Color,
    val onInverse: Color,
    /** 충전·진행 강조색 */
    val accent: Color,
    val accentSoft: Color,
    val warn: Color,
    val ok: Color,
    val info: Color,
    val danger: Color,
)

private val LightPleos = PleosColors(
    bg = Color(0xFFFFFFFF),
    tile = Color(0xFFF3F4F6),
    line = Color(0xFFDDDFE3),
    text = Color(0xFF111111),
    sub = Color(0xFF6B6F76),
    inverse = Color(0xFF111111),
    onInverse = Color(0xFFFFFFFF),
    accent = Color(0xFF1F6FEB),
    accentSoft = Color(0xFFEAF2FF),
    warn = Color(0xFFB45309),
    ok = Color(0xFF047857),
    info = Color(0xFF1D4ED8),
    danger = Color(0xFFDC2626),
)

private val DarkPleos = PleosColors(
    bg = Color(0xFF111214),
    tile = Color(0xFF1D1F23),
    line = Color(0xFF35373D),
    text = Color(0xFFF3F4F6),
    sub = Color(0xFFA2A6AD),
    inverse = Color(0xFFF3F4F6),
    onInverse = Color(0xFF111111),
    accent = Color(0xFF5B9BFF),
    accentSoft = Color(0xFF1A2A44),
    warn = Color(0xFFFBBF24),
    ok = Color(0xFF34D399),
    info = Color(0xFF60A5FA),
    danger = Color(0xFFF87171),
)

val LocalPleos = staticCompositionLocalOf { LightPleos }

object Pleos {
    val colors: PleosColors
        @Composable @ReadOnlyComposable get() = LocalPleos.current
}

/** 간격·크기 (Pleos 설정 화면 기준) */
object Dimen {
    val pagePadH = 56.dp
    val pagePadV = 40.dp
    val sectionGap = 40.dp
    val buttonH = 84.dp
    val chipH = 76.dp
    val railWide = 340.dp
    val railCompact = 132.dp
    val railItemH = 100.dp
}

/** 글자 크기 — 화면 곳곳에서 같은 크기를 쓰도록 이름을 붙인다 */
object TextSize {
    val hero = 120.sp
    val display = 64.sp
    val headline = 52.sp
    val dialogTitle = 46.sp
    val section = 38.sp
    val title = 34.sp
    val body = 32.sp
    val label = 30.sp
    val caption = 26.sp
    /** 왼쪽 메뉴 글자 (Pleos 설정 메뉴와 같은 크기) */
    val rail = 32.sp
}

private val PleosType = Typography(
    displayLarge = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Bold, lineHeight = 128.sp),
    headlineLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp),
    headlineMedium = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp),
    headlineSmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp),
    titleLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.SemiBold, lineHeight = 48.sp),
    titleMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 44.sp),
    titleSmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
    bodyLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp),
    bodyMedium = TextStyle(fontSize = 30.sp, lineHeight = 40.sp),
    bodySmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    labelLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
    labelMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp),
    labelSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
)

@Composable
fun PleosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkPleos else LightPleos
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.inverse, onPrimary = c.onInverse,
            secondary = c.accent, onSecondary = c.onInverse,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.tile, onSurfaceVariant = c.sub,
            surfaceContainer = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.tile,
            surfaceContainerLow = c.bg, surfaceContainerLowest = c.bg,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.inverse, onPrimary = c.onInverse,
            secondary = c.accent, onSecondary = c.onInverse,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.tile, onSurfaceVariant = c.sub,
            surfaceContainer = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.tile,
            surfaceContainerLow = c.bg, surfaceContainerLowest = c.bg,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalPleos provides c) {
        MaterialTheme(colorScheme = scheme, typography = PleosType, content = content)
    }
}
