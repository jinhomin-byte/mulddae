package com.mulddae.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

/** 색 동그라미 버튼(작은 아이콘 하나). 누르면 동그라미 5개 창이 열린다. */
@Composable
fun ThemeButton(current: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(36.dp).clickable { open = true }.semantics { contentDescription = "색상 선택" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PALETTES.forEachIndexed { i, p ->
                        val sel = i == current
                        Box(
                            Modifier.size(32.dp)
                                .background(p.scheme.primary, CircleShape)
                                .border(
                                    if (sel) 3.dp else 1.dp,
                                    if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { onSelect(i); open = false }
                                .semantics { contentDescription = THEME_NAMES[i] + if (sel) ", 선택됨" else "" },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Text("✓", fontSize = 16.sp, color = p.scheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

private val THEME_NAMES = listOf("기본", "바다", "어둡게", "벚꽃", "민트")
