package com.mulddae.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 관측소 아이콘 종류. 선언 순서가 목록 묶음 순서(둘 다 -> 조류만 -> 수온만 -> 없음). */
enum class StationKind(val title: String?, val desc: String?) {
    BOTH("둘 다 있음", "수온·조류 있음"),
    CURRENT("조류만 있음", "조류 있음"),
    TEMP("수온만 있음", "수온 있음"),
    NONE("아이콘 없음", null)
}

fun stationKind(hasTemp: Boolean, hasPair: Boolean): StationKind = when {
    hasTemp && hasPair -> StationKind.BOTH
    hasPair -> StationKind.CURRENT
    hasTemp -> StationKind.TEMP
    else -> StationKind.NONE
}

private val TEMP_BG = Color(0xFFFAD0D0)
private val CUR_BG = Color(0xFFF3D3BE)
private val TEMP_FG = Color(0xFFD32F2F)
private val CUR_FG = Color(0xFFC2561A)

private val TUBE = PathParser().parsePathString("M10 4a2 2 0 0 1 4 0v9.47A4.5 4.5 0 1 1 10 13.47Z").toPath()
private val WAVE_BIG = PathParser().parsePathString("M5 7.5q2-2.4 4 0t4 0t4 0").toPath()
private val WAVE_BIG2 = PathParser().parsePathString("M5 12q2-2.4 4 0t4 0t4 0").toPath()
private val WAVE_SM = PathParser().parsePathString("M13.2 9.5q1.7-2.2 3.4 0t3.4 0").toPath()
private val WAVE_SM2 = PathParser().parsePathString("M13.2 14q1.7-2.2 3.4 0t3.4 0").toPath()

/** 온도계(24 기준 좌표, 세로 중심 x=12). w=선 굵기(현재 좌표 기준). */
private fun DrawScope.thermo(w: Float, detail: Boolean) {
    drawPath(TUBE, Color.White)
    drawPath(TUBE, TEMP_FG, style = Stroke(width = w, join = StrokeJoin.Round))
    drawRoundRect(TEMP_FG, Offset(12f - w / 2, 8f), Size(w, 9f), CornerRadius(w / 2, w / 2))
    drawCircle(TEMP_FG, radius = 2.8f, center = Offset(12f, 17.5f))
    if (detail) {
        drawLine(TEMP_FG, Offset(16.2f, 6f), Offset(18.8f, 6f), 1.5f, StrokeCap.Round)
        drawLine(TEMP_FG, Offset(16.2f, 10f), Offset(18.2f, 10f), 1.5f, StrokeCap.Round)
    }
}

private fun DrawScope.wave(p: Path, w: Float) =
    drawPath(p, CUR_FG, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** 관측소 아이콘 한 칸. NONE이면 같은 크기의 빈 자리(emptySize)만 차지한다. */
@Composable
fun StationIcon(kind: StationKind, size: Dp = 20.dp, emptySize: Dp = size) {
    if (kind == StationKind.NONE) {
        Spacer(Modifier.size(emptySize))
        return
    }
    Canvas(Modifier.size(size).semantics { contentDescription = kind.desc ?: "" }) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, 24f, 24f)) }
            when (kind) {
                StationKind.TEMP -> {
                    drawCircle(TEMP_BG, 12f, Offset(12f, 12f))
                    scale(0.8f, pivot = Offset(12f, 12f)) { thermo(2.2f, true) }
                }
                StationKind.CURRENT -> {
                    drawCircle(CUR_BG, 12f, Offset(12f, 12f))
                    wave(WAVE_BIG, 2.0f)
                    wave(WAVE_BIG2, 2.0f)
                    drawLine(CUR_FG, Offset(7f, 17f), Offset(17f, 17f), 2.0f, StrokeCap.Round)
                    drawLine(CUR_FG, Offset(14.2f, 14.3f), Offset(17f, 17f), 2.0f, StrokeCap.Round)
                    drawLine(CUR_FG, Offset(14.2f, 19.7f), Offset(17f, 17f), 2.0f, StrokeCap.Round)
                }
                else -> { // BOTH: 반반
                    clipPath(circle) {
                        drawRect(TEMP_BG, Offset(0f, 0f), Size(12f, 24f))
                        drawRect(CUR_BG, Offset(12f, 0f), Size(12f, 24f))
                    }
                    // 왼쪽 반: 온도계를 줄여 x=6.2 쪽으로 옮겨 그린다
                    translate(left = -5.8f, top = 0f) {
                        scale(0.7f, pivot = Offset(12f, 12f)) { thermo(3.0f, false) }
                    }
                    wave(WAVE_SM, 2.1f)
                    wave(WAVE_SM2, 2.1f)
                }
            }
        }
    }
}
