package com.mulddae.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val WTEM_COLOR = Color(0xFF1E6FD0)
private val ARTMP_COLOR = Color(0xFFE8590C)
private val SLNT_COLOR = Color(0xFF00897B)

/** 수온·기온 탭(salinity=false) 또는 염분 탭(salinity=true)의 내용. 오늘 자료만 보여준다. */
@Composable
fun ObsTabContent(info: ObsInfo?, loading: Boolean, error: String?, salinity: Boolean, isToday: Boolean = true) {
    val cur = if (isToday) "현재 " else ""
    val pre = if (isToday) "오늘 " else ""
    if (info == null) {
        if (error != null) ObsMessage("실측 자료를 불러오지 못했습니다. 새로고침을 눌러 다시 시도하세요.")
        else if (loading) ObsMessage("불러오는 중...")
        return
    }
    if (!salinity && !info.inIndex) {
        ObsMessage("수온·기온 관측이 없는 곳입니다.")
        return
    }
    if (!info.inIndex) {
        ObsMessage("이 관측소는 실측 자료가 없습니다")
        return
    }
    if (info.rows.isEmpty() && !loading) {
        ObsMessage("이 날짜의 관측값이 없습니다.")
        return
    }
    if (salinity) {
        if (info.rows.none { it.get(OBS_SLNT) != null } && !loading) {
            ObsMessage("이 관측소는 염분 자료가 없습니다")
            return
        }
        ObsCurrent("${cur}염분", info.rows, OBS_SLNT, SLNT_COLOR, "", Modifier.fillMaxWidth(), pre)
        ObsGraph("${pre}염분 그래프", info.rows, OBS_SLNT, SLNT_COLOR, pre)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info.hasWtem) ObsCurrent("${cur}수온", info.rows, OBS_WTEM, WTEM_COLOR, "℃", Modifier.weight(1f), pre)
            ObsCurrent("${cur}기온", info.rows, OBS_ARTMP, ARTMP_COLOR, "℃", Modifier.weight(1f), pre)
        }
        if (info.hasWtem) ObsGraph("${pre}수온 그래프", info.rows, OBS_WTEM, WTEM_COLOR, pre)
        ObsGraph("${pre}기온 그래프", info.rows, OBS_ARTMP, ARTMP_COLOR, pre)
        if (!info.hasWtem) {
            Text("수온 관측이 없는 곳입니다.", color = subColor(), fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ObsMessage(text: String) {
    Text(text, color = subColor(), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center)
}

/** 마지막 유효 관측값을 큰 숫자로, 아래에 관측 시각. */
@Composable
private fun ObsCurrent(label: String, rows: List<ObsRow>, idx: Int, color: Color, unit: String, modifier: Modifier, pre: String) {
    val last = rows.lastOrNull { it.get(idx) != null }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = subColor())
        Text(
            last?.get(idx)?.let { "%.1f%s".format(it, unit) } ?: "-",
            fontSize = 36.sp, fontWeight = FontWeight.Bold, color = color
        )
        Text(
            last?.time?.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")).let { if (it != null) "$it 관측" else "${pre}관측값 없음" },
            fontSize = 12.sp, color = subColor()
        )
    }
}

@Composable
private fun ObsGraph(title: String, rows: List<ObsRow>, idx: Int, color: Color, pre: String) {
    val points = remember(rows, idx) {
        rows.mapNotNull { r -> r.get(idx)?.let { (r.time.hour + r.time.minute / 60.0) to it } }
    }
    Text(title, fontSize = 14.sp, color = subColor())
    if (points.isEmpty()) {
        ObsMessage("${pre}관측값이 없습니다")
    } else {
        ObsChart(points, color, Modifier.fillMaxWidth().height(130.dp))
    }
}

private fun niceStep(range: Double): Double {
    val raw = range / 4
    val p = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
}

/** 오늘 꺾은선 그래프. points = (시각(0~24), 값). 가로는 0~24시, 세로는 값 범위에 맞춤. */
@Composable
private fun ObsChart(points: List<Pair<Double, Double>>, lineColor: Color, modifier: Modifier = Modifier) {
    val grid = gridColor()
    val leftPad = 32.dp
    val subArgb = subColor().toArgb()
    val textPaint = remember(subArgb) {
        android.graphics.Paint().apply { isAntiAlias = true; color = subArgb }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lp = leftPad.toPx()
        val plotW = w - lp
        val topPad = 8.dp.toPx()
        val botPad = 18.dp.toPx()
        val plotH = h - topPad - botPad

        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val range = max(hi - lo, 1.0)
        val step = niceStep(range)
        val yMin = floor((lo - 0.05 * range) / step) * step
        val yMax = ceil((hi + 0.05 * range) / step) * step
        fun yOf(v: Double) = topPad + plotH * (1f - ((v - yMin) / (yMax - yMin)).toFloat())
        fun xOf(hour: Double) = lp + plotW * (hour / 24.0).toFloat()

        textPaint.textSize = 10.sp.toPx()

        // 가로선(값). 숫자는 왼쪽 여백에 오른쪽 맞춤.
        val decimals = abs(step - step.roundToInt()) > 1e-9
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        val n = ((yMax - yMin) / step).roundToInt()
        for (i in 0..n) {
            val v = yMin + i * step
            val y = yOf(v)
            drawLine(grid, Offset(lp, y), Offset(w, y), 1.dp.toPx())
            val label = if (decimals) "%.1f".format(v) else "%d".format(v.roundToInt())
            drawContext.canvas.nativeCanvas.drawText(label, lp - 4.dp.toPx(), y + 3.dp.toPx(), textPaint)
        }
        textPaint.textAlign = android.graphics.Paint.Align.LEFT

        // 세로선(시각): 3시간마다, 0~24시
        for (hr in 0..24 step 3) {
            val x = xOf(hr.toDouble())
            drawLine(grid, Offset(x, topPad), Offset(x, topPad + plotH), 1.dp.toPx())
            if (hr < 24) drawContext.canvas.nativeCanvas.drawText("${hr}시", x + 2.dp.toPx(), h - 4.dp.toPx(), textPaint)
        }

        // 꺾은선 + 점
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = xOf(p.first)
            val y = yOf(p.second)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        points.forEach { p -> drawCircle(lineColor,3.dp.toPx(), Offset(xOf(p.first), yOf(p.second))) }
    }
}
