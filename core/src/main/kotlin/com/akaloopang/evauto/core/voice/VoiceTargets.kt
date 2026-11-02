package com.akaloopang.evauto.core.voice

import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.PlaceKind
import java.time.Duration
import java.time.LocalDateTime

/**
 * "이 충전소"가 어느 기록인지 고른다.
 *
 * 최근 [maxAge] 안에 끝난 공용 충전 중, 아직 확인 단가가 없는 것('단가 입력' 안내 중)을 먼저,
 * 없으면 가장 최근 공용 충전. 오래전 충전을 엉뚱하게 고치지 않도록 시간 제한을 둔다.
 */
object VoiceTargets {
    val DEFAULT_MAX_AGE: Duration = Duration.ofHours(12)

    fun currentStationRecord(
        records: List<ChargingRecord>,
        now: LocalDateTime,
        maxAge: Duration = DEFAULT_MAX_AGE,
    ): ChargingRecord? {
        val recent = records.filter { r ->
            val meta = r.car ?: return@filter false
            val ended = meta.endDate ?: r.date
            meta.stationKey != null && meta.place != PlaceKind.HOME &&
                !ended.isAfter(now) && Duration.between(ended, now) <= maxAge
        }
        return recent.filter { it.car?.askStationPrice == true }.maxByOrNull { it.date }
            ?: recent.maxByOrNull { it.date }
    }
}
