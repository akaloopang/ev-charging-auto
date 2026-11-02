package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.ChargingRecord

/**
 * 단가가 바뀌어도 **지난 기록은 그때 단가 그대로** 둔다 — 가계부 기록의 신뢰도.
 * (예: 어제까지 아파트 190원, 오늘부터 250원 → 단가를 250원으로 바꿔도 어제 기록은 190원)
 *
 * 다시 계산하는 경우는 아래 넷뿐이고, 영수증·관리비 정산으로 확정된 금액은 어떤 경우에도 건드리지 않는다.
 */
object RepricePolicy {

    /** 단가를 몰라 금액을 비워 둔 기록 — 처음 단가를 넣으면 채운다 (지켜야 할 '그때 단가'가 없다) */
    fun unpriced(r: ChargingRecord): Boolean = r.isEstimated && r.unitPrice <= 0.0 && !r.isFree

    /**
     * 아직 확인(맞아요)하지 않은 추정 기록 — 그 충전소 단가를 알려 주면 같은 충전소의 대기 기록도 같이 맞춘다.
     * 같은 충전소인지는 호출하는 쪽에서 본다.
     */
    fun pending(r: ChargingRecord): Boolean = r.isEstimated && r.car?.confirmed == false

    /**
     * 공시 안내가·다른 충전소 단가로 짐작한 미확인 기록 — 회원/비회원 기준을 바꾸면 짐작값만 맞춘다.
     * 사용자가 확인했거나 직접 넣은 단가(내 설정·충전소 확인 단가)는 그대로.
     */
    fun pendingGuess(r: ChargingRecord): Boolean =
        pending(r) && (r.car?.priceSource ?: PriceSource.NONE) in GUESSES

    /**
     * 설정 단가로 계산한 추정 기록 — 사용자가 "잘못 입력한 값이라 지난 기록도 고친다"고 고른 경우에만.
     * 단가가 실제로 바뀐 것(인상·인하)이면 고치지 않는 것이 기본이다.
     */
    fun fromSetting(r: ChargingRecord): Boolean = r.isEstimated && r.car?.priceSource == PriceSource.MY_SETTING

    private val GUESSES = setOf(PriceSource.NONE, PriceSource.OFFICIAL_TARIFF, PriceSource.OTHER_STATION)
}
