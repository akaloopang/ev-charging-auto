package com.akaloopang.evauto.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val app get() = application as EvAutoApp

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { app.monitor.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsThenStart()
        setContent {
            PleosTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Pleos.colors.bg) { AppRoot(app) }
            }
        }
    }

    private fun requestPermissionsThenStart() {
        val wanted = buildList {
            add(CAR_ENERGY)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) app.monitor.start() else permissionLauncher.launch(wanted.toTypedArray())
    }

    companion object {
        /** AAOS 런타임 권한 — 충전 상태·출력·배터리 잔량(Wh) */
        private const val CAR_ENERGY = "android.car.permission.CAR_ENERGY"
    }
}

/** 왼쪽 메뉴 (Pleos 설정 화면처럼 한 줄 목록, 선택하면 검정 반전) */
enum class Dest(val label: String, val settings: Boolean = false) {
    HOME("홈"),
    RECORDS("충전 기록"),
    STATS("통계"),
    HOME_CHARGING("집 충전", settings = true),
    PROVIDER("사업자 단가", settings = true),
    STATION("충전소 단가", settings = true),
    VEHICLE("차량·계산", settings = true),
    DEMO("시연", settings = true),
}

private fun Dest.icon(): ImageVector = when (this) {
    Dest.HOME -> EvIcons.Dashboard
    Dest.RECORDS -> EvIcons.History
    Dest.STATS -> EvIcons.Chart
    Dest.HOME_CHARGING -> EvIcons.Apartment
    Dest.PROVIDER -> EvIcons.Bolt
    Dest.STATION -> EvIcons.Place
    Dest.VEHICLE -> EvIcons.Car
    Dest.DEMO -> EvIcons.Build
}

/** release에서 시연 메뉴 열기 — 메뉴 맨 아래 버전을 5번 누른다 (앱이 살아 있는 동안만) */
internal object DevMode {
    var taps = 0
    var on by mutableStateOf(false)
}

@Composable
fun AppRoot(app: EvAutoApp) {
    val restricted by app.driving.restricted.collectAsStateWithLifecycle()
    // 공용 충전이 끝났는데 확인 단가가 없으면 말로 물어본다 (주행 중에도 손 안 대고 답할 수 있다)
    val voicePrompt by app.monitor.voicePrompt.collectAsStateWithLifecycle()
    // 앱을 여는 순간 띄운 대화상자 창은 차량 화면에서 보이지 않은 채 터치를 가로챌 수 있어, 첫 화면이 뜬 뒤에 띄운다
    var windowReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1_500); windowReady = true }
    if (windowReady) voicePrompt?.let { id ->
        VoicePriceDialog(app, recordId = id) { app.monitor.voicePromptShown() }
    }
    if (restricted) {
        // 주행 중: 요약만. 입력·목록은 정차(P단) 후에. 단가는 말로 입력할 수 있다.
        DrivingSummary(app)
        return
    }

    // 처음 한 번: 집 충전 방식과 아파트 단가 (앱 화면 한 페이지로)
    val car by app.repo.car.collectAsStateWithLifecycle()
    if (!car.setupDone) {
        SetupScreen(app)
        return
    }

    val context = LocalContext.current
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }
    val items = Dest.entries.filter { it != Dest.DEMO || debuggable || DevMode.on }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 넓으면 아이콘 + 글자 메뉴, 좁으면(멀티 윈도우 세로 화면) 아이콘 + 작은 글자
        val wide = maxWidth >= 1100.dp
        Row(Modifier.fillMaxSize()) {
            NavRail(
                items = items, selected = dest, wide = wide,
                version = version,
                onSelect = { dest = it },
                onVersionTap = { if (++DevMode.taps >= 5) DevMode.on = true },
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(Pleos.colors.line))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (dest) {
                    Dest.HOME -> HomeScreen(app, onOpenSettings = { dest = Dest.HOME_CHARGING }, onOpenRecords = { dest = Dest.RECORDS })
                    Dest.RECORDS -> RecordsScreen(app)
                    Dest.STATS -> StatsScreen(app)
                    Dest.HOME_CHARGING -> HomeChargingPage(app)
                    Dest.PROVIDER -> ProviderPricePage(app)
                    Dest.STATION -> StationPricePage(app)
                    Dest.VEHICLE -> VehicleCalcPage(app)
                    Dest.DEMO -> DemoPage(app)
                }
            }
        }
    }
}

@Composable
private fun NavRail(
    items: List<Dest>,
    selected: Dest,
    wide: Boolean,
    version: String,
    onSelect: (Dest) -> Unit,
    onVersionTap: () -> Unit,
) {
    val c = Pleos.colors
    Column(
        Modifier
            .width(if (wide) Dimen.railWide else Dimen.railCompact)
            .fillMaxHeight()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 20.dp),
    ) {
        items.forEachIndexed { i, d ->
            // 설정 메뉴 앞에 작은 구분 제목
            if (d.settings && (i == 0 || !items[i - 1].settings)) {
                Spacer(Modifier.height(20.dp))
                if (wide) {
                    Text("설정", fontSize = TextSize.caption, color = c.sub, modifier = Modifier.padding(start = 36.dp, bottom = 8.dp))
                } else {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp).height(1.dp).background(c.line))
                }
            }
            val sel = d == selected
            val fg = if (sel) c.onInverse else c.text
            if (wide) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimen.railItemH)
                        .background(if (sel) c.inverse else c.bg)
                        .clickable { onSelect(d) }
                        .padding(horizontal = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(d.icon(), contentDescription = null, tint = fg, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(24.dp))
                    Text(d.label, fontSize = TextSize.rail, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = fg)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimen.railItemH + 16.dp)
                        .background(if (sel) c.inverse else c.bg)
                        .clickable { onSelect(d) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(d.icon(), contentDescription = d.label, tint = fg, modifier = Modifier.size(40.dp))
                    Text(d.label, fontSize = 20.sp, color = fg, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            if (wide) "EV충전 기록 Auto $version" else version,
            fontSize = TextSize.caption,
            color = c.sub,
            modifier = Modifier.clickable(onClick = onVersionTap).padding(horizontal = if (wide) 36.dp else 20.dp, vertical = 16.dp),
        )
    }
}
