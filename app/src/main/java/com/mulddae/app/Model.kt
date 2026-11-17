package com.mulddae.app

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

data class Station(val name: String, val code: String)

/** 관측소 목록은 GitHub data/index.json 에서 받는다 (TideRepository.loadStations). */
val DEFAULT_STATION = Station("인천", "DT_0001")

data class TideEvent(val time: LocalDateTime, val high: Boolean, val heightCm: Int)

data class Mulddae(val lunarMonth: Int, val lunarDay: Int, val number: Int, val label: String)

/** 양력 → 음력 → 물때(서해 기준: 음력 1·16일=8물, 8·23일=15물(조금)). */
fun mulddaeOf(date: LocalDate): Mulddae {
    val cal = ChineseCalendar(TimeZone.getTimeZone("Asia/Seoul"))
    cal.time = Date.from(date.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant())
    val month = cal.get(ChineseCalendar.MONTH) + 1
    val day = cal.get(ChineseCalendar.DAY_OF_MONTH)
    val n = (day + 6) % 15 + 1
    val label = when (n) {
        15 -> "조금"
        14 -> "한조금"
        1 -> "무시"
        7, 8, 9 -> "사리"
        else -> ""
    }
    return Mulddae(month, day, n, label)
}

fun Mulddae.title(): String = "${number}물" + if (label.isNotEmpty()) " · $label" else ""
