package com.mulddae.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val MAX_FAVORITES = 5

/** 관측소 즐겨찾기 저장소: 기기 안 SharedPreferences, 순서 유지. */
object Favorites {
    private const val KEY = "favorites"

    private fun prefs(c: Context) = c.getSharedPreferences("mulddae", Context.MODE_PRIVATE)

    fun load(c: Context): List<Station> =
        (prefs(c).getString(KEY, null) ?: "").split('\n').mapNotNull {
            val i = it.indexOf('|')
            if (i <= 0) null else Station(it.substring(i + 1), it.substring(0, i))
        }.take(MAX_FAVORITES)

    fun save(c: Context, list: List<Station>) {
        prefs(c).edit().putString(KEY, list.joinToString("\n") { "${it.code}|${it.name}" }).apply()
    }
}

private val THERMO_RED = Color(0xFFD32F2F)
private val THERMO_TUBE = PathParser().parsePathString("M10 4a2 2 0 0 1 4 0v9.47A4.5 4.5 0 1 1 10 13.47Z").toPath()

/** 빨간 온도계 마크(규격: 24x24 기준, 20dp). show=false면 같은 크기의 빈 자리. */
@Composable
fun ThermoMark(show: Boolean, markSize: Dp = 20.dp) {
    if (!show) {
        Spacer(Modifier.size(markSize))
        return
    }
    Canvas(Modifier.size(markSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            drawPath(THERMO_TUBE, Color.White)
            drawPath(
                THERMO_TUBE, THERMO_RED,
                style = Stroke(width = 1.7f, join = StrokeJoin.Round)
            )
            drawRoundRect(
                THERMO_RED, topLeft = Offset(11.1f, 8f), size = Size(1.8f, 9f),
                cornerRadius = CornerRadius(0.9f, 0.9f)
            )
            drawCircle(THERMO_RED, radius = 2.6f, center = Offset(12f, 17.5f))
            drawLine(THERMO_RED, Offset(16.2f, 5.2f), Offset(18.8f, 5.2f), 1.3f, StrokeCap.Round)
            drawLine(THERMO_RED, Offset(16.2f, 8.2f), Offset(18.0f, 8.2f), 1.3f, StrokeCap.Round)
            drawLine(THERMO_RED, Offset(16.2f, 11.2f), Offset(18.8f, 11.2f), 1.3f, StrokeCap.Round)
        }
    }
}

/** 이름 3자까지, 4자 이상은 3자+".." */
fun chipLabel(name: String): String = if (name.length > 3) name.take(3) + ".." else name

/** 즐겨찾기 칩 한 줄. 길게 누르면 그 칩에 ×가 나타난다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteChips(
    favs: List<Station>,
    selectedCode: String,
    wtemCodes: Set<String>,
    editCode: String?,
    onSelect: (Station) -> Unit,
    onLongPress: (Station) -> Unit,
    onDelete: (Station) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        favs.forEach { s ->
            val selected = s.code == selectedCode
            val hasTemp = s.code in wtemCodes
            Box(Modifier.weight(1f).padding(top = 8.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onSelect(s) },
                        onLongClick = { onLongPress(s) }
                    )
                ) {
                    Row(
                        Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (hasTemp) {
                            ThermoMark(true, 14.dp)
                            Spacer(Modifier.width(1.dp))
                        }
                        Text(
                            chipLabel(s.name), fontSize = 12.sp, maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (editCode == s.code) {
                    // 칩 오른쪽 위 모서리에 걸친 원형 삭제 배지(터치영역 24dp, 칩 크기는 불변)
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-8).dp)
                            .size(24.dp).clickable { onDelete(s) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(18.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("×", fontSize = 14.sp, color = Color.White, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
        // 칩이 5개 미만이면 빈 자리로 채워 칩 폭이 커지지 않게 하고 왼쪽 정렬을 유지한다.
        val empty = MAX_FAVORITES - favs.size
        if (empty > 0) Spacer(Modifier.weight(empty.toFloat()))
    }
}
