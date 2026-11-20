package com.mulddae.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.glance.layout.fillMaxHeight
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 위젯 색상 테마 번호(0~4)를 Glance 상태에 저장하는 키. */
val WIDGET_THEME_KEY = intPreferencesKey("widget_theme")

/** 위젯 투명도(0=불투명, 100=완전 투명)를 Glance 상태에 저장하는 키. */
val WIDGET_TRANSPARENCY_KEY = intPreferencesKey("widget_transparency")

/** 마지막 강제 갱신 시각(epoch ms). 상태를 바꿔 살아 있는 세션도 다시 그리게 하는 용도. */
val WIDGET_REFRESH_KEY = androidx.datastore.preferences.core.longPreferencesKey("widget_refresh")

/** 위젯이 표시 중인 관측소 코드. 바뀌면 살아 있는 세션이 데이터를 다시 읽는다. */
val WIDGET_STATION_KEY = androidx.datastore.preferences.core.stringPreferencesKey("widget_station")

private val SMALL_SIZE = DpSize(250.dp, 110.dp) // 4x2
private const val SEA_COL_MIN_WIDTH_DP = 270f // 이 폭 이상이면 오른쪽에 바다 정보 칸 표시(폰 5칸=289dp)
private const val SEA_COL_WIDTH_DP = 80 // 오른쪽 바다 정보 칸 폭
private const val SEA_COL_GAP_DP = 8 // 곡선 영역과 오른쪽 칸 사이 간격
private const val CURVE_OVERHEAD_DP = 125f // 곡선 외 고정 높이(윗줄·알약·4칸·패딩, 에뮬레이터 측정값)

/** 위젯 오른쪽 칸에 보여줄 가장 최근 실측값. */
private class SeaInfo(val wtem: Double?, val artmp: Double?, val wspd: Double?, val time: LocalDateTime)

private fun latestSea(info: ObsInfo?): SeaInfo? {
    val rows = info?.rows ?: return null
    if (rows.isEmpty()) return null
    fun last(i: Int) = rows.lastOrNull { it.get(i) != null }?.get(i)
    val s = SeaInfo(last(OBS_WTEM), last(OBS_ARTMP), last(OBS_WSPD), rows.last().time)
    return if (s.wtem == null && s.artmp == null && s.wspd == null) null else s
}

/** 위젯이 그릴 데이터 묶음(관측소 이름과 값을 함께 교체하려고 한 덩어리로 둔다). */
private class WidgetData(
    val station: Station,
    val today: LocalDate,
    val now: LocalDateTime,
    val all: List<TideEvent>,
    val todayEvents: List<TideEvent>,
    val slots: List<TideEvent>,
    val yMin: Int,
    val yMax: Int,
    val sea: SeaInfo?
)

private suspend fun loadWidgetData(context: Context, station: Station): WidgetData {
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val yesterday = TideRepository.load(context, station, today.minusDays(1)).getOrDefault(emptyList())
    val todayEvents = TideRepository.load(context, station, today).getOrDefault(emptyList())
    val tomorrow = TideRepository.load(context, station, today.plusDays(1)).getOrDefault(emptyList())
    val all = (yesterday + todayEvents + tomorrow).sortedBy { it.time }

    // 세로 범위: 올해 연중 최소·최대(앱과 같은 방식), 없으면 그날 값
    val yearEvents = TideRepository.loadYear(context, station, today.year).getOrDefault(emptyList())
    val rangeSrc = yearEvents.ifEmpty { all }
    val yMin = if (rangeSrc.isEmpty()) -100 else Math.floorDiv(rangeSrc.minOf { it.heightCm } - 10, 100) * 100
    val yMax = if (rangeSrc.isEmpty()) 1000 else -Math.floorDiv(-(rangeSrc.maxOf { it.heightCm } + 10), 100) * 100

    // 칸: 4개 이하면 전부, 초과면 다음 것 위주 4개
    val nextIdx = todayEvents.indexOfFirst { it.time.isAfter(now) }.let { if (it < 0) todayEvents.size else it }
    val start = if (todayEvents.size <= 4) 0 else minOf(nextIdx, todayEvents.size - 4)
    val slots = todayEvents.drop(start).take(4)

    // 실측은 한 번만 읽고 재사용(실패하면 오른쪽 칸 숨김)
    val sea = try {
        latestSea(ObsRepository.load(context, station).getOrNull())
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return WidgetData(station, today, now, all, todayEvents, slots, yMin, yMax, sea)
}

class TideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallbackTransparency = loadWidgetTransparency(context)
        val fallbackTheme = loadTheme(context)
        // 첫 그리기용 초기값(동기 계산). 이후 관측소·새로고침 키가 바뀌면 content 안에서 다시 읽는다.
        val initial = loadWidgetData(context, TideRepository.selectedStation(context))

        provideContent {
            val prefs = currentState<Preferences>()
            val stationKey = prefs[WIDGET_STATION_KEY]
            val refreshKey = prefs[WIDGET_REFRESH_KEY]
            val firstRefreshKey = remember { refreshKey }
            var data by remember { mutableStateOf(initial) }
            LaunchedEffect(stationKey, refreshKey) {
                if ((stationKey != null && stationKey != data.station.code) || refreshKey != firstRefreshKey) {
                    val st = TideRepository.selectedStation(context)
                    val fresh = withContext(Dispatchers.IO) { loadWidgetData(context, st) }
                    data = fresh // 이름과 데이터를 한 번에 교체
                }
            }
            val station = data.station
            val today = data.today
            val now = data.now
            val todayEvents = data.todayEvents
            val all = data.all
            val m = mulddaeOf(today)
            val yMin = data.yMin
            val yMax = data.yMax
            val next = all.firstOrNull { it.time.isAfter(now) }
            val slots = data.slots
            val sea = data.sea
            val size = LocalSize.current // Exact 모드: 위젯의 실제 크기
            val showSea = sea != null && size.width.value >= SEA_COL_MIN_WIDTH_DP
            // 오른쪽 칸이 들어가면 왼쪽이 좁아지므로 글자·패딩을 조금 줄인다
            val pillSp = if (showSea) 11.sp else 12.sp
            val pillPad = if (showSea) 6.dp else 10.dp
            val slotTimeSp = if (showSea) 13.sp else 15.sp
            val slotSubSp = if (showSea) 9.sp else 10.sp
            val pal = WIDGET_PALETTES[(prefs[WIDGET_THEME_KEY] ?: fallbackTheme).coerceIn(0, THEME_COUNT - 1)]
            val white = ColorProvider(pal.fg)
            val sub = ColorProvider(pal.sub)
            val faint = ColorProvider(pal.fg.copy(alpha = 0.10f))
            // 곡선 영역 크기(폭: 위젯 폭 - 좌우 패딩 - 오른쪽 칸, 높이: 위젯 높이 - 고정 높이)에 맞춰 그려 점이 원으로 보이게 한다
            val curveWDp = size.width.value - 20f - (if (showSea) (SEA_COL_WIDTH_DP + SEA_COL_GAP_DP).toFloat() else 0f)
            val curveHDp = (size.height.value - CURVE_OVERHEAD_DP).coerceIn(30f, 120f)
            val bitmap = if (todayEvents.isNotEmpty()) {
                drawCurve(all, today, now, yMin, yMax, pal, (curveWDp * 2.6f).toInt().coerceAtLeast(100), (curveHDp * 2.6f).toInt())
            } else null
            val transparency = (prefs[WIDGET_TRANSPARENCY_KEY] ?: fallbackTransparency).coerceIn(0, 100)
            val bgMod = GlanceModifier.background(ColorProvider(pal.bg.copy(alpha = (100 - transparency) / 100f)))
            Row(
                GlanceModifier.fillMaxSize()
                    .then(bgMod)
                    .cornerRadius(20.dp)
                    .padding(10.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
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
                                .padding(horizontal = pillPad, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (falling) "날물 ▼" else "들물 ▲",
                                style = TextStyle(color = ColorProvider(tc), fontSize = pillSp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = if (showSea) 1 else Int.MAX_VALUE
                            )
                            Text(
                                "${if (next.time.toLocalDate().isAfter(today)) "내일 " else ""}${if (showSea) "" else if (next.high) "만조 " else "간조 "}%02d:%02d · %s".format(
                                    next.time.hour, next.time.minute, remainText(now, next.time)
                                ),
                                style = TextStyle(color = white, fontSize = pillSp),
                                maxLines = 1
                            )
                        }
                    }
                    // 곡선
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = "오늘 조석 곡선",
                        contentScale = ContentScale.Fit,
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
                                    style = TextStyle(color = ColorProvider(c), fontSize = slotSubSp, textAlign = TextAlign.Center)
                                )
                                Text(
                                    "%02d:%02d".format(e.time.hour, e.time.minute),
                                    style = TextStyle(
                                        color = ColorProvider(pal.fg.copy(alpha = a)),
                                        fontSize = slotTimeSp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                                    )
                                )
                                Text(
                                    "${e.heightCm}cm",
                                    style = TextStyle(
                                        color = ColorProvider(pal.sub.copy(alpha = a)), fontSize = slotSubSp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (showSea) {
                Spacer(GlanceModifier.width(SEA_COL_GAP_DP.dp))
                Column(GlanceModifier.width(SEA_COL_WIDTH_DP.dp).fillMaxHeight()) {
                    @androidx.compose.runtime.Composable
                    fun item(label: String, value: String, color: Color) {
                        Text(label, style = TextStyle(color = sub, fontSize = 10.sp), maxLines = 1)
                        Text(
                            value,
                            style = TextStyle(color = ColorProvider(color), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                    }
                    Column(GlanceModifier.defaultWeight()) {
                        if (sea.wtem != null) item("수온", "%.1f°C".format(Locale.US, sea.wtem), pal.accent)
                        if (sea.artmp != null) item("기온", "%.1f°C".format(Locale.US, sea.artmp), pal.down)
                        if (sea.wspd != null) item("풍속", "%.1f m/s".format(Locale.US, sea.wspd), pal.fg)
                    }
                    Text(
                        "관측 %02d:%02d".format(sea.time.hour, sea.time.minute),
                        style = TextStyle(color = sub, fontSize = 10.sp), maxLines = 1
                    )
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
private fun drawCurve(events: List<TideEvent>, day: LocalDate, now: LocalDateTime, yMin: Int, yMax: Int, pal: WidgetPalette, w: Int, h: Int): Bitmap {
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
        val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.fg.copy(alpha = 0.55f).toArgb(); strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) }
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
                val st = TideRepository.selectedStation(context)
                TideRepository.load(context, st, LocalDate.now(), force = true)
                ObsRepository.load(context, st, force = true)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 실패해도 캐시로 그린다
            }
            refreshAllWidgets(context)
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

/** 4x2 위젯 종류(같은 TideWidget을 쓰고 위젯 목록에서만 따로 보이게 한다). */
class TideWidgetReceiver4x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TideWidget()
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // 1시간마다: 캐시로 다시 그린다("N분 후"·지금선 갱신). 캐시에 오늘이 없으면 load가 내려받는다.
        val st = TideRepository.selectedStation(applicationContext)
        val r = TideRepository.load(applicationContext, st, LocalDate.now())
        try {
            ObsRepository.load(applicationContext, st) // 실측 캐시 갱신(실패해도 무시)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        val failed = r.isFailure
        refreshAllWidgets(applicationContext)
        return if (failed) Result.retry() else Result.success()
    }
}
