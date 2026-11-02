package com.akaloopang.evauto.publicdata

import android.content.Context
import android.util.Log
import com.akaloopang.evauto.BuildConfig
import com.akaloopang.evauto.core.publicdata.EvChargerApi
import com.akaloopang.evauto.core.publicdata.FuelPrice
import com.akaloopang.evauto.core.publicdata.Opinet
import com.akaloopang.evauto.core.publicdata.PublicCache
import com.akaloopang.evauto.core.publicdata.PublicCharger
import com.akaloopang.evauto.core.publicdata.PublicStation
import com.akaloopang.evauto.core.publicdata.RegionCode
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.session.GeoPoint
import com.akaloopang.evauto.data.Repository
import com.akaloopang.evauto.vehicle.StationNamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime

/**
 * 공공데이터 연결 — 키가 있을 때만 켜진다(없으면 화면에 '키를 넣으면 쓸 수 있어요'만 보인다).
 *
 *  - 한국환경공단 충전소 정보: 내 충전소 이름·운영기관·출력 확인, 지금 빈 충전기 수
 *  - 오피넷 전국 평균 유가: 내연기관 비교의 연료 가격을 오늘 값으로
 *
 * 개인정보: 내 위치·충전 기록은 보내지 않는다. 시·도 코드로 목록을 받아 기기 안에서 내 충전소 주변만 골라 저장한다.
 * 키: local.properties의 evCharger.key / opinet.key → BuildConfig (저장소에 올리지 않는다).
 */
class PublicDataClient(context: Context, private val repo: Repository) {

    private val evKey: String? = BuildConfig.EV_CHARGER_KEY.takeIf { it.isNotBlank() }
    private val opinetKey: String? = BuildConfig.OPINET_KEY.takeIf { it.isNotBlank() }

    val chargerEnabled: Boolean get() = evKey != null
    val fuelEnabled: Boolean get() = opinetKey != null

    private val namer = StationNamer(context)
    private val prefs = context.getSharedPreferences("public_data", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "public_chargers.json")
    private val lock = Mutex()

    private val _cache = MutableStateFlow(runCatching { cacheFile.takeIf { it.exists() }?.readText()?.let(PublicCache::decode) }.getOrNull())
    val cache: StateFlow<PublicCache?> = _cache.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** 마지막 조회 결과 한 줄 (오류·건수) */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _region = MutableStateFlow(prefs.getString(KEY_REGION, null))
    /** 사용자가 고른 시·도 코드 (없으면 충전소 주소로 정한다) */
    val region: StateFlow<String?> = _region.asStateFlow()

    fun setRegion(code: String?) {
        prefs.edit().putString(KEY_REGION, code).apply()
        _region.value = code
    }

    /** 내 충전소 좌표들 (공공데이터를 이 주변만 남긴다) */
    private fun myPoints(): List<GeoPoint> =
        (repo.stations.value.mapNotNull { it.location } + listOfNotNull(repo.car.value.home?.center, repo.monitor.value.lastSeen?.location))
            .distinctBy { "%.3f,%.3f".format(it.lat, it.lon) }

    /** 시·도 코드 후보: 사용자가 고른 것 > 내 충전소(없으면 마지막 위치) 주소의 시·도 */
    private suspend fun regionCodes(points: List<GeoPoint>): List<String> {
        _region.value?.let { code -> return RegionCode.choices.firstOrNull { code in it.second }?.second ?: listOf(code) }
        for (p in points.take(3)) {
            val codes = RegionCode.fromAddress(namer.region(p))
            if (codes.isNotEmpty()) return codes
        }
        return emptyList()
    }

    /**
     * 충전소 목록·상태 새로 받기.
     *  - 마지막 목록이 [STATUS_ONLY_WITHIN] 안이면 바뀐 상태만(getChargerStatus)
     *  - 아니면 시·도 목록 전체(getChargerInfo, 여러 쪽)를 받아 내 충전소 주변만 남긴다
     */
    suspend fun refresh(forceFull: Boolean = false): Boolean = lock.withLock {
        val key = evKey ?: run { _message.value = "공공데이터 키가 없어요"; return false }
        val points = myPoints()
        if (points.isEmpty()) { _message.value = "위치가 남은 충전소가 아직 없어요"; return false }
        _busy.value = true
        try {
            val now = LocalDateTime.now()
            val old = _cache.value
            if (!forceFull && old != null && Duration.between(old.infoAt, now) < INFO_MAX_AGE &&
                Duration.between(old.statusAt, now) < STATUS_ONLY_WITHIN
            ) {
                val updates = fetchAll { page -> EvChargerApi.statusUrl(key, old.zcode, EvChargerApi.STATUS_PERIOD_MIN, page) }
                    ?: return false
                save(old.copy(statusAt = now, chargers = EvChargerApi.applyStatus(old.chargers, updates)))
                _message.value = "빈 충전기 상태를 새로 받았어요"
                return true
            }
            val codes = regionCodes(points)
            if (codes.isEmpty()) { _message.value = "지역을 알 수 없어요. 아래에서 시·도를 골라 주세요"; return false }
            for (z in codes) {
                val all = fetchAll(maxPages = MAX_PAGES) { page -> EvChargerApi.infoUrl(key, z, page) } ?: return false
                if (all.isEmpty()) continue // 옛 시·도 코드일 수 있어 다음 후보로
                val near = EvChargerApi.near(all, points)
                save(PublicCache(z, now, now, near))
                _message.value = "내 충전소 주변 충전기 ${near.size}대를 받았어요"
                return true
            }
            _message.value = "받은 충전소가 없어요"
            false
        } finally {
            _busy.value = false
        }
    }

    /** 내 충전소와 같은 자리(150m)의 공공데이터 충전소 */
    fun stationFor(ref: StationRef?): PublicStation? {
        val at = ref?.location ?: return null
        val stations = _cache.value?.stations ?: return null
        return EvChargerApi.nearest(stations, at)
    }

    /** 오늘 전국 평균 유가 */
    suspend fun fuelPrices(): List<FuelPrice> {
        val key = opinetKey ?: run { _message.value = "오피넷 키가 없어요"; return emptyList() }
        _busy.value = true
        return try {
            val text = get(Opinet.averageUrl(key)) ?: return emptyList()
            Opinet.parseAverage(text).also { if (it.isEmpty()) _message.value = "유가를 읽지 못했어요" }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun fetchAll(maxPages: Int = MAX_PAGES, url: (Int) -> String): List<PublicCharger>? {
        val out = mutableListOf<PublicCharger>()
        var page = 1
        while (page <= maxPages) {
            val text = get(url(page)) ?: return null
            val p = EvChargerApi.parse(text)
            if (!p.ok) {
                _message.value = "공공데이터 오류: ${p.resultMsg ?: p.resultCode}"
                return null
            }
            out += p.items
            if (!p.hasMore || p.items.isEmpty()) break
            page++
        }
        return out
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }.onFailure {
            Log.w(TAG, "조회 실패", it)
            _message.value = "인터넷 연결을 확인해 주세요 (${it.message ?: it.javaClass.simpleName})"
        }.getOrNull()
    }

    private suspend fun save(c: PublicCache) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(c.encode())
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        }.onFailure { Log.w(TAG, "저장 실패", it) }
        _cache.value = c
    }

    companion object {
        private const val TAG = "PublicData"
        private const val KEY_REGION = "region"
        /** 한 시·도에서 받는 최대 쪽 수 (쪽당 9,999대) */
        private const val MAX_PAGES = 15
        /** 충전소 목록은 일주일마다 */
        private val INFO_MAX_AGE: Duration = Duration.ofDays(7)
        /** 마지막 상태 갱신이 이보다 최근이면 바뀐 상태만 받는다 */
        private val STATUS_ONLY_WITHIN: Duration = Duration.ofMinutes(EvChargerApi.STATUS_PERIOD_MIN.toLong())
    }
}
