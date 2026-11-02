package com.akaloopang.evauto.core.pricing

import com.akaloopang.evauto.core.model.AmountStatus
import com.akaloopang.evauto.core.model.CarMeta
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.model.RecordSource
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 추정 금액을 원 단위로 만드는 규칙. 영수증이 없는 충전(차량 추정)에만 쓴다.
 * 기본값은 [HALF_UP_1](1원 단위 반올림).
 */
enum class RoundingRule(val key: String) {
    HALF_UP_1("half_up_1"),   // 1원 단위 반올림
    FLOOR_1("floor_1"),       // 1원 미만 절사
    FLOOR_10("floor_10");     // 10원 미만 절사

    fun apply(amount: Double): Double = apply(BigDecimal.valueOf(amount))

    fun apply(bd: BigDecimal): Double {
        return when (this) {
            HALF_UP_1 -> bd.setScale(0, RoundingMode.HALF_UP).toDouble()
            FLOOR_1 -> bd.setScale(0, RoundingMode.FLOOR).toDouble()
            FLOOR_10 -> bd.divide(BigDecimal.TEN).setScale(0, RoundingMode.FLOOR).multiply(BigDecimal.TEN).toDouble()
        }
    }

    companion object {
        fun fromKey(raw: String?): RoundingRule = entries.firstOrNull { it.key == raw } ?: HALF_UP_1
    }
}

/** 영수증(카카오 알림톡·충전기 화면·직접 입력)에 적힌 값. 적힌 그대로 넣는다. */
data class Receipt(
    /** 결제 금액(원) — 절사·반올림이 이미 반영된 최종값 */
    val amount: Double,
    /** 영수증에 적힌 충전량(kWh). 없으면 null */
    val kWh: Double? = null,
    /** 영수증에 적힌 단가(원/kWh). 없으면 null */
    val unitPrice: Double? = null,
)

/**
 * 금액 확정 규칙.
 *
 *  1. 영수증 금액이 있으면 그것이 최종값이다. 다시 계산하지 않는다.
 *     (kWh × 단가를 다시 하면 사업자별 절사·반올림 때문에 몇 원씩 어긋난다)
 *  2. 충전량: 영수증 kWh > (영수증 금액 ÷ 영수증·설정 단가) > 차량 추정값
 *  3. 단가: 영수증 단가 > 설정 단가 > 금액 ÷ 충전량 역산
 *  4. 우선순위가 낮은 정보로 높은 정보를 덮어쓰지 않는다 (영수증 기록에 관리비 정산 적용 금지).
 */
object AmountPolicy {

    fun round2(v: Double): Double = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** 차량 추정 금액 */
    fun estimateAmount(kWh: Double, unitPrice: Double?, rule: RoundingRule = RoundingRule.HALF_UP_1): Double? {
        if (unitPrice == null || unitPrice <= 0 || kWh <= 0) return null
        // 10진수로 곱한다: 42.57 × 250 을 double로 하면 10642.4999… 가 되어 반올림이 1원 틀어진다
        return rule.apply(BigDecimal.valueOf(kWh).multiply(BigDecimal.valueOf(unitPrice)))
    }

    /**
     * 영수증 적용. 차량이 추정한 값은 [CarMeta.estimatedKwh]/[CarMeta.estimatedAmount]에 남겨
     * 추정 정확도(손실계수) 학습에 쓴다.
     *
     * @param settingsUnitPrice 영수증에 단가가 없을 때 kWh 역산에 쓸 설정 단가
     */
    fun applyReceipt(record: ChargingRecord, receipt: Receipt, settingsUnitPrice: Double? = null): ChargingRecord {
        require(receipt.amount >= 0) { "영수증 금액은 0 이상이어야 합니다" }
        val price = receipt.unitPrice?.takeIf { it > 0 } ?: settingsUnitPrice?.takeIf { it > 0 }
        val kWh = when {
            receipt.kWh != null && receipt.kWh > 0 -> receipt.kWh
            price != null -> round2(receipt.amount / price)
            else -> record.kWh
        }
        val unitPrice = price ?: round2(ChargingRecord.calcUnitPrice(kWh, receipt.amount))
        val meta = (record.car ?: CarMeta(source = RecordSource.MANUAL, amountStatus = AmountStatus.RECEIPT)).let {
            it.copy(
                amountStatus = AmountStatus.RECEIPT,
                // 처음 영수증을 받을 때의 차량 추정값을 보존한다(이미 있으면 유지)
                estimatedKwh = it.estimatedKwh ?: record.kWh.takeIf { _ -> record.isEstimated },
                estimatedAmount = it.estimatedAmount ?: record.paymentAmount.takeIf { _ -> record.isEstimated },
            )
        }
        return record.copy(kWh = kWh, unitPrice = unitPrice, paymentAmount = receipt.amount, car = meta)
    }

    /** [incoming] 상태가 [current]보다 확실하거나 같을 때만 덮어쓴다. */
    fun canOverride(current: AmountStatus, incoming: AmountStatus): Boolean = incoming.rank >= current.rank
}
