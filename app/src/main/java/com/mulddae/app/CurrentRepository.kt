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
