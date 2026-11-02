package com.akaloopang.evauto.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.stats.StationPick
import com.akaloopang.evauto.core.voice.GleoActions
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.core.voice.VoicePriceParser
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Gleo AI(플레오스 음성 비서)에서 오는 호출.
 *
 *  "글레오, 충전 기록에 이 충전소 단가 385원으로 저장해 줘"   → SET_CHARGING_UNIT_PRICE
 *  "글레오, 충전 기록에 SK일렉링크 100킬로와트 391원 저장해 줘" → SET_CHARGING_UNIT_PRICE (chargerKw=100)
 *  "글레오, 충전 기록에 10월 관리비 충전요금 47,320원 넣어 줘" → SETTLE_APARTMENT_CHARGING_BILL
 *  "글레오, 충전 기록에서 이번 달 충전비 알려 줘"             → GET_CHARGING_COST_SUMMARY
 *  "글레오, 충전 기록에서 내연기관차보다 얼마나 아꼈어?"       → GET_CHARGING_SAVINGS
 *  "글레오, 충전 기록에서 제일 싼 충전소로 안내해 줘"          → NAVIGATE_TO_CHARGING_STATION (which=cheapest)
 *  "글레오, 충전 기록에서 지금 가는 충전소 충전비 얼마야?"      → GET_EXPECTED_CHARGING_COST
 *
 * 앱 스키마: assets/schema.json (Gleo 측 검토·승인 후 활성화). 처리 결과는 Callback broadcast로 돌려준다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as EvAutoApp
        val params = GleoActions.parseParams(intent.getStringExtra(GleoActions.EXTRA_PARAM))
        val pending = goAsync()
        app.appScope.launch {
            val (status, result) = runCatching { handle(app, action, params) }
                .getOrElse {
                    Log.e(TAG, "Gleo 처리 실패", it)
                    GleoActions.Status.ERROR to VoiceActions.Result(false, "", "Unexpected internal error has occurred.")
                }
            respond(context, action, status, result)
            pending.finish()
        }
    }

    private suspend fun handle(
        app: EvAutoApp,
        action: String,
        params: Map<String, kotlinx.serialization.json.JsonPrimitive>,
    ): Pair<GleoActions.Status, VoiceActions.Result> = when (action) {
        GleoActions.SET_UNIT_PRICE -> {
            val price = GleoActions.number(params, "unitPrice")
            when {
                price == null -> GleoActions.Status.ASKING to VoiceActions.Result(false, "", "The unit price per kWh is required.")
                // 0원 = 사용자가 무료라고 한 충전소
                price == 0.0 && GleoActions.target(GleoActions.text(params, "target")) == PriceTarget.CurrentStation -> {
                    val r = app.voiceActions.markFree(null)
                    (if (r.ok) GleoActions.Status.SUCCESS else GleoActions.Status.FAIL) to r
                }
                price < VoicePriceParser.MIN_UNIT_PRICE || price > VoicePriceParser.MAX_UNIT_PRICE ->
                    GleoActions.Status.ASKING to VoiceActions.Result(
                        false, "", "The price ${VoicePriceParser.formatPrice(price)} KRW per kWh looks unusual. Please say the unit price again.",
                    )
                else -> {
                    // chargerKw(충전기 출력) > chargingSpeed(완속/급속/초급속) 순. 없으면 차량 정보·공시 요금표로 정하고,
                    // 그래도 구간마다 요금이 갈리면 ASKING으로 출력을 되묻는다 → Gleo가 사용자에게 물어 chargerKw를 채워 다시 호출
                    val cmd = VoiceCommand.SetPrice(
                        GleoActions.target(GleoActions.text(params, "target")),
                        price,
                        GleoActions.speed(params),
                    )
                    val r = app.voiceActions.applyNow(cmd)
                    when {
                        r.ok -> GleoActions.Status.SUCCESS
                        r.needsInput -> GleoActions.Status.ASKING
                        else -> GleoActions.Status.FAIL
                    } to r
                }
            }
        }
        GleoActions.SETTLE_APARTMENT_BILL -> {
            val amount = GleoActions.number(params, "amount")?.toLong()
            val month = GleoActions.text(params, "month")?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() }
            if (amount == null) GleoActions.Status.ASKING to VoiceActions.Result(false, "", "The bill amount for apartment charging is required.")
            else app.voiceActions.settleBill(amount, month).let { r -> (if (r.ok) GleoActions.Status.SUCCESS else GleoActions.Status.FAIL) to r }
        }
        GleoActions.GET_COST_SUMMARY -> {
            val month = GleoActions.text(params, "month")?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() }
            GleoActions.Status.SUCCESS to app.voiceActions.summary(month)
        }
        GleoActions.GET_SAVINGS -> {
            val month = GleoActions.text(params, "month")?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() }
            app.voiceActions.savings(month).let { r -> (if (r.ok) GleoActions.Status.SUCCESS else GleoActions.Status.FAIL) to r }
        }
        GleoActions.NAVIGATE_TO_STATION -> {
            val name = GleoActions.text(params, "stationName")
            val pick = StationPick.of(GleoActions.text(params, "which")) ?: if (name != null) StationPick.NAMED else StationPick.MOST_VISITED
            val r = app.voiceActions.navigate(pick, name) { st ->
                val at = st.location
                at != null && app.navi.routeTo(at.lat, at.lon, st.displayName, st.address)
            }
            when {
                r.ok -> GleoActions.Status.SUCCESS
                r.needsInput -> GleoActions.Status.ASKING
                else -> GleoActions.Status.FAIL
            } to r
        }
        GleoActions.GET_EXPECTED_COST -> app.voiceActions.expectedCost(app.assistant.expected.value)
            .let { r -> (if (r.ok) GleoActions.Status.SUCCESS else GleoActions.Status.FAIL) to r }
        else -> GleoActions.Status.FAIL to VoiceActions.Result(false, "", "This action is not supported.")
    }

    private fun respond(context: Context, functionName: String, status: GleoActions.Status, r: VoiceActions.Result) {
        val response = GleoActions.callback(functionName, status, r.englishMessage, if (r.data.isEmpty()) emptyList() else listOf(r.data))
        Intent(GleoActions.RESPONSE_ACTION).apply {
            putExtra("result", status.key)
            putExtra("packageName", context.packageName)
            putExtra("response", response)
        }.also { context.sendBroadcast(it) }
        Log.d(TAG, "Gleo 응답: $response")
    }

    companion object {
        private const val TAG = "GleoActionReceiver"
    }
}
