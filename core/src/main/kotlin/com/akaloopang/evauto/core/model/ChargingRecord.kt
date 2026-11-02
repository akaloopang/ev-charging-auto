package com.akaloopang.evauto.core.model

import com.akaloopang.evauto.core.pricing.PriceSource
import java.time.LocalDateTime

/** 이 기록이 어디서 왔는가. */
enum class RecordSource(val key: String) {
    /** 앱이 실행 중일 때 차량 데이터로 실시간 감지 */
    CAR_LIVE("car_live"),
    /** 앱이 꺼져 있던 동안의 충전을 시동 OFF/ON 스냅샷 비교로 찾아냄 (아파트 야간 충전) */
    CAR_INFERRED("car_inferred"),
    /** 사용자가 직접 입력 */
    MANUAL("manual"),
    /** 백업 파일에서 가져온 기록 */
    IMPORTED("imported");

    companion object {
        fun fromKey(raw: String?): RecordSource? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 금액이 얼마나 확정됐는가. 우선순위: RECEIPT > BILL_SETTLED > ESTIMATED.
 *
 * 영수증에는 사업자별 원 단위 절사·반올림이 이미 반영돼 있으므로, 영수증 금액은
 * 절대 다시 계산하지 않는다. 차량 추정값은 영수증이 들어오면 그대로 대체된다.
 */
enum class AmountStatus(val key: String, val rank: Int, val label: String) {
    ESTIMATED("estimated", 0, "추정"),
    BILL_SETTLED("bill_settled", 1, "관리비 정산"),
    RECEIPT("receipt", 2, "영수증");

    companion object {
        fun fromKey(raw: String?): AmountStatus? = entries.firstOrNull { it.key == raw }
    }
}

/** 충전 장소 판정 결과 */
enum class PlaceKind(val key: String) {
    HOME("home"), PUBLIC("public"), UNKNOWN("unknown");

    companion object {
        fun fromKey(raw: String?): PlaceKind? = entries.firstOrNull { it.key == raw }
    }
}

/** 차량 데이터로 만든 기록에만 붙는 정보 */
data class CarMeta(
    val source: RecordSource,
    val amountStatus: AmountStatus,
    val socStart: Int? = null,
    val socEnd: Int? = null,
    val endDate: LocalDateTime? = null,
    /** 차량 데이터로 추정한 충전량(과금 기준, 손실 보정 포함) */
    val estimatedKwh: Double? = null,
    /** 차량 데이터로 추정한 금액(원) */
    val estimatedAmount: Double? = null,
    /** 배터리에 실제로 들어간 에너지 추정치(손실 보정 전) — 손실계수 학습용 */
    val batteryKwh: Double? = null,
    val observedPeakKw: Double? = null,
    /** [observedPeakKw]가 차가 알려 준 값이 아니라 SOC 상승 속도로 계산한 평균 출력(최대 출력의 하한)인가 */
    val peakFromAverage: Boolean = false,
    val place: PlaceKind = PlaceKind.UNKNOWN,
    /** 사용자가 차량 화면에서 확인(1탭)했는가 */
    val confirmed: Boolean = false,
    /** 앱이 꺼진 사이 주행도 있었던 경우 등 — 충전량이 하한값일 수 있음 */
    val lowConfidence: Boolean = false,
    /** 충전소 식별 키 ([com.akaloopang.evauto.core.pricing.StationRef.key]) — 좌표는 기록에 남기지 않는다 */
    val stationKey: String? = null,
    /** 단가 출처 */
    val priceSource: PriceSource? = null,
    /** 출력 구간 (정격을 모르면 추정값) */
    val tier: PowerTier? = null,
    val tierExact: Boolean = false,
    /** 이 충전소 확인 단가가 아직 없어 '영수증 단가 1회 입력'을 안내할지 */
    val askStationPrice: Boolean = false,
    /** 확인 단가 이후 사업자 공시 요금이 바뀜 */
    val priceMaybeOutdated: Boolean = false,
    /** 단가를 빌려 온 같은 사업자 다른 충전소 이름과 확인한 날 ([PriceSource.OTHER_STATION]일 때) */
    val priceRef: String? = null,
    val priceRefDate: java.time.LocalDate? = null,
)

/**
 * 충전 기록.
 *
 * [paymentAmount]는 현재 가장 확실한 금액이다(추정이면 추정값, 영수증이면 영수증 값).
 * 상태는 [car]?.amountStatus 로 구분한다. car == null 이면 백업에서 가져온 기록이다.
 */
data class ChargingRecord(
    val id: String,
    val date: LocalDateTime,
    val provider: String,
    val kWh: Double,
    val unitPrice: Double,
    val paymentAmount: Double,
    val chargeType: ChargeType? = null,
    /** 충전기 정격 출력(kW) — 원시 숫자만 저장, 구간은 표시 시점 계산 */
    val chargeSpeedKw: Double? = null,
    val chargingMinutes: Int? = null,
    /** 누적 주행거리(km) — 전비·절감액 계산용 */
    val odometer: Double? = null,
    val memo: String? = null,
    val car: CarMeta? = null,
) {
    val amountStatus: AmountStatus
        get() = car?.amountStatus ?: AmountStatus.RECEIPT // 가져온 기록은 사용자가 확정한 값

    val isEstimated: Boolean get() = amountStatus == AmountStatus.ESTIMATED

    /** 무료 충전소(사용자가 무료로 표시한 곳)에서 한 충전 — 0원이 맞는 금액이다 (단가를 몰라 비운 0원과 다르다) */
    val isFree: Boolean
        get() = car?.priceSource == PriceSource.STATION_CONFIRMED &&
            unitPrice == 0.0 && paymentAmount == 0.0

    val powerTier: PowerTier? get() = PowerClassifier.tierFromRatedKw(chargeSpeedKw)

    /** 세션 평균 출력(kW) = 충전량 ÷ 충전시간. 정격이 아니라 하한선이다. */
    val averagePowerKw: Double?
        get() {
            val m = chargingMinutes ?: return null
            if (m <= 0 || kWh <= 0) return null
            return kWh / (m / 60.0)
        }

    companion object {
        const val APARTMENT = "아파트"

        fun calcUnitPrice(kWh: Double, payment: Double): Double = if (kWh > 0) payment / kWh else 0.0
    }
}
