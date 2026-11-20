package com.mulddae.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 위젯 색상 테마 번호(0~4)를 Glance 상태에 저장하는 키. */
val WIDGET_THEME_KEY = intPreferencesKey("widget_theme")

/** 위젯 투명도(0=불투명, 100=완전 투명)를 Glance 상태에 저장하는 키. */
val WIDGET_TRANSPARENCY_KEY = intPreferencesKey("widget_transparency")

class TideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val station = TideRepository.selectedStation(context)
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val yesterday = TideRepository.load(context, station, today.minusDays(1)).getOrDefault(emptyList())
        val todayEvents = TideRepository.load(context, station, today).getOrDefault(emptyList())
        val tomorrow = TideRepository.load(context, station, today.plusDays(1)).getOrDefault(emptyList())
        val all = (yesterday + todayEvents + tomorrow).sortedBy { it.time }
        val m = mulddaeOf(today)
        val fallbackTransparency = loadWidgetTransparency(context)

        // 세로 범위: 올해 연중 최소·최대(앱과 같은 방식), 없으면 그날 값
        val yearEvents = TideRepository.loadYear(context, station, today.year).getOrDefault(emptyList())
        val rangeSrc = yearEvents.ifEmpty { all }
        val yMin = if (rangeSrc.isEmpty()) -100 else Math.floorDiv(rangeSrc.minOf { it.heightCm } - 10, 100) * 100
        val yMax = if (rangeSrc.isEmpty()) 1000 else -Math.floorDiv(-(rangeSrc.maxOf { it.heightCm } + 10), 100) * 100

        val next = all.firstOrNull { it.time.isAfter(now) }
        val fallbackTheme = loadTheme(context)

        // 칸: 4개 이하면 전부, 초과면 다음 것 위주 4개
        val nextIdx = todayEvents.indexOfFirst { it.time.isAfter(now) }.let { if (it < 0) todayEvents.size else it }
        val start = if (todayEvents.size <= 4) 0 else minOf(nextIdx, todayEvents.size - 4)
        val slots = todayEvents.drop(start).take(4)

        provideContent {
            val prefs = currentState<Preferences>()
            val pal = WIDGET_PALETTES[(prefs[WIDGET_THEME_KEY] ?: fallbackTheme).coerceIn(0, THEME_COUNT - 1)]
            val white = ColorProvider(pal.fg)
            val sub = ColorProvider(pal.sub)
            val faint = ColorProvider(pal.fg.copy(alpha = 0.10f))
            val bitmap = if (todayEvents.isNotEmpty()) drawCurve(all, today, now, yMin, yMax, pal) else null
            val transparency = (prefs[WIDGET_TRANSPARENCY_KEY] ?: fallbackTransparency).coerceIn(0, 100)
            val bgMod = GlanceModifier.background(ColorProvider(pal.bg.copy(alpha = (100 - transparency) / 100f)))
            Column(
                GlanceModifier.fillMaxSize()
                    .then(bgMod)
                    .cornerRadius(20.dp)
                    .padding(10.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                // 윗줄
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            station.name,
                            style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            " · 음력 ${m.lunarMonth}.${m.lunarDay}",
                            style = TextStyle(color = sub, fontSize = 12.sp),
                            maxLines = 1
                        )
                    }
                    Text(
                        "${m.number}물",
                        style = TextStyle(color = ColorProvider(pal.accent), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Box(
                        GlanceModifier.size(26.dp)
                            .background(faint)
                            .cornerRadius(13.dp)
                            .clickable(actionRunCallback<RefreshCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↻", style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    }
                }

                if (todayEvents.isEmpty() || bitmap == null) {
                    Spacer(GlanceModifier.height(8.dp))
                    Text("데이터 없음 (새로고침 눌러 보세요)", style = TextStyle(color = sub, fontSize = 12.sp))
                } else {
                    Spacer(GlanceModifier.height(4.dp))
                    // 알약
                    if (next != null) {
                        val falling = !next.high
                        val tc = if (falling) pal.down else pal.accent
                        Row(
                            GlanceModifier.fillMaxWidth()
                                .background(faint)
                                .cornerRadius(12.dp)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (falling) "날물 ▼" else "들물 ▲",
                                style = TextStyle(color = ColorProvider(tc), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                "${if (next.time.toLocalDate().isAfter(today)) "내일 " else ""}${if (next.high) "만조" else "간조"} %02d:%02d · %s".format(
                                    next.time.hour, next.time.minute, remainText(now, next.time)
                                ),
                                style = TextStyle(color = white, fontSize = 12.sp),
                                maxLines = 1
                            )
                        }
                    }
                    // 곡선
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = "오늘 조석 곡선",
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                    )
                    // 고저조 칸
                    Row(GlanceModifier.fillMaxWidth()) {
                        slots.forEach { e ->
                            val past = !e.time.isAfter(now)
                            val isNext = next != null && e.time == next.time
                            val a = if (past) 0.5f else 1f
                            val c = (if (e.high) pal.accent else pal.down).copy(alpha = a)
                            val mod = GlanceModifier.defaultWeight().padding(vertical = 2.dp).let {
                                if (isNext) it.background(faint).cornerRadius(8.dp)
                                else it
                            }
                            Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (e.high) "▲ 만조" else "▼ 간조",
                                    style = TextStyle(color = ColorProvider(c), fontSize = 10.sp, textAlign = TextAlign.Center)
                                )
                                Text(
                                    "%02d:%02d".format(e.time.hour, e.time.minute),
                                    style = TextStyle(
                                        color = ColorProvider(pal.fg.copy(alpha = a)),
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                                    )
                                )
                                Text(
                                    "${e.heightCm}cm",
                                    style = TextStyle(
                                        color = ColorProvider(pal.sub.copy(alpha = a)), fontSize = 10.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun remainText(now: LocalDateTime, t: LocalDateTime): String {
    val min = Duration.between(now, t).toMinutes().coerceAtLeast(0)
    return if (min < 60) "${min}분 후"
    else if (min % 60 == 0L) "${min / 60}시간 후"
    else "${min / 60}시간 ${min % 60}분 후"
}

/** 앱 조위 탭과 같은 식(고저조 사이 코사인)으로 오늘 0~24시를 그린 비트맵. */
private fun drawCurve(events: List<TideEvent>, day: LocalDate, now: LocalDateTime, yMin: Int, yMax: Int, pal: WidgetPalette): Bitmap {
    val w = 600
    val h = 120
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val padY = 10f
    val range = maxOf(1, yMax - yMin).toFloat()
    fun yOf(v: Double) = padY + (h - 2 * padY) * (1f - ((v - yMin) / range).toFloat())
    val start = day.atStartOfDay()

    val line = Path()
    val fill = Path()
    var started = false
    var lastX = 0f
    for (px in 0..w step 3) {
        val t = start.plusSeconds((px / w.toDouble() * 24 * 3600).toLong())
        val hv = tideHeightAt(events, t) ?: continue
        val y = yOf(hv)
        if (!started) {
            line.moveTo(px.toFloat(), y); fill.moveTo(px.toFloat(), h.toFloat()); fill.lineTo(px.toFloat(), y)
            started = true
        } else {
            line.lineTo(px.toFloat(), y); fill.lineTo(px.toFloat(), y)
        }
        lastX = px.toFloat()
    }
    if (started) {
        fill.lineTo(lastX, h.toFloat()); fill.close()
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                pal.accent.copy(alpha = 0.22f).toArgb(), pal.accent.copy(alpha = 0.22f).toArgb(), android.graphics.Shader.TileMode.CLAMP
            )
        }
        cv.drawPath(fill, fp)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = pal.accent.toArgb()
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        cv.drawPath(line, lp)
    }

    // 지금 시각 세로선과 점
    if (now.toLocalDate() == day) {
        val x = (Duration.between(start, now).seconds / (24 * 3600.0) * w).toFloat()
        val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.fg.copy(alpha = 0.55f).toArgb(); strokeWidth = 2f }
        cv.drawLine(x, 0f, x, h.toFloat(), vp)
        tideHeightAt(events, now)?.let {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.down.toArgb() }
            cv.drawCircle(x, yOf(it), 7f, dp)
        }
    }
    return bmp
}

/** 새로고침 버튼: 앱을 열지 않고 조석을 다시 받아 위젯을 다시 그린다. 실패해도 캐시로 그린다. */
class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!running.compareAndSet(false, true)) return // 누르는 동안 중복 실행 방지
        try {
            try {
                TideRepository.load(context, TideRepository.selectedStation(context), LocalDate.now(), force = true)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 실패해도 캐시로 그린다
            }
            TideWidget().updateAll(context)
        } finally {
            running.set(false)
        }
    }

    companion object {
        private val running = AtomicBoolean(false)
    }
}

class TideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TideWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 1시간 주기(기존 24시간 주기였다면 새 주기로 교체)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "tide_widget_refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS).build()
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork("tide_widget_refresh")
    }
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // 1시간마다: 캐시로 다시 그린다("N분 후"·지금선 갱신). 캐시에 오늘이 없으면 load가 내려받는다.
        val r = TideRepository.load(
            applicationContext, TideRepository.selectedStation(applicationContext), LocalDate.now()
        )
        val failed = r.isFailure
        TideWidget().updateAll(applicationContext)
        return if (failed) Result.retry() else Result.success()
    }
}
