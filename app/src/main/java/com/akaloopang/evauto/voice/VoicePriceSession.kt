package com.akaloopang.evauto.voice

import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.SpeedAnswer
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.core.voice.VoicePriceParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 말로 단가 입력 한 번의 대화.
 *
 * 방금 충전한 충전소 (차가 출력 구간을 안다 → 속도는 묻지 않는다):
 *   앱: "SK일렉링크 판교점 단가를 말씀해 주세요"  →  사용자: "삼백팔십오 원"
 *   앱: "SK일렉링크 판교점 100에서 200킬로와트 충전기, 킬로와트시당 385원으로 저장할까요?"  →  "응"
 *
 * 구간마다 요금이 달라 갈리지 않을 때만 출력을 묻는다:
 *   사용자: "SK일렉링크 급속 350원"
 *   앱: "SK일렉링크 요금은 충전기 출력마다 달라요. 충전기에 적힌 출력이 몇 킬로와트인가요?"  →  "백"
 *   앱: "SK일렉링크 100에서 200킬로와트 충전기, 킬로와트시당 350원으로 저장할까요?"  →  "응"
 *
 * 처음 간 충전소인데 짐작한 단가가 있으면(같은 사업자 다른 충전소에서 확인한 단가 > 공시가) 먼저 묻는다:
 *   앱: "SK일렉링크 비비동점 단가를 지난번 에이에이동점처럼 420원으로 할까요? 맞으면 '응', 다르면 금액을 말씀해 주세요"
 *   사용자: "응" → 저장 끝  /  "사백삼십 원" → 평소처럼 확인 후 저장  /  "아니" → 단가를 묻는다
 *
 * 단가는 그 구간 칸 하나에만 넣는다(구간·충전기별 프로모션이 있을 수 있다).
 * (선택) 요금을 받지 않는 곳이면 "무료야"라고 해도 무료 충전소로 저장한다(다음부터 0원, 묻지 않음).
 *
 * 숫자 오인식에 대비해 저장 전에 반드시 어느 구간·얼마인지 읽어 주고 확인받는다. 범위 밖 값은 다시 묻는다(최대 2번).
 * 확인 단계에서 다른 값을 말하면 그 값으로 다시 확인한다. 화면에서는 버튼으로 출력 구간을 고를 수도 있다.
 */
class VoicePriceSession(
    private val speech: PleosSpeech,
    private val actions: VoiceActions,
    private val scope: CoroutineScope,
    private val recordId: String?,
    private val defaultTarget: PriceTarget,
    private val tariff: TariffTable?,
    private val providers: List<String> = ProviderCatalog.officialProviders,
    /** 짐작한 단가가 있으면 "○○원으로 할까요?"부터 묻는다. 카드의 '말로 고치기'처럼 이미 아니라고 한 경우는 false */
    private val suggest: Boolean = true,
) {
    enum class Mode { PRICE, CONFIRM, KW, SUGGEST }

    sealed interface State {
        data object Idle : State
        data class Speaking(val text: String) : State
        /** @param candidates [Mode.KW]일 때 화면에 버튼으로 보여 줄 출력 구간 */
        data class Listening(val partial: String, val mode: Mode, val candidates: List<PowerTier> = emptyList()) : State
        data class AskingKw(val question: String, val candidates: List<PowerTier>) : State
        data class Confirming(val ready: VoiceActions.Planned.Ready) : State
        /** "○○원으로 할까요?" — 화면에서는 [맞아요] 버튼으로도 답할 수 있다 */
        data class Suggesting(val suggestion: VoiceActions.Suggestion, val partial: String = "", val listening: Boolean = false) : State
        data class Done(val message: String, val ok: Boolean) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var rejects = 0
    private var kwAsks = 0
    private var pending: VoiceActions.Planned.Ready? = null
    private var pendingKw: VoiceActions.Planned.NeedKw? = null
    private var suggestion: VoiceActions.Suggestion? = null

    private val targetName: String
        get() = when (defaultTarget) {
            PriceTarget.CurrentStation -> actions.stationName(actions.stationRecordId(recordId)) ?: "이 충전소"
            PriceTarget.Apartment -> "아파트 충전"
            is PriceTarget.Provider -> defaultTarget.name
        }

    fun start() {
        // 짐작한 단가가 있으면 "○○원으로 할까요?"부터 (음성 인식이 없어도 화면 버튼으로 답할 수 있다)
        val s = if (suggest && defaultTarget == PriceTarget.CurrentStation) actions.suggestion(recordId) else null
        if (s != null) {
            suggestion = s
            _state.value = State.Suggesting(s)
            if (speech.sttReady.value) speech.speak(s.question) { listenForSuggestion() } else speech.speak(s.question)
            return
        }
        if (!speech.sttReady.value) {
            _state.value = State.Failed("이 차에서는 음성 인식을 쓸 수 없어요. 화면으로 입력해 주세요.")
            return
        }
        rejects = 0
        kwAsks = 0
        val example = when (defaultTarget) {
            is PriceTarget.Provider -> " 충전기 출력도 함께 말씀해 주시면 정확해요. 예를 들어 백 킬로와트 삼백구십일 원."
            PriceTarget.CurrentStation -> " 예를 들어 삼백팔십오 원."
            PriceTarget.Apartment -> " 예를 들어 삼백팔십오 원."
        }
        ask("$targetName 단가를 말씀해 주세요.$example")
    }

    /** 화면의 [저장] 또는 음성 "응" */
    fun confirm() {
        val ready = pending ?: return
        speech.stopAll()
        scope.launch {
            val r = actions.apply(ready)
            pending = null
            _state.value = State.Done(r.spoken, r.ok)
            speech.speak(r.spoken)
        }
    }

    /** 제안한 단가가 맞다 (화면의 [맞아요] 또는 음성 "응") */
    fun acceptSuggestion() {
        val s = suggestion ?: return
        speech.stopAll()
        scope.launch {
            val r = actions.acceptSuggestion(s)
            suggestion = null
            _state.value = State.Done(r.spoken, r.ok)
            speech.speak(r.spoken)
        }
    }

    /** 제안한 단가가 아니다 → 단가를 묻는다 */
    fun rejectSuggestion() {
        suggestion = null
        speech.stopAll()
        if (!speech.sttReady.value) {
            _state.value = State.Failed("화면의 '숫자로 입력'으로 이 충전소 단가를 넣어 주세요.")
            return
        }
        ask("그럼 $targetName 단가를 말씀해 주세요. 예를 들어 삼백팔십오 원.")
    }

    /** 화면에서 출력 구간 버튼을 눌렀을 때 */
    fun chooseTier(tier: PowerTier) {
        val need = pendingKw ?: return
        speech.stopAll()
        onSetPrice(need.cmd.copy(speed = SpokenSpeed(listOf(tier), exact = true)))
    }

    /** 화면의 [모름] — 출력을 모를 때 */
    fun dontKnowKw() {
        val need = pendingKw ?: return
        speech.stopAll()
        planned(actions.fallback(need))
    }

    /** 다시 말하기 */
    fun retry() {
        // 음성 인식이 없는 기기면 다시 시도해도 같다 → 안내만 다시
        if (!speech.sttReady.value) return start()
        speech.stopAll()
        pending = null
        pendingKw = null
        rejects = 0
        kwAsks = 0
        listenForPrice()
    }

    fun cancel() {
        speech.stopAll()
        pending = null
        pendingKw = null
        suggestion = null
        _state.value = State.Failed("취소했어요.")
    }

    private fun ask(prompt: String) {
        _state.value = State.Speaking(prompt)
        speech.speak(prompt) { listenForPrice() }
    }

    private fun listenForPrice() {
        _state.value = State.Listening("", Mode.PRICE)
        speech.listen(
            onPartial = { _state.value = State.Listening(it, Mode.PRICE) },
            onFinal = { handlePrice(it) },
            onError = { _state.value = State.Failed("$it. 화면으로 입력하거나 다시 시도해 주세요.") },
        )
    }

    private fun listenForSuggestion() {
        val s = suggestion ?: return
        _state.value = State.Suggesting(s, "", listening = true)
        speech.listen(
            onPartial = { _state.value = State.Suggesting(s, it, listening = true) },
            onFinal = { handleSuggestion(it) },
            // 못 들었으면 화면 버튼으로 답할 수 있게 둔다
            onError = { _state.value = State.Suggesting(s) },
        )
    }

    /** "응" → 그대로 저장 / 금액 → 그 금액으로 평소처럼 확인 / "아니" → 단가를 묻는다 */
    private fun handleSuggestion(text: String) {
        val s = suggestion ?: return
        when (val cmd = parse(text, expectPrice = true)) {
            is VoiceCommand.SetPrice -> { suggestion = null; onSetPrice(cmd) }
            is VoiceCommand.Confirm -> when {
                cmd.yes -> acceptSuggestion()
                isStop(text) -> cancel()
                else -> rejectSuggestion()
            }
            is VoiceCommand.Rejected -> { suggestion = null; reject(cmd.reason) }
            VoiceCommand.Free -> onFree()
            null -> _state.value = State.Suggesting(s)
        }
    }

    private fun listenForConfirm() {
        val waiting = _state.value
        _state.value = State.Listening("", Mode.CONFIRM)
        speech.listen(
            onPartial = { _state.value = State.Listening(it, Mode.CONFIRM) },
            onFinal = { handleConfirm(it) },
            // 확인을 못 들었으면 화면 버튼으로 확인할 수 있게 그대로 둔다
            onError = { if (waiting is State.Confirming) _state.value = waiting },
        )
    }

    private fun listenForKw(candidates: List<PowerTier>) {
        val waiting = _state.value
        _state.value = State.Listening("", Mode.KW, candidates)
        speech.listen(
            onPartial = { _state.value = State.Listening(it, Mode.KW, candidates) },
            onFinal = { handleKw(it) },
            // 못 들었으면 화면의 출력 버튼으로 고를 수 있게 그대로 둔다
            onError = { if (waiting is State.AskingKw) _state.value = waiting },
        )
    }

    private fun parse(text: String, expectPrice: Boolean) =
        VoicePriceParser.parse(text, expectPrice, defaultTarget, tariff, providers)

    /** 단가 → 구간 정하기 → 확인 질문 또는 출력 질문 */
    private fun onSetPrice(cmd: VoiceCommand.SetPrice) = planned(actions.plan(cmd, recordId))

    private fun planned(p: VoiceActions.Planned) {
        when (p) {
            is VoiceActions.Planned.Ready -> {
                pending = p
                pendingKw = null
                _state.value = State.Confirming(p)
                speech.speak(p.question) { listenForConfirm() }
            }
            is VoiceActions.Planned.NeedKw -> {
                // "급속"처럼 넓게 두 번 답해도 안 갈리면 더 묻지 않는다
                if (++kwAsks > MAX_KW_ASKS) return planned(actions.fallback(p))
                pendingKw = p
                pending = null
                _state.value = State.AskingKw(p.question, p.candidates)
                speech.speak(p.question) { listenForKw(p.candidates) }
            }
            is VoiceActions.Planned.Fail -> {
                pending = null
                pendingKw = null
                _state.value = State.Failed(p.spoken)
                speech.speak(p.spoken)
            }
        }
    }

    private fun handlePrice(text: String) {
        when (val cmd = parse(text, expectPrice = true)) {
            is VoiceCommand.SetPrice -> onSetPrice(cmd)
            is VoiceCommand.Confirm -> if (cmd.yes && pending != null) confirm() else cancel()
            is VoiceCommand.Rejected -> reject(cmd.reason)
            VoiceCommand.Free -> onFree()
            null -> reject("단가를 다시 말씀해 주세요.")
        }
    }

    private fun handleConfirm(text: String) {
        when (val cmd = parse(text, expectPrice = false)) {
            // 확인하다가 다른 값을 말함 → 출력을 말 안 했으면 앞서 정한 속도를 이어서 쓴다
            is VoiceCommand.SetPrice -> onSetPrice(cmd.copy(speed = cmd.speed ?: pending?.cmd?.speed))
            is VoiceCommand.Confirm -> when {
                cmd.yes && pending != null -> confirm()
                isStop(text) -> cancel()
                else -> retry() // "아니" → 다시 말하기
            }
            is VoiceCommand.Rejected -> reject(cmd.reason)
            VoiceCommand.Free -> onFree()
            null -> pending?.let { _state.value = State.Confirming(it) }
        }
    }

    private fun handleKw(text: String) {
        val need = pendingKw ?: return
        // 출력과 단가를 같이 다시 말한 경우 ("백 킬로 삼백오십 원"). '원'이 없는 숫자("100")는 출력으로 본다
        val again = parse(text, expectPrice = false)
        if (again is VoiceCommand.SetPrice && text.contains('원')) {
            return onSetPrice(again.copy(speed = again.speed ?: need.cmd.speed))
        }
        if (again is VoiceCommand.Confirm && !again.yes && isStop(text)) return cancel()
        when (val a = VoicePriceParser.parseSpeedAnswer(text)) {
            is SpeedAnswer.Speed -> onSetPrice(need.cmd.copy(speed = a.speed))
            SpeedAnswer.DontKnow -> planned(actions.fallback(need))
            null -> {
                if (++rejects >= MAX_REJECTS) {
                    _state.value = State.AskingKw(need.question, need.candidates)
                    speech.speak("화면에서 충전기 출력을 골라 주세요.")
                } else {
                    val q = "충전기 출력을 다시 말씀해 주세요. 예를 들어 백 킬로와트."
                    _state.value = State.AskingKw(q, need.candidates)
                    speech.speak(q) { listenForKw(need.candidates) }
                }
            }
        }
    }

    /** "무료야" → 방금 충전한 충전소를 무료 충전소로 (나중에 설정 > 충전소 단가에서 지울 수 있다) */
    private fun onFree() {
        suggestion = null
        pending = null
        pendingKw = null
        if (defaultTarget != PriceTarget.CurrentStation) return reject("무료 표시는 방금 충전한 충전소에만 할 수 있어요. 단가를 말씀해 주세요.")
        speech.stopAll()
        scope.launch {
            val r = actions.markFree(recordId)
            _state.value = if (r.ok) State.Done(r.spoken, true) else State.Failed(r.spoken)
            speech.speak(r.spoken)
        }
    }

    private fun reject(reason: String) {
        rejects++
        if (rejects >= MAX_REJECTS) {
            _state.value = State.Failed(if (reason.startsWith("단가를 다시")) "단가를 알아듣지 못했어요. 화면으로 입력해 주세요." else reason)
            speech.speak("화면으로 입력해 주세요.")
        } else {
            ask(reason)
        }
    }

    private fun isStop(text: String): Boolean = STOP.any { text.replace(" ", "").contains(it) }

    companion object {
        const val MAX_REJECTS = 2
        const val MAX_KW_ASKS = 2
        private val STOP = listOf("취소", "그만", "됐어", "안해", "안 해")
    }
}
