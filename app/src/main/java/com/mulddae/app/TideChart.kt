package com.mulddae.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** 고저조 사이를 코사인으로 부드럽게 이은 조위(cm). 앞뒤 고저조가 없으면 null. */
fun tideHeightAt(events: List<TideEvent>, t: LocalDateTime): Double? {
    if (events.size < 2) return null
    var lo = 0
    var hi = events.size
    while (lo < hi) { // 처음으로 time > t 인 위치
        val mid = (lo + hi) ushr 1
        if (events[mid].time > t) hi = mid else lo = mid + 1
    }
    if (lo == 0 || lo == events.size) return null
    val p = events[lo - 1]
    val n = events[lo]
    val total = Duration.between(p.time, n.time).seconds.toDouble()
    if (total <= 0) return p.heightCm.toDouble()
    val frac = Duration.between(p.time, t).seconds / total
    return p.heightCm + (n.heightCm - p.heightCm) * (1 - cos(PI * frac)) / 2
}

/**
 * 조석 곡선 그래프. 가운데 점선이 center 시각이고, 좌우로 끌면 onCenterChange로 시각이 바뀐다.
 * 화면 전체 폭은 24시간.
 */
@Composable
fun TideChart(
    events: List<TideEvent>,
    center: LocalDateTime,
    onCenterChange: (LocalDateTime) -> Unit,
    yMin: Int,
    yMax: Int,
    modifier: Modifier = Modifier,
) {
    val windowMin = 24 * 60.0
    val centerState = rememberUpdatedState(center)
    val onChange = rememberUpdatedState(onCenterChange)
    // 데이터가 바뀌면(관측소 변경·새로고침) 말풍선을 해제한다.
    var tapTime by remember(events) { mutableStateOf<LocalDateTime?>(null) }
    val lineColor = MaterialTheme_primary()
    val grid = Color(0xFFE0E0E0)
    val leftPad = 32.dp // 세로축 숫자가 들어가는 왼쪽 여백

    val textPaint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.GRAY }
    }
    val bubbleText = remember {
        android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE }
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    val plotW = size.width - leftPad.toPx()
                    val sec = (dx / plotW * windowMin * 60).toLong()
                    onChange.value(centerState.value.minusSeconds(sec))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val lp = leftPad.toPx()
                    val plotW = size.width - lp
                    val min = (off.x - (lp + plotW / 2f)) / plotW * windowMin
                    tapTime = centerState.value.plusSeconds((min * 60).toLong())
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val lp = leftPad.toPx()
        val plotW = w - lp
        val topPad = 30.dp.toPx() // 맨 위 줄은 말풍선 자리
        val botPad = 30.dp.toPx() // 아래 두 줄: 날짜, 시각
        val plotH = h - topPad - botPad
        val range = max(1, yMax - yMin).toFloat()
        fun yOf(v: Double) = topPad + plotH * (1f - ((v - yMin) / range).toFloat())
        fun xOf(t: LocalDateTime) =
            lp + plotW / 2f + (Duration.between(center, t).seconds / 60.0 / windowMin * plotW).toFloat()

        textPaint.textSize = 10.sp.toPx()
        bubbleText.textSize = 12.sp.toPx()

        // 가로선(cm). 숫자는 왼쪽 여백에 오른쪽 맞춤.
        val step = if (range >= 800f) 200 else if (range >= 500f) 100 else 50
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        var v = Math.ceil(yMin / step.toDouble()).toInt() * step
        while (v <= yMax) {
            val y = yOf(v.toDouble())
            drawLine(grid, Offset(lp, y), Offset(w, y), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("$v", lp - 4.dp.toPx(), y + 3.dp.toPx(), textPaint)
            v += step
        }
        textPaint.textAlign = android.graphics.Paint.Align.LEFT

        // 세로선(시각): 3시간마다, 0시는 날짜
        val start = center.minusMinutes((windowMin / 2).toLong()).truncatedTo(ChronoUnit.HOURS)
        var t = start
        val end = center.plusMinutes((windowMin / 2).toLong() + 60)
        while (t <= end) {
            val x = xOf(t)
            if (t.hour % 3 == 0 && x in lp..w) {
                drawLine(grid, Offset(x, topPad), Offset(x, topPad + plotH), 1.dp.toPx())
                if (t.hour == 0) {
                    // 자정은 날짜만, 시각 줄 한 줄 위에 표시
                    drawContext.canvas.nativeCanvas.drawText(
                        "${t.monthValue}/${t.dayOfMonth}", x + 2.dp.toPx(), h - 16.dp.toPx(), textPaint)
                } else {
                    drawContext.canvas.nativeCanvas.drawText("${t.hour}시", x + 2.dp.toPx(), h - 4.dp.toPx(), textPaint)
                }
            }
            t = t.plusHours(1)
        }

        // 곡선
        val path = Path()
        var started = false
        var px = lp
        while (px <= w) {
            val tt = center.plusSeconds(((px - lp - plotW / 2f) / plotW * windowMin * 60).toLong())
            val hv = tideHeightAt(events, tt)
            if (hv == null) {
                started = false
            } else {
                val y = yOf(hv)
                if (!started) { path.moveTo(px, y); started = true } else path.lineTo(px, y)
            }
            px += 3.dp.toPx()
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx()))

        // 고저조 점
        val from = center.minusMinutes((windowMin / 2).toLong())
        val to = center.plusMinutes((windowMin / 2).toLong())
        var lo = 0
        var hi = events.size
        while (lo < hi) { // 처음으로 time >= from 인 위치
            val mid = (lo + hi) ushr 1
            if (events[mid].time < from) lo = mid + 1 else hi = mid
        }
        for (i in lo until events.size) {
            val e = events[i]
            if (e.time > to) break
            drawCircle(
                if (e.high) Color(0xFF1565C0) else Color(0xFFEF6C00), 4.dp.toPx(),
                Offset(xOf(e.time), yOf(e.heightCm.toDouble()))
            )
        }

        // 가운데 점선(지금)
        drawLine(
            Color(0xFFD32F2F), Offset(lp + plotW / 2f, topPad), Offset(lp + plotW / 2f, topPad + plotH),
            1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )

        // 누른 곳 말풍선
        tapTime?.let { tt ->
            val hv = tideHeightAt(events, tt)
            val x = xOf(tt)
            if (hv != null && x in lp..w) {
                val y = yOf(hv)
                drawCircle(Color.White, 5.dp.toPx(), Offset(x, y))
                drawCircle(lineColor, 5.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                val text = "%02d:%02d · 약 %d cm".format(tt.hour, tt.minute, Math.round(hv))
                val tw = bubbleText.measureText(text)
                val padX = 8.dp.toPx()
                val bw = tw + padX * 2
                val bh = 24.dp.toPx()
                val bx = (x - bw / 2f).coerceIn(lp, w - bw)
                val by = 3.dp.toPx() // 그래프 맨 위 줄에 고정(곡선을 가리지 않음)
                drawRoundRect(
                    Color(0xCC333333), Offset(bx, by), Size(bw, bh),
                    androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                )
                drawContext.canvas.nativeCanvas.drawText(text, bx + padX, by + bh * 0.68f, bubbleText)
            }
        }
    }
}

@Composable
private fun MaterialTheme_primary(): Color = androidx.compose.material3.MaterialTheme.colorScheme.primary
