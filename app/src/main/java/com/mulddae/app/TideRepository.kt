package com.mulddae.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TideRepository {
    private const val ENDPOINT =
        "https://apis.data.go.kr/1192136/tideFcstHghLw/GetTideFcstHghLwApiService"
    private val DAY = DateTimeFormatter.BASIC_ISO_DATE
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun prefs(c: Context) = c.getSharedPreferences("mulddae", Context.MODE_PRIVATE)

    fun selectedStation(c: Context): Station {
        val code = prefs(c).getString("station", null)
        return STATIONS.firstOrNull { it.code == code } ?: STATIONS.first()
    }

    fun selectStation(c: Context, s: Station) {
        prefs(c).edit().putString("station", s.code).apply()
    }

    /** 캐시 우선, 없으면(또는 force) 네트워크. 실패 시 Result.failure. */
    suspend fun load(
        c: Context, station: Station, date: LocalDate, force: Boolean = false
    ): Result<List<TideEvent>> = withContext(Dispatchers.IO) {
        val key = "${station.code}_${date.format(DAY)}"
        if (!force) prefs(c).getString(key, null)?.let { return@withContext Result.success(decode(it)) }
        runCatching {
            val events = fetch(station, date)
            prefs(c).edit().putString(key, encode(events)).apply()
            events
        }.recoverCatching { e ->
            prefs(c).getString(key, null)?.let { decode(it) } ?: throw e
        }
    }

    private fun fetch(station: Station, date: LocalDate): List<TideEvent> {
        val apiKey = BuildConfig.TIDE_API_KEY
        require(apiKey.isNotBlank()) { "API 키가 없습니다 (local.properties의 TIDE_API_KEY)" }
        val url = URL(
            "$ENDPOINT?serviceKey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&type=json&obsCode=${station.code}&reqDate=${date.format(DAY)}&numOfRows=20&pageNo=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (!body.trimStart().startsWith("{")) error("API 응답 오류: ${body.take(120)}")
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    // 응답: response.body.items.item[] { predcDt, extrSe, tdlvHgt }
    // extrSe: 1·3 = 고조(만조), 2·4 = 저조(간조)  (활용가이드로 확인 필요)
    private fun parse(json: String): List<TideEvent> {
        val root = JSONObject(json).getJSONObject("response")
        val header = root.getJSONObject("header")
        if (header.optString("resultCode") != "00") error("API 오류: ${header.optString("resultMsg")}")
        val items = root.getJSONObject("body").optJSONObject("items")?.opt("item") ?: return emptyList()
        val arr = if (items is JSONArray) items else JSONArray().put(items)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            TideEvent(
                time = LocalDateTime.parse(it.getString("predcDt").take(16), TS),
                high = it.getString("extrSe").toInt() % 2 == 1,
                heightCm = it.getString("tdlvHgt").toDouble().toInt(),
            )
        }.sortedBy { it.time }
    }

    private fun encode(e: List<TideEvent>) =
        e.joinToString(";") { "${it.time}|${if (it.high) 1 else 0}|${it.heightCm}" }

    private fun decode(s: String): List<TideEvent> =
        if (s.isBlank()) emptyList() else s.split(";").map {
            val p = it.split("|")
            TideEvent(LocalDateTime.parse(p[0]), p[1] == "1", p[2].toInt())
        }
}
