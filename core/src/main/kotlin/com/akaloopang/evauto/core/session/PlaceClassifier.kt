package com.akaloopang.evauto.core.session

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PlaceKind
import java.time.Duration

/** 집(아파트) 충전기 위치. 처음 아파트 충전을 확인할 때 저장한다. */
data class HomeZone(val center: GeoPoint, val radiusM: Double = DEFAULT_RADIUS_M) {
    fun contains(p: GeoPoint): Boolean = center.distanceTo(p) <= radiusM

    companion object {
        /** 단지 규모 + 지하주차장 진입 직전 GPS 오차를 고려한 반경 */
        const val DEFAULT_RADIUS_M = 250.0
    }
}

data class PlaceDecision(
    val place: PlaceKind,
    /** 사용자에게 한 번 확인받아야 하는가 ('집 충전기가 맞나요?') */
    val needsConfirm: Boolean,
    val reason: String,
)

/**
 * 충전 장소 판정. 아파트 충전을 빠뜨리지 않는 것이 목표라, 애매하면 '집 후보 + 확인 요청'으로 둔다.
 *
 *  1. 집 위치가 저장돼 있고 위치(또는 시동 끄기 직전 위치)가 그 안 → 집 (확인 불필요)
 *     단, 내비 목적지가 충전소이거나 아파트에 없는 급속 충전이면 집 근처 공용 충전소
 *  2. 위치가 집 밖 → 공용
 *  3. 위치를 모름 / 집 미설정:
 *     - 완속 + (야간 시작 또는 3시간 이상) + 내비 충전소 목적지 아님 → 집 후보 (확인 요청)
 *     - 급속·초급속이거나 내비 목적지가 충전소 → 공용
 *     - 그 외 → 모름 (확인 요청)
 */
object PlaceClassifier {

    /**
     * @param homeHasDc 아파트에 급속 충전기가 있다(아파트 급속 단가를 설정함). 없으면 집 반경 안의 급속 충전은
     *   집 근처 공용 충전소(마트·주차장 등)로 본다.
     */
    fun classify(
        session: ChargeSession,
        home: HomeZone?,
        chargeType: ChargeType?,
        naviChargingStationName: String? = null,
        homeHasDc: Boolean = false,
    ): PlaceDecision {
        val loc = session.bestLocation
        if (home != null && loc != null) {
            if (!home.contains(loc)) return PlaceDecision(PlaceKind.PUBLIC, false, "집 위치 밖")
            // 집 반경(250m) 안이어도 내비로 충전소를 찾아갔거나, 아파트에 없는 급속으로 충전했으면 집 근처 공용 충전소
            if (naviChargingStationName != null) return PlaceDecision(PlaceKind.PUBLIC, false, "집 근처 — 내비 목적지가 충전소")
            val fast = chargeType == ChargeType.DC || chargeType == ChargeType.ULTRA
            if (fast && !homeHasDc) return PlaceDecision(PlaceKind.PUBLIC, false, "집 근처 공용 급속")
            return PlaceDecision(PlaceKind.HOME, false, "집 위치 반경 안")
        }
        val isAc = chargeType == null || chargeType == ChargeType.AC
        if (naviChargingStationName != null || chargeType == ChargeType.DC || chargeType == ChargeType.ULTRA) {
            return PlaceDecision(PlaceKind.PUBLIC, false, "급속 또는 내비 충전소 목적지")
        }
        if (isAc && (isOvernight(session) || session.duration >= Duration.ofHours(3))) {
            // 집 위치를 이미 알면 위치가 없어도 확인 없이 집으로 본다 (지하주차장 + 야간 완속)
            return PlaceDecision(PlaceKind.HOME, needsConfirm = home == null, reason = "야간·장시간 완속 충전")
        }
        return PlaceDecision(PlaceKind.UNKNOWN, true, "판정 근거 부족")
    }

    /** 18시~06시 사이에 시작했는가 */
    fun isOvernight(session: ChargeSession): Boolean {
        val h = session.start.hour
        return h >= 18 || h < 6
    }
}
