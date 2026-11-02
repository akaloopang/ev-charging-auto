package com.akaloopang.evauto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 화면에 쓰는 아이콘 (Material Icons 모양, Apache 2.0). 아이콘 라이브러리 의존성을 늘리지 않으려고 경로만 담았다.
 * Icon(tint = …)으로 색을 입힌다.
 */
object EvIcons {
    val Dashboard: ImageVector by lazy { icon("Dashboard", "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z") }
    val History: ImageVector by lazy { icon("History", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z") }
    val Apartment: ImageVector by lazy {
        icon(
            "Apartment",
            "M17,11V3H7v4H3v14h8v-4h2v4h8V11H17zM7,19H5v-2h2V19zM7,15H5v-2h2V15zM7,11H5V9h2V11zM11,15H9v-2h2V15zM11,11H9V9h2V11zM11,7H9V5h2V7zM15,15h-2v-2h2V15zM15,11h-2V9h2V11zM15,7h-2V5h2V7zM19,19h-2v-2h2V19zM19,15h-2v-2h2V15z",
        )
    }
    val Bolt: ImageVector by lazy { icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z") }
    val Place: ImageVector by lazy { icon("Place", "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13C19,5.13 15.87,2 12,2zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5S13.38,11.5 12,11.5z") }
    val Car: ImageVector by lazy {
        icon(
            "Car",
            "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11C5.84,5 5.29,5.42 5.08,6.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8L18.92,6.01zM6.5,16C5.67,16 5,15.33 5,14.5S5.67,13 6.5,13 8,13.67 8,14.5 7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5S18.33,16 17.5,16zM5,11l1.5,-4.5h11L19,11H5z",
        )
    }
    val Build: ImageVector by lazy {
        icon(
            "Build",
            "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3C23.1,20 23.1,19.3 22.7,19z",
        )
    }
    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6C9,12.66 10.34,14 12,14zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72H17.3z",
        )
    }
    val Receipt: ImageVector by lazy {
        icon(
            "Receipt",
            "M18,17H6v-2h12V17zM18,13H6v-2h12V13zM18,9H6V7h12V9zM3,22l1.5,-1.5L6,22l1.5,-1.5L9,22l1.5,-1.5L12,22l1.5,-1.5L15,22l1.5,-1.5L18,22l1.5,-1.5L21,22V2l-1.5,1.5L18,2l-1.5,1.5L15,2l-1.5,1.5L12,2l-1.5,1.5L9,2L7.5,3.5L6,2L4.5,3.5L3,2V22z",
        )
    }
    val Check: ImageVector by lazy { icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19L21,7l-1.41,-1.41z") }
    val Edit: ImageVector by lazy {
        icon(
            "Edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75L20.71,7.04z",
        )
    }
    val Delete: ImageVector by lazy { icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6V19zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }
    val Download: ImageVector by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7L19,9zM5,18v2h14v-2H5z") }
    val Info: ImageVector by lazy {
        icon("Info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2V17zM13,9h-2V7h2V9z")
    }
    val Chart: ImageVector by lazy { icon("Chart", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z") }
    val Savings: ImageVector by lazy {
        icon(
            "Savings",
            "M15,10c0,-0.55 0.45,-1 1,-1s1,0.45 1,1 -0.45,1 -1,1S15,10.55 15,10zM8,9h5V7H8V9zM22,7.5v6.97l-2.82,0.94L17.5,21H12v-2h-2v2H4.5C4.5,21 2,12.54 2,9.5S4.46,4 7.5,4h5c0.91,-1.21 2.36,-2 4,-2C17.33,2 18,2.67 18,3.5c0,0.21 -0.04,0.4 -0.12,0.58C17.74,4.42 17.62,4.81 17.56,5.23l2.27,2.27H22z",
        )
    }
    val Directions: ImageVector by lazy {
        icon("Directions", "M21.71,11.29l-9,-9c-0.39,-0.39 -1.02,-0.39 -1.41,0l-9,9c-0.39,0.39 -0.39,1.02 0,1.41l9,9c0.39,0.39 1.02,0.39 1.41,0l9,-9c0.39,-0.38 0.39,-1.01 0,-1.41zM14,14.5V12h-4v3H8v-4c0,-0.55 0.45,-1 1,-1h5V7.5l3.5,3.5 -3.5,3.5z")
    }
    val Speaker: ImageVector by lazy {
        icon("Speaker", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    }
    val Refresh: ImageVector by lazy {
        icon("Refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    }
    val Swap: ImageVector by lazy { icon("Swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99V11zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z") }
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
