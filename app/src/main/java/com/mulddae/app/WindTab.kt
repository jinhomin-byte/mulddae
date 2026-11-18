package com.mulddae.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val WIND_INK = Color(0xFF14282E)
private val WIND_STAGES = listOf(
    Triple(0.0, Color(0xFF7DBDF0), "약함" to "0~1.5"),
    Triple(1.5, Color(0xFF2B78D4), "보통" to "1.5~3.5"),
    Triple(3.5, Color(0xFFF5C542), "약간 강함" to "3.5~7"),
    Triple(7.0, Color(0xFFF08A24), "강함" to "7~11"),
    Triple(11.0, Color(0xFFB71C1C), "매우 강함" to "11+")
)
private val WIND_NAMES = listOf("북풍", "북동풍", "동풍", "남동풍", "남풍", "남서풍", "서풍", "북서풍")

/** 풍속(m/s)에 해당하는 단계 색. 경계값은 아래 구간이 아니라 위 구간(v>=하한). */
private fun windColor(v: Double): Color = WIND_STAGES.last { v >= it.first }.second

private fun windName(deg: Double): String = WIND_NAMES[(((deg % 360 + 360) % 360) / 45).roundToInt() % 8]

private fun poly(vararg p: Float): Path = Path().apply {
    moveTo(p[0], p[1])
    var i = 2
    while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    close()
}

private val ROUND_STROKE_12 = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_STROKE_1 = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 바람자루. 28x28 좌표계(-14~14)를 캔버스 크기에 맞춰 그린다. */
private fun DrawScope.drawWindsock(sp: Double, col: Color) {
    val ph = min(90.0, sp * 8.2).toFloat()
    val s = size.minDimension / 28f
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(s, s, Offset.Zero)
    }) {
        drawLine(WIND_INK, Offset(-9f, -12f), Offset(-9f, 13f), strokeWidth = 1.8f, cap = StrokeCap.Round)
        withTransform({
            translate(-9f, -8f)
            rotate(-ph, Offset.Zero)
        }) {
            val body = poly(-5f, 0f, 5f, 0f, 2.6f, 20f, -2.6f, 20f)
            drawPath(body, Color.White)
            drawPath(body, WIND_INK, style = ROUND_STROKE_12)
            drawPath(poly(-4.6f, 3.5f, 4.6f, 3.5f, 4f, 8.5f, -4f, 8.5f), col)
            drawPath(poly(-3.4f, 13f, 3.4f, 13f, 3f, 17.2f, -3f, 17.2f), col)
            drawPath(body, WIND_INK, style = ROUND_STROKE_12)
        }
    }
}

/** 풍향 종이비행기. deg = 바람이 불어오는 방위. 비행기는 불어가는 쪽을 향한다. */
private fun DrawScope.drawPlane(
    deg: Double, col: Color,
    px: Float = size.minDimension, cx: Float = size.width / 2f, cy: Float = size.height / 2f
) {
    val a = ((deg + 180) % 360 + 360) % 360
    val s = px / 28f
    withTransform({
        translate(cx, cy)
        scale(s, s, Offset.Zero)
        if (a <= 180) {
            rotate(a.toFloat(), Offset.Zero)
        } else {
            scale(-1f, 1f, Offset.Zero)
            rotate((360 - a).toFloat(), Offset.Zero)
        }
    }) {
        val wing = poly(0f, -13f, -11.9f, 13f, -6.8f, 11.6f)
        val front = poly(0f, -13f, 5.2f, 7.8f, 0.9f, 9.0f, -4.5f, 11.0f)
        val tail = poly(-4.5f, 11f, 0.9f, 9f, 1.6f, 19.6f)
        drawPath(wing, col, alpha = 0.45f)
        drawPath(wing, WIND_INK, style = ROUND_STROKE_1)
        drawPath(front, col)
        drawPath(front, WIND_INK, style = ROUND_STROKE_1)
        drawPath(tail, col)
        drawPath(tail, WIND_INK, alpha = 0.28f)
        drawPath(tail, WIND_INK, style = ROUND_STROKE_1)
    }
}

@Composable
private fun WindMessage(text: String) {
    Text(text, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center)
}

/** 바람 탭 내용. 오늘 자료만 보여준다. */
@Composable
fun WindTabContent(info: ObsInfo?, loading: Boolean, error: String?, isToday: Boolean = true) {
    if (info == null) {
        if (error != null) WindMessage("실측 자료를 불러오지 못했습니다. 새로고침을 눌러 다시 시도하세요.")
        else if (loading) WindMessage("불러오는 중...")
        return
    }
    if (!info.inIndex) {
        WindMessage("이 관측소는 실측 자료가 없습니다")
        return
    }
    val rows = info.rows
    if (rows.isEmpty() && !loading) {
        WindMessage("이 날짜의 관측값이 없습니다.")
        return
    }
    if (rows.none { it.get(OBS_WSPD) != null || it.get(OBS_WDIR) != null } && !loading) {
        WindMessage("이 관측소는 바람 자료가 없습니다")
        return
    }

    val lastSp = rows.lastOrNull { it.get(OBS_WSPD) != null }
    val lastMx = rows.lastOrNull { it.get(OBS_WSMAX) != null }
    val lastDir = rows.lastOrNull { it.get(OBS_WDIR) != null }
    val sp = lastSp?.get(OBS_WSPD)
    val mx = lastMx?.get(OBS_WSMAX)
    val dir = lastDir?.get(OBS_WDIR)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WindCell("풍속", Modifier.weight(1f), lastSp?.time) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sp != null) Canvas(Modifier.size(26.dp)) { drawWindsock(sp, windColor(sp)) }
                Spacer(Modifier.width(4.dp))
                Text(sp?.let { "%.1f m/s".format(it) } ?: "-", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = sp?.let { windColor(it) } ?: Color.Gray, maxLines = 1)
            }
        }
        WindCell("순간최대", Modifier.weight(1f), lastMx?.time) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mx != null) Canvas(Modifier.size(26.dp)) { drawWindsock(mx, windColor(mx)) }
                Spacer(Modifier.width(4.dp))
                Text(mx?.let { "%.1f m/s".format(it) } ?: "-", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = mx?.let { windColor(it) } ?: Color.Gray, maxLines = 1)
            }
        }
        WindCell("풍향", Modifier.weight(1f), lastDir?.time) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dir != null) {
                    val c = windColor(sp ?: 0.0)
                    Canvas(Modifier.size(30.dp)) { drawPlane(dir, c) }
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(dir?.let { windName(it) } ?: "-", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = sp?.let { windColor(it) } ?: Color.Gray, maxLines = 1)
                    if (dir != null) Text("${dir.roundToInt()}°", fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                }
            }
        }
    }

    val pre = if (isToday) "오늘 " else ""
    Text("${pre}풍속 그래프", fontSize = 14.sp, color = Color.Gray)
    val points = remember(rows) {
        rows.mapNotNull { r -> r.get(OBS_WSPD)?.let { (r.time.hour + r.time.minute / 60.0) to it } }
    }
    val maxPts = remember(rows) {
        rows.mapNotNull { r -> r.get(OBS_WSMAX)?.let { (r.time.hour + r.time.minute / 60.0) to it } }
    }
    if (points.isEmpty()) {
        WindMessage("${pre}풍속 관측값이 없습니다")
    } else {
        WindChart(points, maxPts, Modifier.fillMaxWidth().height(150.dp))
    }

    // 시간별 풍향: 3시간마다, 그래프와 같은 가로 배치
    val hourly = remember(rows) {
        (0..21 step 3).map { hr ->
            val r = rows.filter { it.get(OBS_WDIR) != null }
                .minByOrNull { abs(it.time.hour * 60 + it.time.minute - hr * 60) }
            val ok = r != null && abs(r.time.hour * 60 + r.time.minute - hr * 60) <= 30
            Triple(hr, if (ok) r!!.get(OBS_WDIR) else null, if (ok) r!!.get(OBS_WSPD) else null)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val lp = 32.dp.toPx()
        val plotW = size.width - lp
        val ps = 18.dp.toPx()
        hourly.forEach { (hr, d, w) ->
            if (d != null) {
                val x = lp + plotW * (hr / 24f)
                val c = windColor(w ?: 0.0)
                drawPlane(d, c, ps, x, size.height / 2f)
            }
        }
    }

    WindLegend()
}

@Composable
private fun WindCell(label: String, modifier: Modifier, time: java.time.LocalDateTime?, content: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        content()
        Text(
            time?.format(DateTimeFormatter.ofPattern("HH:mm"))?.let { "$it 관측" } ?: "관측값 없음",
            fontSize = 11.sp, color = Color.Gray
        )
    }
}

@Composable
private fun WindLegend() {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            WIND_STAGES.forEach { st ->
                Box(Modifier.weight(1f).height(8.dp).background(st.second))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            WIND_STAGES.forEach { st ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(st.third.first, fontSize = 10.sp, color = Color.DarkGray, maxLines = 1, softWrap = false)
                    Text(st.third.second, fontSize = 10.sp, color = Color.Gray, maxLines = 1, softWrap = false)
                }
            }
        }
        Text("단위 m/s", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End)
    }
}

/** 오늘 풍속 꺾은선(중립색) + 순간최대(연한 점선). 점은 풍속 단계 색. */
@Composable
private fun WindChart(points: List<Pair<Double, Double>>, maxPts: List<Pair<Double, Double>>, modifier: Modifier) {
    val grid = Color(0xFFE0E0E0)
    val neutral = Color(0xFF78909C)
    val textPaint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.GRAY }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lp = 32.dp.toPx()
        val plotW = w - lp
        val topPad = 8.dp.toPx()
        val botPad = 18.dp.toPx()
        val plotH = h - topPad - botPad

        val hi = max(points.maxOf { it.second }, maxPts.maxOfOrNull { it.second } ?: 0.0)
        val step = if (hi <= 6) 1.0 else if (hi <= 12) 2.0 else 5.0
        val yMax = max(ceil(hi / step) * step, step * 2)
        fun yOf(v: Double) = topPad + plotH * (1f - (v / yMax).toFloat())
        fun xOf(hour: Double) = lp + plotW * (hour / 24.0).toFloat()

        textPaint.textSize = 10.sp.toPx()
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        val n = (yMax / step).roundToInt()
        for (i in 0..n) {
            val v = i * step
            val y = yOf(v)
            drawLine(grid, Offset(lp, y), Offset(w, y), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("%d".format(v.roundToInt()), lp - 4.dp.toPx(), y + 3.dp.toPx(), textPaint)
        }
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        for (hr in 0..24 step 3) {
            val x = xOf(hr.toDouble())
            drawLine(grid, Offset(x, topPad), Offset(x, topPad + plotH), 1.dp.toPx())
            if (hr < 24) drawContext.canvas.nativeCanvas.drawText("${hr}시", x + 2.dp.toPx(), h - 4.dp.toPx(), textPaint)
        }

        if (maxPts.size >= 2) {
            val mp = Path()
            maxPts.forEachIndexed { i, p ->
                val x = xOf(p.first); val y = yOf(p.second)
                if (i == 0) mp.moveTo(x, y) else mp.lineTo(x, y)
            }
            drawPath(mp, neutral.copy(alpha = 0.45f),
                style = Stroke(width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = xOf(p.first); val y = yOf(p.second)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, neutral, style = Stroke(width = 2.dp.toPx()))
        points.forEach { p -> drawCircle(windColor(p.second), 3.dp.toPx(), Offset(xOf(p.first), yOf(p.second))) }
    }
}
