package com.akaloopang.evauto.data

import com.akaloopang.evauto.core.model.RecordSource
import com.akaloopang.evauto.core.session.ChargeSession
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.core.session.NaviChargingStation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 시연용 두세 달 충전 기록 — 시연 영상·화면 캡처에서 통계·절감액·월간 브리핑이 실제처럼 보이게.
 *
 * 하루하루 주행(평일 출퇴근 40km 안팎, 토요일 가끔 장거리)으로 SOC를 줄이고,
 *  - 저녁 SOC가 50% 아래면 아파트 완속(밤 10시 → 아침 6시 반, 85%까지, 앱 꺼진 동안의 충전)
 *  - 장거리 도중 35% 아래면 휴게소 초급속, 평일 낮에 가끔 회사 근처 급속
 * 으로 채운다. 전비는 약 5.6km/kWh(실주행 수준). 같은 시드로 늘 같은 기록이 나온다.
 */
object DemoData {

    data class Stop(
        val session: ChargeSession,
        /** 공용 충전소면 내비 목적지 정보 */
        val navi: NaviChargingStation?,
        /** 공용 충전소의 영수증 단가 (원/kWh) — null이면 아직 영수증 안 넣은 기록 */
        val receiptUnitPrice: Double?,
    )

    data class Spot(val name: String, val ratedKw: Int, val location: GeoPoint, val unitPrice: Double, val peakKw: Double)

    val HOME = GeoPoint(37.5894, 127.0167)
    /** 회사 근처 급속 (평일 낮) */
    val OFFICE = Spot("SK일렉링크 판교점", 100, GeoPoint(37.4012, 127.1086), 420.0, 96.0)
    /** 장거리 휴게소 초급속 */
    val HIGHWAY = Spot("채비 안성휴게소", 200, GeoPoint(37.0128, 127.2044), 385.0, 178.0)
    /** 집 근처 공용 급속 (아파트에는 급속이 없다) */
    val NEARBY = Spot("환경부 성북구청", 50, GeoPoint(37.5893, 127.0218), 324.4, 47.0)

    private const val KM_PER_KWH = 5.6

    fun timeline(today: LocalDate, capacityKwh: Double, days: Int = 75, startOdo: Double = 9_800.0, seed: Long = 7): List<Stop> {
        val rnd = Random(seed)
        val out = mutableListOf<Stop>()
        var soc = 72.0
        var odo = startOdo
        fun drive(km: Double) {
            odo += km
            soc = max(5.0, soc - km / KM_PER_KWH / capacityKwh * 100.0)
        }
        fun public(spot: Spot, at: LocalDateTime, target: Double, receipt: Boolean) {
            val from = soc
            val kWh = (target - from) / 100.0 * capacityKwh
            val minutes = max(15L, (kWh / (spot.peakKw * 0.62) * 60).roundToLong())
            out += Stop(
                ChargeSession(
                    source = RecordSource.CAR_LIVE, start = at, end = at.plusMinutes(minutes),
                    socStart = from, socEnd = target, odometerStart = odo, odometerEnd = odo,
                    peakKw = spot.peakKw, location = spot.location, batteryCapacityKwh = capacityKwh,
                ),
                NaviChargingStation(spot.name, spot.ratedKw, chargerCount = 4, targetSoc = target.toInt()),
                if (receipt) spot.unitPrice else null,
            )
            soc = target
        }
        // 공용 충전소 방문 일정 (며칠 전쯤) — 충전소별 통계·'가장 저렴' 표시가 보이게 고르게 섞는다
        val officeDue = mutableListOf(days - 8, days - 36, 10)
        val nearbyDue = mutableListOf(days - 22, 20)
        for (i in days downTo 1) {
            val day = today.minusDays(i.toLong())
            val dow = day.dayOfWeek
            val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
            val longTrip = dow == DayOfWeek.SATURDAY && rnd.nextInt(10) < 4
            val km = when {
                longTrip -> 170.0 + rnd.nextInt(60)
                weekend -> 12.0 + rnd.nextInt(25)
                else -> 36.0 + rnd.nextInt(16)
            }
            if (longTrip) {
                drive(km * 0.55)
                if (soc < 45) public(HIGHWAY, day.atTime(11, 20).plusMinutes(rnd.nextInt(30).toLong()), 80.0, receipt = true)
                drive(km * 0.45)
            } else if (!weekend && soc < 65 && officeDue.any { i <= it }) {
                officeDue.removeAll { i <= it }
                // 출근길 배터리가 부족한 날 회사 근처 급속
                drive(km * 0.5)
                public(OFFICE, day.atTime(12, 10).plusMinutes(rnd.nextInt(30).toLong()), 80.0, receipt = true)
                drive(km * 0.5)
            } else if (weekend && !longTrip && soc < 65 && nearbyDue.any { i <= it }) {
                nearbyDue.removeAll { i <= it }
                drive(km * 0.5)
                public(NEARBY, day.atTime(15, 5).plusMinutes(rnd.nextInt(40).toLong()), 80.0, receipt = true)
                drive(km * 0.5)
            } else {
                drive(km)
            }
            // 저녁: 배터리가 절반 아래면 아파트 완속 (앱이 꺼진 동안 충전 → 다음 시동 때 기록)
            // 어젯밤에는 꼭 충전한다 — 오늘 아침 시동 때 '꺼진 동안 아파트 충전' 확인 카드가 뜨게
            if (soc < 45 || (i == 1 && soc < 75)) {
                val start = day.atTime(21, 40).plusMinutes(rnd.nextInt(40).toLong())
                val target = 85.0
                out += Stop(
                    ChargeSession(
                        source = RecordSource.CAR_INFERRED, start = start, end = day.plusDays(1).atTime(6, 30).plusMinutes(rnd.nextInt(40).toLong()),
                        socStart = soc, socEnd = target, odometerStart = odo, odometerEnd = odo,
                        peakKw = null, location = HOME, batteryCapacityKwh = capacityKwh,
                    ),
                    navi = null, receiptUnitPrice = null,
                )
                soc = target
            }
        }
        // 마지막(오늘) 공용 충전 한 건은 영수증을 아직 안 넣은 상태로 둔다 — 홈 '확인' 카드 시연용
        val lastPublic = out.indexOfLast { it.navi != null }
        if (lastPublic >= 0) out[lastPublic] = out[lastPublic].copy(receiptUnitPrice = null)
        return out
    }

    /** 관리비 고지서 금액 흉내: 그달 아파트 추정 금액 합계를 10원 단위로 (고지서는 기본요금 등으로 조금 다를 수 있다) */
    fun billFor(estimatedSum: Double): Long = ((estimatedSum * 1.012) / 10).roundToLong() * 10
}
