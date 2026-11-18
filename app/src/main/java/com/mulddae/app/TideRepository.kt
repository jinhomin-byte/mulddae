package com.mulddae.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 데이터: GitHub 저장소의 정적 JSON (data/index.json, data/<코드>/<연도>.json).
 * 내려받은 파일은 앱 내부 저장소(filesDir/tide)에 캐시하고, 네트워크 실패 시 캐시를 쓴다.
 */
object TideRepository {
    private const val BASE = "https://raw.githubusercontent.com/jinhomin-byte/mulddae/main/data/"
    private const val INDEX_MAX_AGE_MS = 24L * 60 * 60 * 1000

    const val SOURCE_TEXT = "출처: 해양수산부 국립해양조사원, 조석예보(고, 저조) (공공데이터포털)"

    private const val RETRY_MS = 60L * 60 * 1000

    /** 마지막 load()에서 다운로드가 실패해 저장된 캐시로 대신했으면 true. */
    @Volatile
    var lastDownloadFailed = false
        private set

    // 연도 파일 재요청 제한: 캐시 이름 -> 마지막 시도 시각 (앱 프로세스 동안만 유지)
    private val lastTry = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun prefs(c: Context) = c.getSharedPreferences("mulddae", Context.MODE_PRIVATE)

    private fun cacheFile(c: Context, name: String) =
        File(File(c.filesDir, "tide").apply { mkdirs() }, name)

    fun selectedStation(c: Context): Station {
        val p = prefs(c)
        val code = p.getString("station", null) ?: return DEFAULT_STATION
        return Station(p.getString("station_name", null) ?: code, code)
    }

    fun selectStation(c: Context, s: Station) {
        prefs(c).edit().putString("station", s.code).putString("station_name", s.name).apply()
    }

    private fun download(path: String): String {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) error("다운로드 실패 (${conn.responseCode}): $path")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 캐시 파일 우선(maxAgeMs 이내), 아니면 다운로드 후 저장. 다운로드 실패 시 오래된 캐시라도 사용. */
    private fun fetchCached(c: Context, path: String, cacheName: String, maxAgeMs: Long): String {
        val f = cacheFile(c, cacheName)
        // 파싱되는 캐시만 유효. 깨진 캐시는 무시하고 다시 받는다.
        val cached = runCatching { f.readText().also { JSONObject(it) } }.getOrNull()
        if (cached != null && System.currentTimeMillis() - f.lastModified() <= maxAgeMs) return cached
        return try {
            val text = download(path)
            JSONObject(text) // 형식 확인
            val tmp = File(f.parentFile, cacheName + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) error("캐시 저장 실패: $cacheName")
            }
            text
        } catch (e: Exception) {
            if (cached != null) {
                if (maxAgeMs == 0L) lastDownloadFailed = true // 연도 파일 로딩에서만 표시
                cached
            } else throw e
        }
    }

    /** 관측소 목록 (index.json). 하루 단위로 갱신, 실패 시 캐시. */
    suspend fun loadStations(c: Context): Result<List<Station>> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONObject(fetchCached(c, "index.json", "index.json", INDEX_MAX_AGE_MS))
                .getJSONArray("stations")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Station(o.getString("name"), o.getString("code"))
            }
        }
    }

    /** 한 해 전체의 고저조(시간순). 캐시가 있으면 그것을 쓰고, 없거나 force면 내려받는다. 데이터가 없는 해는 실패(Result.failure). */
    suspend fun loadYear(
        c: Context, station: Station, year: Int, force: Boolean = false
    ): Result<List<TideEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "${station.code}/$year.json"
            val name = "${station.code}_$year.json"
            lastDownloadFailed = false
            var days = cacheFile(c, name).takeIf { it.exists() && !force }?.let {
                runCatching { JSONObject(it.readText()).getJSONObject("days") }.getOrNull()
            }
            if (days == null) {
                val now = System.currentTimeMillis()
                val last = lastTry[name]
                if (force || last == null || now - last >= RETRY_MS) {
                    lastTry[name] = now
                    days = JSONObject(fetchCached(c, path, name, 0L)).getJSONObject("days")
                } else error("데이터를 받지 못했습니다 (잠시 후 다시 시도)")
            }
            val out = ArrayList<TideEvent>()
            val keys = days!!.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val date = LocalDate.parse(key)
                val rows = days.getJSONArray(key)
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONArray(i)
                    out.add(
                        TideEvent(
                            LocalDateTime.of(date, LocalTime.parse(r.getString(0))),
                            r.getString(2) == "H", r.getInt(1)
                        )
                    )
                }
            }
            out.sortBy { it.time }
            out
        }
    }

    /** 해당 날짜의 고저조. 연도 파일만 받는다. force면 다시 내려받는다. */
    suspend fun load(
        c: Context, station: Station, date: LocalDate, force: Boolean = false
    ): Result<List<TideEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "${station.code}/${date.year}.json"
            val name = "${station.code}_${date.year}.json"
            val key = date.toString()
            lastDownloadFailed = false
            var days = cacheFile(c, name).takeIf { it.exists() && !force }?.let {
                runCatching { JSONObject(it.readText()).getJSONObject("days") }.getOrNull()
            }
            // 캐시에 해당 날짜가 없거나 force면 새로 받는다(실패하면 캐시 유지).
            // force가 아니면 같은 파일은 1시간에 한 번만 시도한다.
            if (days == null || !days.has(key)) {
                val now = System.currentTimeMillis()
                val last = lastTry[name]
                if (force || last == null || now - last >= RETRY_MS) {
                    lastTry[name] = now
                    days = JSONObject(fetchCached(c, path, name, 0L)).getJSONObject("days")
                } else if (days == null) {
                    error("데이터를 받지 못했습니다 (잠시 후 다시 시도)")
                }
            }
            if (days == null) error("데이터 없음")
            val rows = days.optJSONArray(key) ?: return@runCatching emptyList<TideEvent>()
            (0 until rows.length()).map {
                val r = rows.getJSONArray(it)
                TideEvent(
                    time = LocalDateTime.of(date, LocalTime.parse(r.getString(0))),
                    high = r.getString(2) == "H",
                    heightCm = r.getInt(1),
                )
            }.sortedBy { it.time }
        }
    }
}
