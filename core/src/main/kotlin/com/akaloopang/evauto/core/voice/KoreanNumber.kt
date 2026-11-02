package com.akaloopang.evauto.core.voice

/**
 * 음성 인식 결과의 숫자 읽기.
 *
 * 음성 인식은 같은 말을 여러 모양으로 돌려준다: "385", "3백85", "삼백팔십오", "삼백 팔십 오",
 * "삼팔오"(한 자리씩), "324.4", "삼백이십사 점 사". 모두 같은 규칙으로 숫자로 바꾼다.
 */
object KoreanNumber {

    private val DIGITS = mapOf(
        '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4,
        '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )
    private val SMALL_UNITS = mapOf('십' to 10, '백' to 100, '천' to 1000)
    private const val MAN = '만'
    private const val POINT = '점'

    /** 숫자를 이루는 글자인가 (공백 제외) */
    fun isNumeralChar(c: Char): Boolean =
        c.isDigit() || c == '.' || c == ',' || c in DIGITS || c in SMALL_UNITS || c == MAN || c == POINT

    /** 문자열 전체를 숫자로. 숫자가 아닌 글자가 섞이면 null */
    fun parse(raw: String): Double? {
        val s = raw.replace(" ", "").replace(",", "").trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        if (s.any { !isNumeralChar(it) }) return null

        // 소수: '점' 또는 '.' 뒤는 한 자리씩 읽는다 ("점 사" → .4, "점 이오" → .25)
        val pointIdx = s.indexOfFirst { it == POINT || it == '.' }
        val intPart = if (pointIdx >= 0) s.substring(0, pointIdx) else s
        val fracPart = if (pointIdx >= 0) s.substring(pointIdx + 1) else ""

        val integer = parseInteger(intPart) ?: return null
        if (fracPart.isEmpty()) return integer.toDouble()
        val fracDigits = fracPart.map { c ->
            when {
                c.isDigit() -> c.digitToInt()
                c in DIGITS -> DIGITS.getValue(c)
                else -> return null
            }
        }
        val frac = fracDigits.joinToString("").let { "0.$it".toDouble() }
        return integer + frac
    }

    /** 정수 부분: 아라비아 숫자·한글 숫자·단위(십백천만) 혼합 */
    private fun parseInteger(s: String): Long? {
        if (s.isEmpty()) return 0
        s.toLongOrNull()?.let { return it }
        val hasUnit = s.any { it in SMALL_UNITS || it == MAN }
        if (!hasUnit) {
            // "삼팔오"처럼 한 자리씩 읽은 경우 → 이어 붙인다
            val digits = s.map { c ->
                when {
                    c.isDigit() -> c.digitToInt()
                    c in DIGITS -> DIGITS.getValue(c)
                    else -> return null
                }
            }
            return digits.joinToString("").toLongOrNull()
        }
        var total = 0L
        var section = 0L
        var current = -1L // 아직 숫자를 못 받음
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    current = s.substring(i, j).toLong()
                    i = j
                    continue
                }
                c in DIGITS -> current = if (current >= 0 && current < 10) current * 10 + DIGITS.getValue(c) else DIGITS.getValue(c).toLong()
                c in SMALL_UNITS -> {
                    section += (if (current < 0) 1 else current) * SMALL_UNITS.getValue(c)
                    current = -1
                }
                c == MAN -> {
                    val block = section + (if (current < 0) 0 else current)
                    total += (if (block == 0L) 1 else block) * 10_000
                    section = 0
                    current = -1
                }
                else -> return null
            }
            i++
        }
        return total + section + (if (current < 0) 0 else current)
    }
}
