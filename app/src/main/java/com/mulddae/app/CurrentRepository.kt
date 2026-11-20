package com.mulddae.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * 지금 조류 정보.
 * speedCms: 지금 유속(cm/s), dirIndex: 유향 16방위 인덱스(0=북, 시계방향, -1=모름), dirName: 한글 방위,
 * strength: 약함/보통/강함, hourlyMax: 오늘 시간별 최대 유속 24개(cm/s), maxCrsp: 지점 최대 유속(막대 높이 기준),
 * hourIndex: 지금 시각의 시간(0~23), distanceKm: 관측소에서 조류 지점까지 거리.
 */
class CurrentInfo(
    val pointName: String,
    val speedCms: Double,
    val dirIndex: Int,
    val dirName: String,
    val strength: String,
    val hourlyMax: List<Double>,
    val maxCrsp: Double,
    val hourIndex: Int,
    val distanceKm: Double
) {
    /** "0.8" 형태(m/s, 소수 1자리). 0.05 미만은 "0.0". */
    val speedMsText: String
        get() = if (speedCms < 5.0) "0.0" else "%.1f".format(Locale.US, speedCms / 100.0)
}

/**
 * 조류 예보: GitHub data/current/map.json, data/current/<지점코드>.json.
 * ObsRepository처럼 TideRepository.fetchCached를 쓴다(캐시 하루, 실패 시 캐시, 없으면 null).
 */
object CurrentRepository {
    const val SOURCE_TEXT = "출처: 해양수산부 국립해양조사원, 조류예보(시계열) (공공데이터포털)"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val DIR_NAMES = listOf(
        "북", "북북동", "북동", "동북동", "동", "동남동", "남동", "남남동",
        "남", "남남서", "남서", "서남서", "서", "서북서", "북서", "북북서"
    )

    fun dirNameOf(i: Int): String = if (i in 0..15) DIR_NAMES[i] else "방향 모름"

    /** 유속÷maxCrsp 기준 세기 말(1/3 미만 약함, 2/3 미만 보통, 그 이상 강함, maxCrsp 0이면 보통). */
    fun strengthOf(speed: Double, maxCrsp: Double): String = if (maxCrsp <= 0.0) "보통" else {
        val r = speed / maxCrsp
        if (r < 1.0 / 3) "약함" else if (r < 2.0 / 3) "보통" else "강함"
    }

    /**
     * 앱 조류 탭용 하루치. speeds(cm/s)·dirs는 10분 간격 144개, 그 날 데이터가 없으면 둘 다 null.
     */
    class Day(
        val pointName: String, val distanceKm: Double, val maxCrsp: Double,
        val speeds: List<Double>?, val dirs: List<Int>?
    )

    private const val MISSING_RETRY_MS = 30L * 60 * 1000
    private val missingMemo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun pairIn(map: JSONObject, stationCode: String): Boolean =
        map.optJSONObject("stations")?.optJSONObject(stationCode)?.optString("point").orEmpty().isNotEmpty()

    /** 캐시된 map.json만 읽어 짝 지점 유무를 바로 판단(네트워크 없음). 캐시가 없거나 깨졌으면 null. */
    fun cachedHasPair(c: Context, stationCode: String): Boolean? = runCatching {
        val f = java.io.File(java.io.File(c.filesDir, "tide"), "current_map.json")
        pairIn(JSONObject(f.readText()), stationCode)
    }.getOrNull()

    /** map.json(캐시 우선)로 짝 지점 유무 판단. 받을 수도 없으면 null(알 수 없음). */
    suspend fun hasPair(c: Context, stationCode: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            pairIn(JSONObject(TideRepository.fetchCached(c, "current/map.json", "current_map.json", MAX_AGE_MS)), stationCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 짝 지점이 없는 관측소(또는 map.json을 못 받음)는 null. 짝은 있는데 파일·날짜 데이터가 없으면 speeds가 null인 Day. */
    suspend fun dayFor(
        c: Context, stationCode: String, date: java.time.LocalDate, force: Boolean = false
    ): Day? = withContext(Dispatchers.IO) {
        try {
            val age = if (force) 1L else MAX_AGE_MS
            val map = JSONObject(TideRepository.fetchCached(c, "current/map.json", "current_map.json", age))
            val st = map.optJSONObject("stations")?.optJSONObject(stationCode) ?: return@withContext null
            val point = st.optString("point").takeIf { it.isNotEmpty() } ?: return@withContext null
            val km = st.optDouble("km", 0.0)
            val name = map.optJSONObject("points")?.optJSONObject(point)?.optString("name") ?: ""
            try {
                val cache = "current_$point.json"
                val path = "current/$point.json"
                val key = date.toString()
                val memoKey = "$point|$key"
                var file = JSONObject(TideRepository.fetchCached(c, path, cache, age))
                var day = file.optJSONObject("days")?.optJSONObject(key)
                if (day == null) {
                    val last = missingMemo[memoKey]
                    val recentlyTried = !force && last != null && System.currentTimeMillis() - last < MISSING_RETRY_MS
                    if (!force && !recentlyTried) {
                        file = runCatching { JSONObject(TideRepository.fetchCached(c, path, cache, 1L)) }.getOrDefault(file)
                        day = file.optJSONObject("days")?.optJSONObject(key)
                    }
                    // 다시 받아 봤는데도(또는 강제 새로고침 후에도) 없으면 잠시 동안 다시 받지 않는다
                    if (day == null && !recentlyTried) missingMemo[memoKey] = System.currentTimeMillis()
                } else missingMemo.remove(memoKey)
                val fileMax = file.optDouble("maxCrsp", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it }
                val s = day?.optJSONArray("s")
                if (s == null || s.length() < 144) return@withContext Day(name, km, fileMax, null, null)
                val d = day.optJSONArray("d")
                val speeds = List(144) { s.optDouble(it, 0.0).let { v -> if (v.isNaN() || v < 0) 0.0 else v } }
                val dirs = List(144) { i -> (d?.optInt(i, -1) ?: -1).let { if (it in 0..15) it else -1 } }
                // 파일에 maxCrsp가 없으면 그 날 최대값을 기준값으로 쓴다(막대·세기 말·이벤트 공통)
                val maxCrsp = if (fileMax > 0) fileMax else (speeds.maxOrNull() ?: 0.0)
                Day(name, km, maxCrsp, speeds, dirs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Day(name, km, 0.0, null, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 조류가 없는 관측소·데이터 없음·오류는 모두 null(조용히 숨김). force면 캐시를 무시하고 새로 받는다. */
    suspend fun infoFor(
        c: Context, stationCode: String, now: LocalDateTime = LocalDateTime.now(), force: Boolean = false
    ): CurrentInfo? = withContext(Dispatchers.IO) {
        try {
            val age = if (force) 1L else MAX_AGE_MS
            val map = JSONObject(TideRepository.fetchCached(c, "current/map.json", "current_map.json", age))
            val st = map.optJSONObject("stations")?.optJSONObject(stationCode) ?: return@withContext null
            val point = st.optString("point").takeIf { it.isNotEmpty() } ?: return@withContext null
            val km = st.optDouble("km", 0.0)
            val name = map.optJSONObject("points")?.optJSONObject(point)?.optString("name") ?: ""

            val cache = "current_$point.json"
            val path = "current/$point.json"
            var file = JSONObject(TideRepository.fetchCached(c, path, cache, age))
            var day = file.optJSONObject("days")?.optJSONObject(now.toLocalDate().toString())
            if (day == null && !force) { // 캐시가 낡아 오늘이 없으면 한 번 새로 받아 본다
                file = runCatching { JSONObject(TideRepository.fetchCached(c, path, cache, 1L)) }.getOrDefault(file)
                day = file.optJSONObject("days")?.optJSONObject(now.toLocalDate().toString())
            }
            day ?: return@withContext null
            val s = day.optJSONArray("s") ?: return@withContext null
            val d = day.optJSONArray("d")
            if (s.length() < 144) return@withContext null

            val speeds = List(144) { s.optDouble(it, 0.0).let { v -> if (v.isNaN() || v < 0) 0.0 else v } }
            val hourly = List(24) { h -> (0 until 6).maxOf { speeds[h * 6 + it] } }
            val idx = ((now.hour * 60 + now.minute + 5) / 10).coerceIn(0, 143)
            val speed = speeds[idx]
            val dir = d?.optInt(idx, -1) ?: -1
            val dirIdx = if (dir in 0..15) dir else -1
            val maxCrsp = file.optDouble("maxCrsp", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it }
            val strength = if (maxCrsp <= 0.0) "보통" else {
                val r = speed / maxCrsp
                if (r < 1.0 / 3) "약함" else if (r < 2.0 / 3) "보통" else "강함"
            }
            CurrentInfo(
                name, speed, dirIdx, if (dirIdx >= 0) DIR_NAMES[dirIdx] else "방향 모름",
                strength, hourly, maxCrsp, now.hour, km
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
