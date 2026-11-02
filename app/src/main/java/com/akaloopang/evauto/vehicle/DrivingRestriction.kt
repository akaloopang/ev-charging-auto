package com.akaloopang.evauto.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행 중 UX 제한 상태. true면 입력·긴 목록을 숨기고 요약 카드만 보여준다.
 * (Pleos Connect 주행 안전 설계 가이드 · AAOS 운전자 주의 분산 기준)
 */
class DrivingRestriction(private val context: Context) {

    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    /** 차량 서비스는 기다리지 않고 연결한다(메인 스레드를 막지 않음). 서비스가 재시작돼도 앱이 종료되지 않는다. */
    @SuppressLint("NewApi")
    fun start() {
        // 리스너도 여기서 만든다: android.car가 없는 기기(일반 폰 AVD)에서는 클래스 로딩부터 실패하므로
        // 필드로 두면 객체 생성 시 앱이 죽는다. 여기서는 실패해도 '제한 없음'으로 동작.
        runCatching {
            car = if (Build.VERSION.SDK_INT >= 30) {
                Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c: Car, ready: Boolean ->
                    if (ready) onCarReady(c) else manager = null
                    Unit
                }
            } else {
                Car.createCar(context.applicationContext)?.also { onCarReady(it) }
            }
        }.onFailure { Log.w("DrivingRestriction", "UX 제한 정보를 받을 수 없음", it) }
    }

    private fun onCarReady(c: Car) {
        runCatching {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            _restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
            m.registerListener(
                CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r: CarUxRestrictions ->
                    _restricted.value = r.isRequiresDistractionOptimization
                },
            )
        }.onFailure { Log.w("DrivingRestriction", "UX 제한 구독 실패", it) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }
}
