package com.akaloopang.evauto.core.stats

import com.akaloopang.evauto.core.model.ChargingRecord
import java.time.temporal.ChronoUnit

/**
 * 계기판 주행거리 기록 → 구간 → 묶음(window).
 *
 * 구간 충전량은 full-to-full 규칙: 구간 시작 기록의 충전은 그 전 주행 몫, 끝 기록의 충전은
 * 이 구간 주행을 메운 것. 불가능한 전비 구간은 이웃 구간과 합쳐 보고, 합쳐도 불가능할 때만
 * 충전 누락을 의심한다.
 *
 * 차량 앱에서는 주행거리가 차량에서 자동으로 들어오므로 오입력은 거의 없지만,
 * 백업에서 가져온 수동 기록과 섞일 수 있어 오입력 방어 로직을 둔다.
 */
const val MAX_PLAUSIBLE_EFFICIENCY = 10.0 // km/kWh

data class OdoSegment(
    val start: ChargingRecord,
    val end: ChargingRecord,
    val distanceKm: Double,
    val kWh: Double,
    val payment: Double,
    val parts: Int = 1,
) {
    val kmPerKwh: Double get() = if (kWh > 0) distanceKm / kWh else Double.POSITIVE_INFINITY
    val plausible: Boolean get() = kWh > 0 && kmPerKwh <= MAX_PLAUSIBLE_EFFICIENCY
    val missingChargeSuspected: Boolean get() = !plausible
    fun continuesInto(next: OdoSegment): Boolean = end.id == next.start.id
    fun merge(next: OdoSegment) = OdoSegment(
        start, next.end, distanceKm + next.distanceKm, kWh + next.kWh, payment + next.payment, parts + next.parts,
    )
}

object OdoSegments {
    /** 하루 주행 상한 근사 */
    const val MAX_KM_PER_DAY = 1500.0

    private fun hasOdo(r: ChargingRecord) = r.odometer != null && r.odometer > 0

    fun raw(records: Iterable<ChargingRecord>): List<OdoSegment> {
        // 정렬: 날짜(시각 포함) → 같으면 계기판 없는 기록 먼저 → 계기판 오름차순
        val sorted = records.sortedWith { a, b ->
            val c = a.date.compareTo(b.date)
            if (c != 0) return@sortedWith c
            val ao = hasOdo(a)
            val bo = hasOdo(b)
            if (ao != bo) return@sortedWith if (ao) 1 else -1
            if (!ao) 0 else a.odometer!!.compareTo(b.odometer!!)
        }

        val result = mutableListOf<OdoSegment>()
        var startRec: ChargingRecord? = null
        var kWh = 0.0
        var payment = 0.0
        var preDay: java.time.LocalDate? = null
        var preKwh = 0.0
        var prePay = 0.0
        for (r in sorted) {
            if (startRec != null) {
                kWh += r.kWh
                payment += r.paymentAmount
            }
            if (!hasOdo(r)) {
                if (startRec == null) {
                    val d = r.date.toLocalDate()
                    if (d != preDay) {
                        preDay = d; preKwh = 0.0; prePay = 0.0
                    }
                    preKwh += r.kWh
                    prePay += r.paymentAmount
                }
                continue
            }
            val odo = r.odometer!!
            if (startRec == null) {
                startRec = r
                if (preDay == r.date.toLocalDate()) {
                    kWh = preKwh; payment = prePay
                }
                continue
            }
            val prevOdo = startRec.odometer!!
            if (odo == prevOdo) continue
            if (odo < prevOdo || jumped(startRec, r)) continue
            result += OdoSegment(startRec, r, odo - prevOdo, kWh, payment)
            startRec = r
            kWh = 0.0
            payment = 0.0
        }
        return result
    }

    private fun jumped(a: ChargingRecord, b: ChargingRecord): Boolean {
        val days = ChronoUnit.DAYS.between(a.date.toLocalDate(), b.date.toLocalDate())
        val allowed = (if (days < 1) 1 else days) * MAX_KM_PER_DAY
        return b.odometer!! - a.odometer!! > allowed
    }

    fun windows(records: Iterable<ChargingRecord>): List<OdoSegment> {
        val out = mutableListOf<OdoSegment>()
        for (seg in raw(records)) {
            val prev = out.lastOrNull()
            val adjacent = prev != null && prev.continuesInto(seg)
            if (adjacent && !prev!!.plausible) {
                out[out.size - 1] = prev.merge(seg)
                continue
            }
            if (adjacent && !seg.plausible) {
                val merged = prev!!.merge(seg)
                if (merged.plausible) {
                    out[out.size - 1] = merged
                    continue
                }
            }
            out += seg
        }
        return out
    }
}
