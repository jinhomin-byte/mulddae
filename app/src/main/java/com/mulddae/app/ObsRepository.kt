package com.mulddae.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 한 시각의 실측값. v = [수온, 기온, 풍속, 순간최대풍속, 풍향, 염분] (없는 값은 null). */
class ObsRow(val time: LocalDateTime, private val v: List<Double?>) {
    fun get(i: Int): Double? = v.getOrNull(i)
}

/** 오늘의 실측. inIndex=false면 실측소가 아님(66곳 밖). */
class ObsInfo(val inIndex: Boolean, val hasWtem: Boolean, val rows: List<ObsRow>)

const val OBS_WTEM = 0
const val OBS_ARTMP = 1
const val OBS_SLNT = 5

/**
 * 실측 데이터: GitHub data/obs/index.json, data/obs/<코드>/<yyyy-MM>.json.
 * TideRepository의 캐시 방식을 재사용한다(유효시간 20분, 실패 시 캐시).
 */
object ObsRepository {
    const val SOURCE_TEXT = "출처: 해양수산부 국립해양조사원, 조위관측소 최신 관측데이터 (공공데이터포털)"
    private const val MAX_AGE_MS = 20L * 60 * 1000

    /** 오늘의 실측. force면 캐시를 무시하고 다시 받는다. 해당 월 파일이 없으면 rows가 빈 정상 결과. */
    suspend fun load(c: Context, station: Station, force: Boolean = false): Result<ObsInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val age = if (force) 1L else MAX_AGE_MS
                val list = JSONObject(
                    TideRepository.fetchCached(c, "obs/index.json", "obs_index.json", age)
                ).getJSONArray("stations")
                var found: JSONObject? = null
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    if (o.optString("code") == station.code) { found = o; break }
                }
                if (found == null) return@runCatching ObsInfo(false, false, emptyList())
                val indexWtem = !found.isNull("wtemLast")

                val today = LocalDate.now()
                val month = "%04d-%02d".format(today.year, today.monthValue)
                val text = try {
                    TideRepository.fetchCached(
                        c, "obs/${station.code}/$month.json", "obs_${station.code}_$month.json", age
                    )
                } catch (e: Exception) {
                    if (e.message?.contains("(404)") == true) null else throw e
                }
                val rows = ArrayList<ObsRow>()
                val day = text?.let { JSONObject(it).optJSONObject("obs")?.optJSONObject(today.toString()) }
                if (day != null) {
                    val keys = day.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val t = runCatching { LocalTime.parse(k) }.getOrNull() ?: continue
                        val arr = day.optJSONArray(k) ?: continue
                        rows.add(ObsRow(LocalDateTime.of(today, t), values(arr)))
                    }
                    rows.sortBy { it.time }
                }
                ObsInfo(true, indexWtem || rows.any { it.get(OBS_WTEM) != null }, rows)
            }
        }

    private fun values(a: JSONArray): List<Double?> = (0 until a.length()).map { i ->
        if (a.isNull(i)) null else a.optDouble(i, Double.NaN).takeIf { !it.isNaN() }
    }
}
