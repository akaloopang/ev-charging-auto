package com.akaloopang.evauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.voice.PriceTarget
import kotlinx.coroutines.launch

private enum class HomeMode { APARTMENT, OPERATOR, NONE }

/**
 * 처음 설정 — 앱을 처음 열면 한 번. 집에서 어떻게 충전하는지와 아파트 단가만 묻는다.
 * 대화상자(별도 창)가 아니라 앱 화면 한 페이지로 그린다 — 차량 화면에서 앱을 여는 순간 띄운 대화상자 창은
 * 보이지 않은 채 터치만 가로챌 수 있어서다.
 * 아파트 충전은 영수증이 없어서 단가를 모르면 금액을 계산할 수 없다(추측하지 않는다). 그래서 처음에 받는다.
 * 고지서 단가를 아직 모르면 비워 두고 시작할 수 있다 — 나중에 관리비 고지서로 원 단위까지 맞춘다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(app: EvAutoApp) {
    val scope = rememberCoroutineScope()
    val car by app.repo.car.collectAsStateWithLifecycle()
    val price by app.repo.price.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(if (car.homeProvider == ProviderCatalog.APARTMENT) HomeMode.APARTMENT else HomeMode.OPERATOR) }
    var operator by remember { mutableStateOf(car.homeProvider.takeIf { it != ProviderCatalog.APARTMENT }) }
    var ac by remember { mutableStateOf(price.apartmentAcUnitPrice.asInput()) }
    var dc by remember { mutableStateOf(price.apartmentDcUnitPrice.asInput()) }
    var voice by remember { mutableStateOf(false) }
    // 말로 넣은 아파트 단가를 칸에도 보여 준다
    LaunchedEffect(price.apartmentAcUnitPrice) { price.apartmentAcUnitPrice?.let { ac = it.asInput() } }
    if (voice) VoicePriceDialog(app, recordId = null, target = PriceTarget.Apartment) { voice = false }

    fun finish() = scope.launch {
        when (mode) {
            HomeMode.APARTMENT -> {
                app.repo.saveCar(car.copy(homeProvider = ProviderCatalog.APARTMENT, homeCharging = true, setupDone = true))
                val a = ac.toDoubleOrNullTrim()?.takeIf { it > 0 }
                val d = dc.toDoubleOrNullTrim()?.takeIf { it > 0 }
                if (a != price.apartmentAcUnitPrice || d != price.apartmentDcUnitPrice) {
                    app.repo.savePrice(price.copy(apartmentAcUnitPrice = a, apartmentDcUnitPrice = d))
                }
            }
            HomeMode.OPERATOR -> app.repo.saveCar(
                car.copy(homeProvider = operator ?: ProviderCatalog.APARTMENT, homeCharging = true, setupDone = true),
            )
            HomeMode.NONE -> app.repo.saveCar(car.copy(homeCharging = false, setupDone = true))
        }
    }

    PageColumn {
        PageHeader("처음 설정", subtitle = "한 번만 정하면 돼요. 나중에 '집 충전' 메뉴에서 바꿀 수 있어요.")
        Text("집에서는 어떻게 충전하세요?", fontSize = TextSize.section, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoiceChip("아파트 충전기 (관리비로 청구)", selected = mode == HomeMode.APARTMENT, onClick = { mode = HomeMode.APARTMENT })
            ChoiceChip("집에 단 사업자 충전기", selected = mode == HomeMode.OPERATOR, onClick = { mode = HomeMode.OPERATOR })
            ChoiceChip("집에서는 충전 안 해요", selected = mode == HomeMode.NONE, onClick = { mode = HomeMode.NONE })
        }
        when (mode) {
            HomeMode.APARTMENT -> {
                Text(
                    "관리비 고지서의 전기차 충전 단가(원/kWh)를 넣어 주세요. 앱이 꺼져 있던 밤사이 충전도 이 단가로 계산해요. " +
                        "나중에 단가가 바뀌어도 지난 기록은 그때 단가 그대로예요.",
                    fontSize = TextSize.body, color = Pleos.colors.sub,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField("완속 (원/kWh)", ac, { ac = it }, Modifier.weight(1f))
                    NumberField("급속 (원/kWh) · 있으면", dc, { dc = it }, Modifier.weight(1f))
                    SecondaryButton("말로 입력", onClick = { voice = true }, icon = EvIcons.Mic)
                }
                Text("고지서를 아직 모르면 비워 두세요. 고지서가 나오면 '관리비 고지서 정산'으로 원 단위까지 맞출 수 있어요.", fontSize = TextSize.label, color = Pleos.colors.sub)
            }
            HomeMode.OPERATOR -> {
                Text("어느 사업자 충전기인가요? 그 사업자 요금으로 계산해요.", fontSize = TextSize.body, color = Pleos.colors.sub)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    price.providers.filter { it != ProviderCatalog.APARTMENT && it != ProviderCatalog.OTHER }.forEach { p ->
                        ChoiceChip(p, selected = operator == p, onClick = { operator = p })
                    }
                }
            }
            HomeMode.NONE -> Text(
                "공용 충전소 충전만 기록해요. 처음 가는 충전소는 단가를 한 번만 확인하면 다음부터 자동으로 계산돼요.",
                fontSize = TextSize.body, color = Pleos.colors.sub,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "시작하기", icon = EvIcons.Check,
                enabled = mode != HomeMode.OPERATOR || operator != null,
                onClick = { finish() },
            )
            TextAction("나중에", onClick = { scope.launch { app.repo.saveCar(car.copy(setupDone = true)) } }, color = Pleos.colors.sub)
        }
    }
}
