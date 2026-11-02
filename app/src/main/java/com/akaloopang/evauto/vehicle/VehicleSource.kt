package com.akaloopang.evauto.vehicle

import com.akaloopang.evauto.core.session.DriveInfo
import com.akaloopang.evauto.core.session.VehicleSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/**
 * 차량 상태 공급원. core 모듈은 이 인터페이스 너머의 SDK를 모른다.
 */
interface VehicleSource {
    /** 의미 있는 변화가 있을 때마다 스냅샷을 내보낸다 */
    val snapshots: SharedFlow<VehicleSnapshot>

    /** 주행 정보 (주행가능거리·이번 주행 거리·전비) — 예상 충전비·주행 비용에 쓴다 */
    val drive: StateFlow<DriveInfo>

    /** 연결 상태 한 줄 (어느 신호를 받고 있는지 — 개발용 화면에 표시) */
    val connection: StateFlow<String>

    fun start()
    fun stop()

    /** 지금 상태. SOC를 받을 때까지 최대 [timeoutMs] 기다리고, 못 받으면 아는 값만 담아 돌려준다 */
    suspend fun current(timeoutMs: Long = 8_000): VehicleSnapshot
}

/**
 * 차량 신호가 없는 기기(일반 휴대폰 AVD 등)용. 아무것도 내보내지 않는다 — 화면 확인·시연 데이터 확인용.
 * 실제 차량(AAOS + Pleos)에서는 [PleosVehicleSource]를 쓴다.
 */
object NoVehicleSource : VehicleSource {
    override val snapshots: SharedFlow<VehicleSnapshot> = MutableSharedFlow()
    override val drive: StateFlow<DriveInfo> = MutableStateFlow(DriveInfo())
    override val connection: StateFlow<String> = MutableStateFlow("차량 신호 없음 (차량이 아닌 기기)")
    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun current(timeoutMs: Long): VehicleSnapshot = VehicleSnapshot(time = LocalDateTime.now())
}
