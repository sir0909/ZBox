package com.zbox.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.core.CubicSplineLut
import com.zbox.ai.ui.ZColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 交互式色调曲线编辑器。
 *
 * 交互设计：
 *  - 画布即 [0,255]² 坐标系，左下原点，Y 轴向上（跟 PS / 尼康机身一致）
 *  - 拖动已有控制点改位置；点击空白处新增点；长按控制点删除
 *  - 顶部叠加 256-LUT 实际插值曲线（青色），控制点之间用直线预览（虚线不可见则省略）
 *  - 背景画灰阶斜条纹，方便判断曲线是否压暗/提亮
 */
@Composable
fun CurveEditorCanvas(
    lut: IntArray,
    points: List<CubicSplineLut.Point>,
    onPointsChange: (List<CubicSplineLut.Point>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggingIndex by remember { mutableStateOf(-1) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val latestPoints by rememberUpdatedState(points)
    val latestOnChange by rememberUpdatedState(onPointsChange)
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZColors.SurfaceHigh)
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (canvasSize.width <= 0f) return@awaitEachGesture
                        val pad = 18f
                        val w = canvasSize.width - pad * 2
                        val h = canvasSize.height - pad * 2
                        val curPts = latestPoints
                        var best = -1
                        var bestDist = Float.MAX_VALUE
                        curPts.forEachIndexed { i, p ->
                            val px = pad + (p.x / 255f) * w
                            val py = pad + h - (p.y / 255f) * h
                            val d = hypot(down.position.x - px, down.position.y - py)
                            if (d < bestDist) { bestDist = d; best = i }
                        }

                        if (bestDist < 42f && best >= 0) {
                            var activeIdx = best
                            draggingIndex = activeIdx
                            val touchSlop = viewConfiguration.touchSlop
                            var moved = false
                            val downTime = System.currentTimeMillis()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val distFromDown = hypot(
                                    change.position.x - down.position.x,
                                    change.position.y - down.position.y,
                                )
                                if (!moved && distFromDown < touchSlop &&
                                    System.currentTimeMillis() - downTime >= viewConfiguration.longPressTimeoutMillis
                                ) {
                                    val ptsNow = latestPoints
                                    if (activeIdx in 1 until ptsNow.lastIndex && ptsNow.size > 2) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val next = ptsNow.toMutableList().apply { removeAt(activeIdx) }
                                        latestOnChange(next)
                                    }
                                    draggingIndex = -1
                                    while (true) {
                                        val ev2 = awaitPointerEvent()
                                        if (ev2.changes.none { it.pressed }) break
                                    }
                                    break
                                }
                                if (distFromDown >= touchSlop) moved = true
                                if (moved) {
                                    change.consume()
                                    val ptsNow = latestPoints
                                    if (activeIdx in ptsNow.indices) {
                                        val nx = ((change.position.x - pad) / w * 255f).coerceIn(0f, 255f)
                                        val ny = ((pad + h - change.position.y) / h * 255f).coerceIn(0f, 255f)
                                        val mutable = ptsNow.toMutableList()
                                        val updatedPt = when (activeIdx) {
                                            0 -> CubicSplineLut.Point(0f, ny)
                                            ptsNow.lastIndex -> CubicSplineLut.Point(255f, ny)
                                            else -> CubicSplineLut.Point(nx.coerceIn(1f, 254f), ny)
                                        }
                                        mutable[activeIdx] = updatedPt
                                        val sorted = mutable.sortedBy { it.x }
                                        if (activeIdx in 1 until ptsNow.lastIndex) {
                                            val newIdx = sorted.indexOfFirst {
                                                abs(it.x - updatedPt.x) < 0.01f && abs(it.y - updatedPt.y) < 0.01f
                                            }
                                            if (newIdx in 1 until sorted.lastIndex) activeIdx = newIdx
                                        }
                                        draggingIndex = activeIdx
                                        latestOnChange(sorted)
                                    }
                                }
                            }
                            draggingIndex = -1
                        } else {
                            val nxp = ((down.position.x - pad) / w * 255f).coerceIn(1f, 254f)
                            val nyp = ((pad + h - down.position.y) / h * 255f).coerceIn(0f, 255f)
                            var activeIdx = -1
                            if (curPts.size < 20) {
                                val next = (curPts + CubicSplineLut.Point(nxp, nyp)).sortedBy { it.x }
                                latestOnChange(next)
                                activeIdx = next.indexOfFirst {
                                    abs(it.x - nxp) < 0.6f && abs(it.y - nyp) < 0.6f
                                }
                                draggingIndex = activeIdx
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val ptsNow = latestPoints
                                if (activeIdx in 1 until ptsNow.lastIndex) {
                                    change.consume()
                                    val nx = ((change.position.x - pad) / w * 255f).coerceIn(1f, 254f)
                                    val ny = ((pad + h - change.position.y) / h * 255f).coerceIn(0f, 255f)
                                    val mutable = ptsNow.toMutableList()
                                    val updatedPt = CubicSplineLut.Point(nx, ny)
                                    mutable[activeIdx] = updatedPt
                                    val sorted = mutable.sortedBy { it.x }
                                    val newIdx = sorted.indexOfFirst {
                                        abs(it.x - updatedPt.x) < 0.01f && abs(it.y - updatedPt.y) < 0.01f
                                    }
                                    if (newIdx in 1 until sorted.lastIndex) activeIdx = newIdx
                                    draggingIndex = activeIdx
                                    latestOnChange(sorted)
                                }
                            }
                            draggingIndex = -1
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                canvasSize = size
                drawGrid()
                drawDiagonal()
                drawLutCurve(lut)
                drawControlPoints(points)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "控制点 ${points.size}/20 · 点击空白新增 · 拖动调整 · 长按中间点删点",
                color = ZColors.TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "x0→y${points.firstOrNull()?.y?.roundToInt() ?: 0}  x255→y${points.lastOrNull()?.y?.roundToInt() ?: 255}",
                color = ZColors.YellowDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun hypot(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

private const val PAD = 18f

private fun DrawScope.plotWidth() = size.width - PAD * 2
private fun DrawScope.plotHeight() = size.height - PAD * 2

private fun DrawScope.toScreen(x: Float, y: Float): Offset {
    val w = plotWidth()
    val h = plotHeight()
    return Offset(PAD + (x / 255f) * w, PAD + h - (y / 255f) * h)
}

private fun DrawScope.drawGrid() {
    // 背景灰阶（从左到右由黑到白），带一点暗化，让曲线看得清
    val steps = 64
    val w = plotWidth()
    val h = plotHeight()
    for (i in 0 until steps) {
        val v = (i * 255 / (steps - 1))
        val c = Color(v, v, v).copy(alpha = 0.10f)
        drawRect(
            color = c,
            topLeft = Offset(PAD + w * i / steps, PAD),
            size = Size(w / steps + 1f, h),
        )
    }
    // 网格线：每 1/4
    for (i in 0..4) {
        val gx = PAD + w * i / 4f
        val gy = PAD + h * i / 4f
        drawLine(ZColors.Outline.copy(alpha = 0.55f), Offset(gx, PAD), Offset(gx, PAD + h), strokeWidth = 1f)
        drawLine(ZColors.Outline.copy(alpha = 0.55f), Offset(PAD, gy), Offset(PAD + w, gy), strokeWidth = 1f)
    }
    // 边框
    drawRect(
        color = ZColors.Outline,
        topLeft = Offset(PAD, PAD),
        size = Size(w, h),
        style = Stroke(width = 1.5f),
    )
    // 轴标注
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#6B6B6B")
        textSize = 22f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.apply {
        drawText("0", PAD - 4f, PAD + h + 26f, paint)
        drawText("255", PAD + w - 30f, PAD + h + 26f, paint)
        drawText("OUT", 2f, PAD + 14f, paint)
        drawText("IN", PAD + w - 34f, PAD - 4f, paint)
    }
}

private fun DrawScope.drawDiagonal() {
    drawLine(
        color = ZColors.Outline,
        start = toScreen(0f, 0f),
        end = toScreen(255f, 255f),
        strokeWidth = 1.5f,
    )
}

private fun DrawScope.drawLutCurve(lut: IntArray) {
    if (lut.size < 256) return
    val path = Path()
    for (i in 0..255) {
        val p = toScreen(i.toFloat(), lut[i].toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    // 发光底层
    drawPath(path, color = ZColors.NikonYellow.copy(alpha = 0.18f), style = Stroke(width = 7f))
    drawPath(path, color = ZColors.NikonYellow, style = Stroke(width = 2.2f))
}

private fun DrawScope.drawControlPoints(points: List<CubicSplineLut.Point>) {
    points.forEach { p ->
        val o = toScreen(p.x, p.y)
        drawCircle(color = ZColors.Bg, radius = 7.5f, center = o)
        drawCircle(color = ZColors.NikonYellow, radius = 5.5f, center = o)
    }
}

/** 小尺寸曲线缩略图（预设卡片上用）。 */
@Composable
fun CurveThumbnail(lut: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(ZColors.SurfaceHigh)) {
        val w = size.width
        val h = size.height
        if (lut.size < 256) return@Canvas
        drawLine(
            color = ZColors.Outline,
            start = Offset(0f, h),
            end = Offset(w, 0f),
            strokeWidth = 1f,
        )
        val path = Path()
        for (i in 0..255) {
            val x = i / 255f * w
            val y = h - (lut[i] / 255f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = ZColors.NikonYellow, style = Stroke(width = 1.8f))
    }
}
