package com.mulddae.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

private val CUR_LIGHT = Color(0xFFF3C9A8)
private val CUR_DARK = Color(0xFFC2561A)
private val CUR_TIDE_LINE = Color(0xFF1E88E5)

private fun msText(cms: Double): String =
    if (cms < 5.0) "0.0" else "%.1f".format(Locale.US, cms / 100.0)

private fun hhmm(idx: Int): String = "%02d:%02d".format(idx * 10 / 60, idx * 10 % 60)

/** 막대·글자에 쓰는 조류색: 위젯 팔레트의 down 색. */
private fun currentColor(themeIndex: Int): Color = WIDGET_PALETTES[themeIndex.coerceIn(0, THEME_COUNT - 1)].down

private fun ratioColor(r: Double): Color = lerp(CUR_LIGHT, CUR_DARK, r.coerceIn(0.0, 1.0).toFloat())

/** 화살표(위쪽=북, 시계방향). dirIdx는 흐르는 쪽 16방위. size는 화살표 전체 길이(px). */
private fun DrawScope.drawFlowArrow(dirIdx: Int, col: Color, cx: Float, cy: Float, len: Float) {
    if (dirIdx !in 0..15) return
    val h = len / 2f
    val path = Path().apply {
        moveTo(0f, -h)
        lineTo(h * 0.62f, -h * 0.05f)
        lineTo(h * 0.24f, -h * 0.05f)
        lineTo(h * 0.24f, h)
        lineTo(-h * 0.24f, h)
        lineTo(-h * 0.24f, -h * 0.05f)
        lineTo(-h * 0.62f, -h * 0.05f)
        close()
    }
    withTransform({
        translate(cx, cy)
        rotate(dirIdx * 22.5f, Offset.Zero)
    }) {
        drawPath(path, col)
    }
}

@Composable
private fun CurMessage(text: String, small: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, color = subColor(), fontSize = 14.sp, textAlign = TextAlign.Center)
        if (small != null) Text(small, color = subColor(), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

private class CurEvent(val idx: Int, val stop: Boolean)

/** 10분 시계열에서 물 멈춤(유속 국소 최소 + 앞뒤 유향 반대)과 가장 셈(유속 국소 최대)을 찾는다. */
private fun findEvents(s: List<Double>, d: List<Int>, maxCrsp: Double): List<CurEvent> {
    val out = ArrayList<CurEvent>()
    val ref = maxCrsp // 기준값(막대·세기 말과 같은 값)
    fun dirBefore(i: Int): Int { var k = i - 2; while (k >= 0) { if (d[k] >= 0) return d[k]; k-- }; return -1 }
    fun dirAfter(i: Int): Int { var k = i + 2; while (k < d.size) { if (d[k] >= 0) return d[k]; k++ }; return -1 }
    // 봉우리 돌출도: 양옆으로 더 높은 점을 만날 때까지의 골 중 높은 쪽과의 차. 하루 끝까지 더 높은 점이 없는 쪽은 무시(둘 다 없으면 통과).
    fun peakProminence(i: Int): Double {
        var best = -1.0
        var k = i - 1; var m = s[i]
        while (k >= 0) { if (s[k] > s[i]) { best = maxOf(best, s[i] - m); break }; m = minOf(m, s[k]); k-- }
        k = i + 1; m = s[i]
        while (k < s.size) { if (s[k] > s[i]) { best = maxOf(best, s[i] - m); break }; m = minOf(m, s[k]); k++ }
        return if (best < 0) Double.MAX_VALUE else best
    }
    // 골 깊이: 양옆으로 더 낮은 점을 만날 때까지의 봉우리 중 낮은 쪽과의 차. 끝까지 낮은 점이 없는 쪽은 무시.
    fun valleyDepth(i: Int): Double {
        var best = Double.MAX_VALUE; var found = false
        var k = i - 1; var m = s[i]
        while (k >= 0) { if (s[k] < s[i]) { best = minOf(best, m - s[i]); found = true; break }; m = maxOf(m, s[k]); k-- }
        k = i + 1; m = s[i]
        while (k < s.size) { if (s[k] < s[i]) { best = minOf(best, m - s[i]); found = true; break }; m = maxOf(m, s[k]); k++ }
        return if (found) best else Double.MAX_VALUE
    }
    for (i in 1..s.size - 2) {
        if (s[i] <= s[i - 1] && s[i] < s[i + 1] && s[i] <= ref * 0.15 && valleyDepth(i) >= ref * 0.15) {
            val a = dirBefore(i)
            val b = dirAfter(i)
            if (a >= 0 && b >= 0) {
                val diff = Math.abs(a - b).let { minOf(it, 16 - it) }
                if (diff >= 6) out.add(CurEvent(i, true))
            }
        }
        if (s[i] >= s[i - 1] && s[i] > s[i + 1] && s[i] > 0 && s[i] >= ref * 0.25 && peakProminence(i) >= ref * 0.15) {
            out.add(CurEvent(i, false))
        }
    }
    return out.sortedBy { it.idx }
}

@Composable
fun CurrentTabContent(
    day: CurrentRepository.Day?, dayDate: LocalDate?, viewDate: LocalDate, loading: Boolean,
    themeIndex: Int, events: List<TideEvent>, yMin: Int, yMax: Int
) {
    // 상태·remember는 모든 return보다 앞에 둔다(로딩·범위 밖 화면을 거쳐도 겹쳐보기 상태 유지).
    var nowDt by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(viewDate) {
        nowDt = LocalDateTime.now()
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
            nowDt = LocalDateTime.now()
        }
    }
    var overlay by remember { mutableStateOf(false) }
    val speeds = if (dayDate == viewDate) day?.speeds else null
    val dirs = if (dayDate == viewDate) day?.dirs else null
    val bars = remember(speeds) { speeds?.let { sp -> List(48) { b -> (0 until 3).maxOf { sp[b * 3 + it] } } } }
    val maxCrsp = if (day != null && day.maxCrsp > 0) day.maxCrsp else (speeds?.maxOrNull() ?: 0.0)
    val evs = remember(speeds, dirs, maxCrsp) {
        if (speeds != null && dirs != null) findEvents(speeds, dirs, maxCrsp) else emptyList()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridCol = gridColor()
    val subArgb = subColor().toArgb()
    val textPaint = remember(subArgb) { android.graphics.Paint().apply { isAntiAlias = true; color = subArgb } }

    val today = nowDt.toLocalDate()
    val inRange = !viewDate.isBefore(today.minusDays(1)) && !viewDate.isAfter(today.plusDays(6))
    if (!inRange) {
        CurMessage(
            "${viewDate.monthValue}월 ${viewDate.dayOfMonth}일은 조류 정보가 없습니다",
            "조류는 어제부터 6일 뒤까지만 볼 수 있습니다. 날짜를 되돌려 주세요."
        )
        return
    }
    if (day == null || speeds == null || dirs == null || bars == null) {
        if (loading || dayDate != viewDate) CurMessage("불러오는 중...")
        else CurMessage("조류 정보를 불러오지 못했습니다. 새로고침(↻)을 눌러 주세요")
        return
    }

    val col = currentColor(themeIndex)
    val isToday = viewDate == today
    val nowIdx = ((nowDt.hour * 60 + nowDt.minute + 5) / 10).coerceIn(0, 143)
    val strengthBase = maxCrsp

    // 맨 위 카드: 오늘은 지금 값, 다른 날은 그 날 가장 센 값
    val shownIdx = if (isToday) nowIdx else speeds.indices.maxByOrNull { speeds[it] } ?: 0
    val shownSpeed = speeds[shownIdx]
    val shownDir = dirs[shownIdx]
    val shownStrength = CurrentRepository.strengthOf(shownSpeed, strengthBase)
    val dirName = CurrentRepository.dirNameOf(shownDir)

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.size(36.dp)) {
                drawFlowArrow(shownDir, col, size.width / 2f, size.height / 2f, size.minDimension)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    append(msText(shownSpeed))
                    withStyle(SpanStyle(fontSize = 13.sp)) { append(" m/s") }
                },
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = col, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    if (isToday) "$dirName · 지금" else "가장 셈 ${hhmm(shownIdx)}",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ptextColor(),
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false
                )
                @Composable
                fun badge(size: androidx.compose.ui.unit.TextUnit) = Text(
                    shownStrength, fontSize = size, fontWeight = FontWeight.Bold,
                    color = when (shownStrength) {
                        "강함" -> Color.White
                        "보통" -> Color(0xFF3E1A00)
                        else -> Color(0xFF5A2A00)
                    },
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.background(
                        ratioColor(when (shownStrength) { "강함" -> 1.0; "보통" -> 0.5; else -> 0.0 }),
                        RoundedCornerShape(50)
                    ).padding(horizontal = 8.dp, vertical = 0.dp)
                )
                if (isToday) {
                    badge(11.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(dirName, fontSize = 14.sp, color = ptextColor(), maxLines = 1, softWrap = false)
                        // 방위가 길면(4글자 이상) 배지 글자만 줄인다
                        badge(if (dirName.length >= 4) 10.sp else 11.sp)
                    }
                }
            }
        }
    }

    // 24시간 그래프
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("조류 세기 (24시간)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ptextColor(),
                    modifier = Modifier.weight(1f))
                val pillShape = RoundedCornerShape(50)
                Text(
                    if (overlay) "✓ 조위와 겹쳐보기" else "조위와 겹쳐보기",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    color = if (overlay) Color.White else CUR_TIDE_LINE,
                    modifier = Modifier
                        .clip(pillShape)
                        .then(if (overlay) Modifier.background(CUR_TIDE_LINE) else Modifier.border(1.dp, CUR_TIDE_LINE, pillShape))
                        .clickable { overlay = !overlay }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                val w = size.width
                val h = size.height
                val slot = w / 48f
                val gap = slot * 0.18f
                for (g in 0..2) {
                    val y = h * g / 2f
                    drawLine(gridCol, Offset(0f, y), Offset(w, y), 1.dp.toPx())
                }
                val nowBar = nowIdx / 3
                bars.forEachIndexed { b, v ->
                    val r = if (maxCrsp > 0) (v / maxCrsp).coerceIn(0.0, 1.0) else 0.0
                    val bh = maxOf((r * h).toFloat(), 2.dp.toPx())
                    val topLeft = Offset(b * slot + gap / 2f, h - bh)
                    val sz = Size(slot - gap, bh)
                    drawRect(ratioColor(r), topLeft, sz)
                    if (isToday && b == nowBar) {
                        drawRect(onSurface, topLeft, sz, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                if (overlay && yMax > yMin) {
                    val path = Path()
                    var started = false
                    val base = viewDate.atStartOfDay()
                    var m = 0
                    while (m <= 24 * 60) {
                        val hgt = tideHeightAt(events, base.plusMinutes(m.toLong()))
                        if (hgt == null) { started = false } else {
                            val x = w * m / (24f * 60f)
                            val y = h * (1f - ((hgt - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f))
                            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                        }
                        m += 15
                    }
                    drawPath(path, CUR_TIDE_LINE, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (isToday) {
                    val x = w * (nowDt.hour * 60 + nowDt.minute) / (24f * 60f)
                    drawLine(
                        onSurface, Offset(x, 0f), Offset(x, h), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    )
                }
            }
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val w = size.width
                for (k in 0 until 8) {
                    val i = (k * 18 + 9).coerceIn(0, 143) // 3시간 구간의 한가운데 시각
                    val x = w * (k * 3 + 1.5f) / 24f
                    val v = speeds[i]
                    val r = if (maxCrsp > 0) v / maxCrsp else 0.0
                    drawFlowArrow(dirs[i], ratioColor(r.coerceAtLeast(0.35)), x, 10.dp.toPx(), 15.dp.toPx())
                }
                textPaint.textSize = 11.sp.toPx()
                for (hr in listOf(0, 6, 12, 18, 24)) {
                    val x = w * hr / 24f
                    textPaint.textAlign = when (hr) {
                        0 -> android.graphics.Paint.Align.LEFT
                        24 -> android.graphics.Paint.Align.RIGHT
                        else -> android.graphics.Paint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.drawText("%02d".format(hr), x, size.height - 3.dp.toPx(), textPaint)
                }
            }
        }
    }

    // 물 멈춤 · 가장 셈
    evs.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { e ->
                val v = speeds[e.idx]
                val faded = isToday && e.idx < nowIdx
                Card(
                    Modifier.weight(1f).alpha(if (faded) 0.45f else 1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()) {
                        Text(
                            if (e.stop) "물 멈춤" else "가장 셈 ↗", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (e.stop) subColor() else col
                        )
                        Text(hhmm(e.idx), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (e.stop) "${msText(v)} m/s"
                            else "${msText(v)} m/s ${CurrentRepository.dirNameOf(dirs[e.idx])}",
                            fontSize = 12.sp, color = subColor(), maxLines = 1
                        )
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    // 범례
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("약함", fontSize = 11.sp, color = subColor())
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.width(80.dp).height(8.dp)
                .background(Brush.horizontalGradient(listOf(CUR_LIGHT, CUR_DARK)), RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text("강함", fontSize = 11.sp, color = subColor())
        Spacer(Modifier.weight(1f))
        Text("화살표 = 흐르는 쪽", fontSize = 11.sp, color = subColor())
    }
    Spacer(Modifier.height(0.dp)) // 스크롤 끝 여백(부모 간격 8dp)
}

/** 맨 아래 고정 줄: 조류 지점(14sp, 이름 굵게)과 출처(9sp). */
@Composable
fun CurrentFooter(day: CurrentRepository.Day?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (day != null) {
            Text(
                buildAnnotatedString {
                    append("조류 지점: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(day.pointName) }
                    append(" · 관측소에서 ${"%.1f".format(Locale.US, day.distanceKm)}km")
                },
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            )
        }
        var sourceSize by remember { mutableStateOf(9f) }
        Text(
            CurrentRepository.SOURCE_TEXT,
            fontSize = sourceSize.sp, color = subColor(), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { r ->
                if (r.hasVisualOverflow && sourceSize > 6.5f) sourceSize = (sourceSize - 0.5f).coerceAtLeast(6.5f)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
