package com.akaloopang.evauto.vehicle

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.akaloopang.evauto.core.session.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 충전소 GPS → 도로명 주소. 내비 목적지 없이 간 충전소도 사람이 알아볼 수 있게 이름 대신 쓴다.
 * 기기에 지오코더가 없거나 네트워크가 없으면 null — 그때는 좌표와 사업자로 표시하고, 사용자가 이름을 붙일 수 있다.
 */
class StationNamer(private val context: Context) {

    suspend fun address(p: GeoPoint, timeoutMs: Long = 4_000): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { lookup(p) }.onFailure { Log.w(TAG, "주소 조회 실패", it) }.getOrNull()
        }?.let(::shortAddress)
    }

    /** 시·도 이름 ("서울특별시", "경기도") — 공공데이터를 시·도 단위로 받을 때 쓴다 */
    suspend fun region(p: GeoPoint, timeoutMs: Long = 4_000): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { lookup(p) }.getOrNull()
        }?.let { a -> a.adminArea ?: a.getAddressLine(0)?.removePrefix("대한민국")?.trim() }
    }

    private suspend fun lookup(p: GeoPoint): Address? {
        val geocoder = Geocoder(context, Locale.KOREA)
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(p.lat, p.lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(p.lat, p.lon, 1)?.firstOrNull()
            }
        }
    }

    /** "대한민국 경기도 성남시 분당구 판교역로 235" → "분당구 판교역로 235" (시·군·구 + 도로명) */
    private fun shortAddress(a: Address): String? {
        val road = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").takeIf { it.isNotBlank() }
        val area = a.subLocality ?: a.locality ?: a.subAdminArea
        if (road != null) return listOfNotNull(area, road).joinToString(" ")
        return a.getAddressLine(0)?.removePrefix("대한민국")?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "StationNamer"
    }
}
