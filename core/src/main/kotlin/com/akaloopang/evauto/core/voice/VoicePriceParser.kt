package com.akaloopang.evauto.core.voice

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.SpokenSpeed
import com.akaloopang.evauto.core.pricing.TariffTable
import com.akaloopang.evauto.core.pricing.TierRange

/** 단가를 어디에 적용할까 */
sealed interface PriceTarget {
    /** 방금(또는 지금) 충전한 공용 충전소 */
    data object CurrentStation : PriceTarget
    /** 집(아파트) 충전 단가 */
    data object Apartment : PriceTarget
    /** 특정 사업자의 내 설정 단가 */
    data class Provider(val name: String) : PriceTarget
}

sealed interface VoiceCommand {
    /** @param speed 말한 충전 속도("100킬로", "급속"). 없으면 null — 구간은 차가 본 값·공시 요금표로 정한다 */
    data class SetPrice(val target: PriceTarget, val unitPrice: Double, val speed: SpokenSpeed? = null) : VoiceCommand {
        val chargeType: ChargeType? get() = speed?.chargeType
    }
    data class Confirm(val yes: Boolean) : VoiceCommand
    /** "무료야", "공짜 충전소야" — 방금 충전한 충전소가 무료 (선택 기능) */
    data object Free : VoiceCommand
    /** 알아들었지만 쓸 수 없음 → [reason]을 읽어 주고 다시 묻는다 */
    data class Rejected(val reason: String, val heard: String) : VoiceCommand
}

/** 충전기 출력을 물었을 때의 대답 */
sealed interface SpeedAnswer {
    data class Speed(val speed: SpokenSpeed) : SpeedAnswer
    data object DontKnow : SpeedAnswer
}

/**
 * 말로 단가 입력: "이 충전소 385원", "킬로와트시당 삼백팔십오 원으로 저장해 줘", "아파트 단가 이백오십 원",
 * "볼트업 급속 350원", "SK일렉링크 백 킬로와트 삼백구십일 원", "SK일렉링크 초급속 사백삼십 원으로 바꿔줘".
 *
 * 충전 속도: 출력을 말하면("백 킬로", "200kW") 그 구간, "완속/중속/급속/초급속"이면 그 말이 뜻하는 구간들.
 * '킬로와트시(kWh)'는 단가 단위라 출력으로 보지 않는다.
 *
 * 오인식 방어:
 *  - 숫자는 '원' 바로 앞의 숫자 덩어리만 단가로 본다 → "이 충전소"의 '이'(2) 같은 글자를 숫자로 오인하지 않는다
 *  - kWh당 [MIN_UNIT_PRICE]~[MAX_UNIT_PRICE]원 밖이면 거절 ("3850원"은 385원을 잘못 들었을 가능성)
 *  - 저장 전에 항상 읽어 주고 확인받는다(앱 쪽 흐름)
 */
object VoicePriceParser {
    const val MIN_UNIT_PRICE = 100.0
    const val MAX_UNIT_PRICE = 1000.0

    private val YES = listOf("네", "응", "예", "맞아", "맞습니다", "저장", "좋아", "그래", "확인", "오케이")
    private val NO = listOf("아니", "취소", "틀려", "다시", "그만", "됐어")
    private val FREE = listOf("무료", "공짜", "돈안")
    private val FILLERS = listOf(
        "킬로와트시당", "킬로와트당", "kwh당", "킬로와트시", "킬로와트", "kwh", "단가는", "단가", "원으로", "원이야", "원이에요",
        "원입니다", "원", "이에요", "입니다", "이야", "요", "으로", "로", "저장해줘", "저장해", "바꿔줘", "바꿔", "해줘",
    )

    /**
     * @param expectPrice 앱이 "단가를 말씀해 주세요"라고 물은 직후면 true — 숫자만 말해도("삼백팔십오") 단가로 받는다
     * @param defaultTarget 대상을 말하지 않았을 때 적용할 곳 (물어본 화면의 대상)
     */
    fun parse(
        utterance: String,
        expectPrice: Boolean = false,
        defaultTarget: PriceTarget = PriceTarget.CurrentStation,
        tariff: TariffTable? = null,
        providers: List<String> = ProviderCatalog.officialProviders,
    ): VoiceCommand? {
        val text0 = utterance.trim()
        if (text0.isEmpty()) return null
        // 출력("백 킬로")을 먼저 떼어 내야 그 숫자를 단가로 오인하지 않는다
        val kw = extractKw(text0)
        val text = kw?.let { text0.removeRange(it.second).trim() } ?: text0
        val compact = text.replace(" ", "")

        val price = extractPrice(text) ?: if (expectPrice) wholeNumber(compact) else null

        if (price == null) {
            // "무료 아니야"는 무료가 아니다 → 확인 대답으로 처리
            if (FREE.any { compact.contains(it) } && NO.none { compact.contains(it) }) return VoiceCommand.Free
            confirmation(compact)?.let { return it }
            return if (expectPrice || mentionsPrice(compact)) {
                VoiceCommand.Rejected("단가를 알아듣지 못했어요. 예를 들어 '삼백팔십오 원'처럼 말씀해 주세요.", text)
            } else null
        }
        if (price < MIN_UNIT_PRICE || price > MAX_UNIT_PRICE) {
            return VoiceCommand.Rejected(
                "${formatPrice(price)}원은 킬로와트시당 단가로 보기 어려워요. 다시 말씀해 주세요.", text,
            )
        }
        val speed = kw?.first ?: speedWord(compact)
        return VoiceCommand.SetPrice(target(compact, defaultTarget, tariff, providers), price, speed)
    }

    /**
     * "충전기에 적힌 출력이 몇 킬로와트인가요?"에 대한 대답.
     * "백", "백 킬로", "200kW", "급속", "몰라" 등. 단가를 같이 말했으면 [parse]로 다시 처리한다(앱 쪽).
     */
    fun parseSpeedAnswer(utterance: String): SpeedAnswer? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        val compact = text.replace(" ", "")
        if (DONT_KNOW.any { compact.contains(it) }) return SpeedAnswer.DontKnow
        extractKw(text)?.let { return SpeedAnswer.Speed(it.first) }
        speedWord(compact)?.let { return SpeedAnswer.Speed(it) }
        var s = compact.lowercase()
        SPEED_FILLERS.sortedByDescending { it.length }.forEach { s = s.replace(it, "") }
        val kwNumber = KoreanNumber.parse(s) ?: return null
        return SpokenSpeed.ofKw(kwNumber)?.let { SpeedAnswer.Speed(it) }
    }

    private val DONT_KNOW = listOf("몰라", "모르겠", "모름", "모르")
    private val SPEED_FILLERS = listOf("짜리", "이에요", "입니다", "이야", "요", "충전기", "정도", "쯤", "이요", "예")

    /** 출력 단어 — 킬로/키로/킬로와트/kW/케이더블유 (킬로와트시·kWh·킬로와트당·킬로미터는 제외) */
    private val KW_WORD = Regex("(킬로|키로)(와트)?|kw|케이더블유", RegexOption.IGNORE_CASE)
    private val NOT_KW_AFTER = setOf('시', '당', 'h', 'H', '미')

    /** 말 속의 충전기 출력과 그 위치 (숫자~단위). 여러 번 말했으면 마지막 것 */
    fun extractKw(text: String): Pair<SpokenSpeed, IntRange>? {
        var found: Pair<SpokenSpeed, IntRange>? = null
        for (m in KW_WORD.findAll(text)) {
            val next = text.getOrNull(m.range.last + 1)
            if (next != null && next in NOT_KW_AFTER) continue
            val (value, start) = numberSpanBefore(text, m.range.first) ?: continue
            val speed = SpokenSpeed.ofKw(value) ?: continue
            found = speed to (start..m.range.last)
        }
        return found
    }

    private fun speedWord(compact: String): SpokenSpeed? = when {
        compact.contains("초급속") -> SpokenSpeed.ULTRA_FAST
        compact.contains("중속") -> SpokenSpeed.MEDIUM
        compact.contains("급속") -> SpokenSpeed.FAST
        compact.contains("완속") -> SpokenSpeed.SLOW
        else -> null
    }

    /** '원' 바로 앞 숫자 덩어리. 여러 개면 마지막 것 (말하다가 고쳐 말한 값) */
    fun extractPrice(text: String): Double? {
        var found: Double? = null
        var idx = text.indexOf('원')
        while (idx >= 0) {
            numberBefore(text, idx)?.let { found = it }
            idx = text.indexOf('원', idx + 1)
        }
        if (found != null) return found
        // '원' 없이 아라비아 숫자만 말한 경우 ("킬로와트시당 385")
        return Regex("\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDouble() }
            .lastOrNull { it in MIN_UNIT_PRICE..MAX_UNIT_PRICE }
    }

    private fun numberBefore(text: String, end: Int): Double? = numberSpanBefore(text, end)?.first

    /** [end] 바로 앞 숫자 덩어리의 값과 시작 위치 */
    private fun numberSpanBefore(text: String, end: Int): Pair<Double, Int>? {
        var i = end - 1
        while (i >= 0 && text[i] == ' ') i--
        val stop = i
        while (i >= 0) {
            val c = text[i]
            if (KoreanNumber.isNumeralChar(c)) { i--; continue }
            // 숫자 사이의 공백("삼백 팔십 오")은 허용, 숫자가 아닌 글자 앞 공백에서 멈춘다.
            // 아라비아 숫자와 한글 숫자가 공백을 사이에 두고 붙은 것("391 이백")은 서로 다른 숫자로 본다.
            if (c == ' ' && i > 0 && KoreanNumber.isNumeralChar(text[i - 1]) && sameKind(text[i - 1], text[i + 1])) { i--; continue }
            break
        }
        if (stop < 0 || i == stop) return null
        return KoreanNumber.parse(text.substring(i + 1, stop + 1))?.let { it to i + 1 }
    }

    private fun isArabic(c: Char) = c.isDigit() || c == '.' || c == ','
    private fun sameKind(a: Char, b: Char) = isArabic(a) == isArabic(b)

    private fun wholeNumber(compact: String): Double? {
        var s = compact.lowercase()
        FILLERS.sortedByDescending { it.length }.forEach { s = s.replace(it, "") }
        return KoreanNumber.parse(s)
    }

    private fun confirmation(compact: String): VoiceCommand.Confirm? {
        if (NO.any { compact.startsWith(it) || compact.contains(it) }) return VoiceCommand.Confirm(false)
        if (YES.any { compact.startsWith(it) }) return VoiceCommand.Confirm(true)
        return null
    }

    private fun mentionsPrice(compact: String) = compact.contains("단가") || compact.contains("원")

    private fun target(compact: String, default: PriceTarget, tariff: TariffTable?, providers: List<String>): PriceTarget {
        if (compact.contains("아파트") || compact.contains("집")) return PriceTarget.Apartment
        if (compact.contains("이충전소") || compact.contains("여기") || compact.contains("방금")) return PriceTarget.CurrentStation
        ProviderCatalog.findInStationName(compact, providers)?.let { return PriceTarget.Provider(it) }
        tariff?.findOperatorInName(compact)?.let { return PriceTarget.Provider(it) }
        return default
    }

    fun formatPrice(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    fun whereName(target: PriceTarget, stationName: String?): String = when (target) {
        PriceTarget.CurrentStation -> stationName ?: "이 충전소"
        PriceTarget.Apartment -> "아파트 충전"
        is PriceTarget.Provider -> target.name
    }

    /**
     * 확인 질문 문장 (TTS로 읽어 줄 문장). 어느 출력 구간 칸에 넣는지([tiers])를 꼭 읽어 준다 —
     * "SK일렉링크 50킬로와트 이상 충전기, 킬로와트시당 391원으로 저장할까요?"
     */
    fun confirmQuestion(cmd: VoiceCommand.SetPrice, stationName: String?, tiers: List<PowerTier> = emptyList()): String {
        val where = whereName(cmd.target, stationName)
        val speed = when {
            tiers.isNotEmpty() -> TierRange.label(tiers, spoken = true).let { if (it == "모든 충전기") " $it" else " $it 충전기" }
            cmd.target == PriceTarget.Apartment -> cmd.chargeType?.speedName?.let { " $it" } ?: ""
            else -> ""
        }
        return "$where$speed, 킬로와트시당 ${formatPrice(cmd.unitPrice)}원으로 저장할까요?"
    }

    /** 구간마다 요금이 달라 출력을 물을 때 (TTS) */
    fun askKwQuestion(where: String, candidates: List<PowerTier>): String {
        // 흔한 충전기 출력(50·100·200kW)으로 예를 든다. 30~50kW 구간만 남았을 때만 40
        val common = listOf(50.0 to PowerTier.T50_TO_100, 100.0 to PowerTier.T100_TO_200, 200.0 to PowerTier.OVER_200)
        val examples = common.filter { it.second in candidates }.map { it.first.toInt().toString() }
            .ifEmpty { if (PowerTier.T30_TO_50 in candidates) listOf("40") else emptyList() }
        val slow = if (PowerTier.UNDER_30 in candidates) " 완속이면 완속이라고 말씀해 주세요." else ""
        val eg = if (examples.isNotEmpty()) " 예를 들어 ${examples.joinToString(", ")}." else ""
        return "$where 요금은 충전기 출력마다 달라요. 충전기에 적힌 출력이 몇 킬로와트인가요?$eg$slow"
    }
}
