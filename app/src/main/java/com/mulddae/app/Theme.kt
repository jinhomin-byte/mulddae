package com.mulddae.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat

/** 색상 테마 5종: 0 기본(보라) 1 바다 2 어둡게(네이비) 3 벚꽃 4 민트. 저장: SharedPreferences "mulddae" 키 "theme". */
const val THEME_COUNT = 5
private const val PREFS = "mulddae"
private const val KEY_THEME = "theme"

fun loadTheme(c: Context): Int =
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, 0).coerceIn(0, THEME_COUNT - 1)

fun saveTheme(c: Context, i: Int) {
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, i).apply()
}

/** 위젯 투명도 0~100(0=불투명, 100=완전 투명), 기본 0. 저장: SharedPreferences "mulddae" 키 "widget_transparency". */
private const val KEY_WIDGET_TRANSPARENCY = "widget_transparency"

fun loadWidgetTransparency(c: Context): Int =
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WIDGET_TRANSPARENCY, 0).coerceIn(0, 100)

fun saveWidgetTransparency(c: Context, v: Int) {
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_WIDGET_TRANSPARENCY, v.coerceIn(0, 100)).apply()
}

/** 저장 + 모든 위젯의 Glance 상태에 반영하고 다시 그린다(살아 있는 세션에도 즉시 적용). */
suspend fun applyWidgetTransparency(c: Context, v: Int) {
    saveWidgetTransparency(c, v)
    val manager = GlanceAppWidgetManager(c)
    manager.getGlanceIds(TideWidget::class.java).forEach { id ->
        try {
            updateAppWidgetState(c, id) { it[WIDGET_TRANSPARENCY_KEY] = v.coerceIn(0, 100) }
            TideWidget().update(c, id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 방금 지워진 위젯 등: 건너뛰고 나머지 계속
        }
    }
}

/** 모든 위젯(4x2·5x2)의 상태에 갱신 시각을 써서 강제로 다시 그린다(관측소 변경·새로고침용). */
suspend fun refreshAllWidgets(c: Context) {
    val manager = GlanceAppWidgetManager(c)
    manager.getGlanceIds(TideWidget::class.java).forEach { id ->
        try {
            val code = TideRepository.selectedStation(c).code
            updateAppWidgetState(c, id) {
                it[WIDGET_STATION_KEY] = code
                it[WIDGET_REFRESH_KEY] = System.currentTimeMillis()
            }
            TideWidget().update(c, id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 방금 지워진 위젯 등: 건너뛰고 나머지 계속
        }
    }
}

/** 위젯 테마 번호를 Glance 상태에도 저장하고 다시 그린다(살아 있는 세션에도 즉시 적용). */
suspend fun applyWidgetTheme(c: Context, i: Int) {
    saveTheme(c, i)
    val idx = i.coerceIn(0, THEME_COUNT - 1)
    val manager = GlanceAppWidgetManager(c)
    manager.getGlanceIds(TideWidget::class.java).forEach { id ->
        try {
            updateAppWidgetState(c, id) { it[WIDGET_THEME_KEY] = idx }
            TideWidget().update(c, id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 방금 지워진 위젯 등: 건너뛰고 나머지 계속
        }
    }
}

/** 위젯·설정창 미리보기 공용 색(바탕=카드색, 글자, 보조, 강조, 하락). */
class WidgetPalette(val bg: Color, val fg: Color, val sub: Color, val accent: Color, val down: Color)

private fun wp(bg: Long, fg: Long, sub: Long, accent: Long, down: Long) =
    WidgetPalette(c(bg), c(fg), c(sub), c(accent), c(down))

val WIDGET_PALETTES = listOf(
    wp(0xE7E0EC, 0x1C1B1F, 0x6B6672, 0x6650A4, 0xB45309), // 기본(보라)
    wp(0xDDE4FB, 0x0A1230, 0x4A5D96, 0x1237B5, 0xB45309), // 바다
    wp(0x1B2530, 0xDFE6ED, 0x98A4B0, 0x8ECBF5, 0xFFB74D), // 어둡게
    wp(0xF6E2E8, 0x201A1B, 0x76666A, 0xB5446E, 0xB45309), // 벚꽃
    wp(0xD9E8E3, 0x161D1B, 0x5B6A66, 0x006B5E, 0xB45309), // 민트
)

private class Palette(
    val dark: Boolean, val scheme: ColorScheme, val grid: Color, val ptext: Color,
)

private fun c(v: Long) = Color(0xFF000000 or v)

private fun palette(
    dark: Boolean, bg: Long, fg: Long, sub: Long, primary: Long, onPrimary: Long,
    container: Long, onContainer: Long, outline: Long, outline2: Long, card: Long, grid: Long,
    ptext: Long = primary, ownContainers: Boolean = true,
): Palette {
    var s = if (dark) darkColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(container), onPrimaryContainer = c(onContainer),
        background = c(bg), onBackground = c(fg),
        surface = c(bg), onSurface = c(fg),
        surfaceVariant = c(card), onSurfaceVariant = c(sub),
        outline = c(outline), outlineVariant = c(outline2),
    ) else lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(container), onPrimaryContainer = c(onContainer),
        background = c(bg), onBackground = c(fg),
        surface = c(bg), onSurface = c(fg),
        surfaceVariant = c(card), onSurfaceVariant = c(sub),
        outline = c(outline), outlineVariant = c(outline2),
    )
    if (ownContainers) {
        // 다이얼로그·메뉴 바탕도 테마색으로(기본 보라는 Material 기본값을 그대로 둔다)
        s = s.copy(surfaceContainer = c(card), surfaceContainerHigh = c(card), surfaceContainerHighest = c(card))
    }
    return Palette(dark, s, c(grid), c(ptext))
}

private val PALETTES = listOf(
    // 기본(보라)
    palette(false, 0xFFFBFE, 0x1C1B1F, 0x6B6672, 0x6650A4, 0xFFFFFF, 0xEADDFF, 0x21005D,
        0x79747E, 0xCAC4D0, 0xE7E0EC, 0xE3DFE8, ownContainers = false),
    // 바다
    palette(false, 0xF0F3FF, 0x0A1230, 0x4A5D96, 0x1744E0, 0xFFFFFF, 0x4A7DFF, 0xFFFFFF,
        0x5877D6, 0xBCCAF5, 0xDDE4FB, 0xDAE1F6, ptext = 0x1237B5),
    // 어둡게(밤바다 네이비)
    palette(true, 0x0E141B, 0xDFE6ED, 0x98A4B0, 0x8ECBF5, 0x00344F, 0x1D4A6B, 0xCFE9FF,
        0x8A96A3, 0x34404C, 0x1B2530, 0x27323D),
    // 벚꽃
    palette(false, 0xFFF8F9, 0x201A1B, 0x76666A, 0xB5446E, 0xFFFFFF, 0xFFD9E3, 0x3E001D,
        0x857375, 0xD8C2C6, 0xF6E2E8, 0xF0DDE2),
    // 민트
    palette(false, 0xF4FBF8, 0x161D1B, 0x5B6A66, 0x006B5E, 0xFFFFFF, 0xBDF0E4, 0x00201B,
        0x6F7976, 0xBFC9C5, 0xD9E8E3, 0xDCE8E4),
)

private val LocalGrid = compositionLocalOf { Color(0xFFE3DFE8) }
private val LocalPtext = compositionLocalOf { Color(0xFF6650A4) }

/** 그래프 격자선 색 */
@Composable
fun gridColor(): Color = LocalGrid.current

/** 보조 글자색(예전 Color.Gray 자리) */
@Composable
fun subColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 버튼·탭 글자에 쓰는 primary 계열 글자색(바다만 primary보다 진함) */
@Composable
fun ptextColor(): Color = LocalPtext.current

@Composable
fun AppTheme(themeIndex: Int, content: @Composable () -> Unit) {
    val p = PALETTES[themeIndex.coerceIn(0, THEME_COUNT - 1)]
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            w.statusBarColor = p.scheme.background.toArgb()
            w.navigationBarColor = p.scheme.background.toArgb()
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !p.dark
            ctl.isAppearanceLightNavigationBars = !p.dark
        }
    }
    CompositionLocalProvider(LocalGrid provides p.grid, LocalPtext provides p.ptext) {
        MaterialTheme(colorScheme = p.scheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** 윗줄 공용 40dp 원형 테두리 버튼. */
@Composable
fun CircleOutlineButton(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 설정 톱니바퀴 버튼(24dp 아이콘, 40dp 테두리 원). 누르면 색상 선택 창이 열린다. */
@Composable
fun ThemeButton(current: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    CircleOutlineButton(onClick = { open = true }, description = "색상 선택") {
        Icon(Icons.Filled.Settings, contentDescription = null, tint = ptextColor(), modifier = Modifier.size(24.dp))
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(20.dp)) {
                    var transparency by remember { mutableIntStateOf(loadWidgetTransparency(ctx)) }
                    Text("설정", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text("색상", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WIDGET_PALETTES.forEachIndexed { i, wp ->
                            val sel = i == current
                            Column(
                                Modifier.width(150.dp).clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelect(i)
                                        scope.launch { applyWidgetTheme(ctx, i) }
                                    }
                                    .semantics { contentDescription = THEME_NAMES[i] + if (sel) ", 선택됨" else "" },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .border(
                                            if (sel) 3.dp else 1.dp,
                                            if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(10.dp)
                                        )
                                ) {
                                    Checker(Modifier.matchParentSize())
                                    MiniWidget(wp, (100 - transparency) / 100f, Modifier.padding(8.dp))
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (sel) "✓ ${THEME_NAMES[i]}" else THEME_NAMES[i], fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("위젯 투명도: $transparency", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = transparency.toFloat(),
                        onValueChange = { transparency = it.toInt() },
                        onValueChangeFinished = {
                            scope.launch { applyWidgetTransparency(ctx, transparency) }
                        },
                        valueRange = 0f..100f,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { open = false }) { Text("완료", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

private val THEME_NAMES = listOf("기본(보라)", "바다", "어둡게", "벚꽃", "민트")

/** 미리보기 뒤 회색 체크무늬(투명도 확인용). */
@Composable
private fun Checker(modifier: Modifier) {
    Canvas(modifier) {
        val s = 8.dp.toPx()
        drawRect(Color(0xFFD9D9D9))
        var y = 0f
        var r = 0
        while (y < size.height) {
            var x = if (r % 2 == 0) 0f else s
            while (x < size.width) {
                drawRect(Color(0xFFB8B8B8), Offset(x, y), Size(s, s))
                x += 2 * s
            }
            y += s; r++
        }
    }
}

/** 설정창용 아주 작은 위젯 모양 미리보기. */
@Composable
private fun MiniWidget(p: WidgetPalette, alpha: Float, modifier: Modifier = Modifier) {
    @Composable
    fun t(s: String, size: Int, color: Color, bold: Boolean = false, m: Modifier = Modifier) =
        Text(
            s, color = color, fontSize = size.sp, lineHeight = (size + 2).sp, maxLines = 1,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = m
        )
    Column(
        modifier.fillMaxWidth().background(p.bg.copy(alpha = alpha), RoundedCornerShape(10.dp)).padding(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                t("인천", 8, p.fg, true)
                t(" · 음력 9.2", 7, p.sub)
            }
            t("4물", 8, p.accent, true)
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(12.dp).background(p.fg.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
                t("↻", 7, p.fg, true)
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth().background(p.fg.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            t("날물 ▼", 7, p.down, true, Modifier.weight(1f))
            t("간조 06:56", 7, p.fg)
        }
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            val h = size.height
            fun yOf(x: Float) = h * (0.5f - 0.38f * kotlin.math.cos(x / w * 2f * Math.PI.toFloat() * 2f))
            val line = androidx.compose.ui.graphics.Path()
            val fill = androidx.compose.ui.graphics.Path()
            fill.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                val y = yOf(x)
                if (x == 0f) line.moveTo(x, y) else line.lineTo(x, y)
                fill.lineTo(x, y)
                x += 2f
            }
            fill.lineTo(w, h); fill.close()
            drawPath(fill, p.accent.copy(alpha = 0.22f))
            drawPath(line, p.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            val nx = w * 0.3f
            drawLine(p.fg.copy(alpha = 0.55f), Offset(nx, 0f), Offset(nx, h), strokeWidth = 1.dp.toPx())
            drawCircle(p.down, 2.5.dp.toPx(), Offset(nx, yOf(nx)))
        }
        Row(Modifier.fillMaxWidth()) {
            val items = listOf(
                Triple("▲ 만조", "00:14", "647cm"), Triple("▼ 간조", "06:56", "102cm"),
                Triple("▲ 만조", "12:30", "640cm"), Triple("▼ 간조", "19:10", "98cm"),
            )
            items.forEachIndexed { i, (a, b, c) ->
                val high = i % 2 == 0
                Column(
                    Modifier.weight(1f).then(
                        if (i == 1) Modifier.background(p.fg.copy(alpha = 0.10f), RoundedCornerShape(4.dp)) else Modifier
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    t(a, 5, if (high) p.accent else p.down)
                    t(b, 7, p.fg, true)
                    t(c, 5, p.sub)
                }
            }
        }
    }
}
