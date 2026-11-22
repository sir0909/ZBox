package com.zbox.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.ai.LocalCompositionEngine
import com.zbox.ai.core.CompositionMode
import com.zbox.ai.core.LocalCompStyle
import com.zbox.ai.core.LocalCompositionGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun CompositionPanel(ui: UiState, vm: MainViewModel) {
    if (!ui.compositionOpen) return
    var cropPreview by remember(ui.compositionAdvice, ui.compositionSnapshot) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ZColors.SurfaceHigh)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (ui.compositionMode == CompositionMode.LOCAL) "本地智能构图 · 实时引导" else "AI Agent 构图",
                    color = ZColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("可在「设置 → 构图模式」切换", color = ZColors.TextTertiary, fontSize = 10.sp)
            }
            TextButton(onClick = { vm.clearComposition(close = true) }) { Text("关闭", color = ZColors.TextSecondary) }
        }
        Spacer(Modifier.height(6.dp))

        if (ui.compositionMode == CompositionMode.LOCAL) {
            LocalCompositionSection(ui = ui, vm = vm)
        } else {
            Text("仅点分析才发送一张缩小快照到当前 AI 供应商。每次点击一次请求，失败不自动重试。", fontSize = 12.sp, color = ZColors.TextSecondary)
            Text("当前：${ui.activeAccount.provider.displayName} / ${ui.activeAccount.model}", fontSize = 11.sp, color = ZColors.TextSecondary)
            Row {
                TextButton(onClick = vm::analyzeComposition, enabled = !ui.compositionBusy) {
                    Text(if (ui.compositionBusy) "分析中…" else if (ui.compositionSnapshot == null) "分析当前画面" else "重新分析")
                }
                TextButton(onClick = { vm.clearComposition() }) { Text(if (ui.compositionBusy) "取消请求" else "清除建议") }
            }
            if (ui.compositionBusy) Text("正在分析本次快照；取消不能保证服务端不计费。", color = ZColors.YellowDim, fontSize = 12.sp)
            if (ui.compositionError.isNotBlank()) Text(ui.compositionError, color = ZColors.Danger, fontSize = 12.sp)
            val snapshot = ui.compositionSnapshot
            val advice = ui.compositionAdvice
            if (snapshot != null) {
                Text("分析快照 · 非实时跟踪；移动后请手动重新分析", fontSize = 11.sp, color = ZColors.TextSecondary)
                Box(Modifier.fillMaxWidth().aspectRatio(snapshot.width.toFloat() / snapshot.height).background(ZColors.Surface)) {
                    Image(snapshot.asImageBitmap(), "本次分析快照", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (advice != null) Canvas(Modifier.fillMaxSize()) {
                        fun drawBox(b: com.zbox.ai.core.CompositionBox, color: Color) {
                            drawRect(
                                color,
                                Offset(b.left * size.width, b.top * size.height),
                                Size((b.right - b.left) * size.width, (b.bottom - b.top) * size.height),
                                style = Stroke(2.dp.toPx()),
                            )
                        }
                        advice.subject?.let { drawBox(it, Color(0xFF67C7FF)) }
                        drawBox(advice.crop, Color(0xFFFFD35F))
                    }
                }
                if (advice != null) {
                    Text("黄框：推荐裁切构图；蓝框：主体（如能识别）", fontSize = 11.sp, color = ZColors.TextSecondary)
                    Text(advice.summary, color = ZColors.TextPrimary, fontSize = 14.sp)
                    advice.moves.forEach { Text("机位 → $it", color = ZColors.TextPrimary, fontSize = 13.sp) }
                    advice.tips.forEach { Text("拍法 · $it", color = ZColors.TextPrimary, fontSize = 13.sp) }
                    Text("单张画面无法确认画外环境或精确距离，请留意周围安全。", fontSize = 11.sp, color = ZColors.YellowDim)
                    TextButton(onClick = { cropPreview = !cropPreview }) { Text(if (cropPreview) "收起裁切示意" else "预览推荐构图") }
                    if (cropPreview) {
                        val cropped = remember(snapshot, advice.crop) {
                            val b = advice.crop
                            val x = (b.left * snapshot.width).toInt().coerceIn(0, snapshot.width - 1)
                            val y = (b.top * snapshot.height).toInt().coerceIn(0, snapshot.height - 1)
                            val w = ((b.right - b.left) * snapshot.width).toInt().coerceIn(1, snapshot.width - x)
                            val h = ((b.bottom - b.top) * snapshot.height).toInt().coerceIn(1, snapshot.height - y)
                            Bitmap.createBitmap(snapshot, x, y, w, h)
                        }
                        Text("裁切示意 · 未改变镜头变焦，也不改变实际照片范围", fontSize = 11.sp, color = ZColors.YellowDim)
                        Image(cropped.asImageBitmap(), "推荐构图裁切示意", Modifier.fillMaxWidth().aspectRatio(cropped.width.toFloat() / cropped.height), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCompositionSection(ui: UiState, vm: MainViewModel) {
    Text(
        "离线实时识别主体与黄金三分点（0 流量 · 0 Token）。移动机位让蓝点重合黄圈准星即可拍摄。",
        fontSize = 11.sp,
        color = ZColors.TextSecondary,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LocalCompStyle.entries.forEach { style ->
            val selected = ui.localCompStyle == style
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) ZColors.SurfaceHigher else ZColors.Surface)
                    .border(1.dp, if (selected) ZColors.NikonYellow.copy(alpha = 0.7f) else ZColors.Outline, RoundedCornerShape(7.dp))
                    .clickable { vm.setLocalCompStyle(style) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    style.label,
                    color = if (selected) ZColors.NikonYellow else ZColors.TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val guide = ui.localCompGuide
    when {
        !ui.isConnected || !ui.liveViewActive || ui.liveViewBitmap == null -> {
            Text("等待实时取景画面开启后自动开始引导…", color = ZColors.TextTertiary, fontSize = 12.sp)
        }
        guide == null -> {
            Text("正在快速分析取景画面…", color = ZColors.TextSecondary, fontSize = 12.sp)
        }
        else -> {
            val badgeBg = if (guide.aligned) ZColors.Success.copy(alpha = 0.16f) else ZColors.NikonYellow.copy(alpha = 0.14f)
            val badgeFg = if (guide.aligned) ZColors.Success else ZColors.NikonYellow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(ZColors.Surface)
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(9.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${guide.score}分", color = badgeFg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(guide.moveHint, color = if (guide.aligned) ZColors.Success else ZColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${guide.sceneTag} · ${guide.detailHint}", color = ZColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun LocalCompositionHudOverlay(
    frame: Bitmap?,
    ui: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val active = ui.compositionOpen && ui.compositionMode == CompositionMode.LOCAL && ui.liveViewActive && frame != null
    val latestFrame by rememberUpdatedState(frame)
    val latestStyle by rememberUpdatedState(ui.localCompStyle)
    LaunchedEffect(active) {
        if (!active) {
            vm.updateLocalComposition(null)
            return@LaunchedEffect
        }
        var prevFrame: Bitmap? = null
        var prevStyle: LocalCompStyle? = null
        while (isActive) {
            val f = latestFrame
            val curStyle = latestStyle
            if (f != null && (f !== prevFrame || curStyle != prevStyle)) {
                prevFrame = f
                prevStyle = curStyle
                val guide = withContext(Dispatchers.Default) {
                    LocalCompositionEngine.analyze(f, curStyle)
                }
                vm.updateLocalComposition(guide)
            }
            delay(220)
        }
    }
    if (!active) return
    val guide: LocalCompositionGuide = ui.localCompGuide ?: return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fit = min(size.width / frame.width.toFloat(), size.height / frame.height.toFloat())
            val w = frame.width * fit
            val h = frame.height * fit
            val left = (size.width - w) * 0.5f
            val top = (size.height - h) * 0.5f

            // Subtle rule-of-thirds guides
            val gridColor = Color.White.copy(alpha = 0.22f)
            for (i in 1..2) {
                val gx = left + w * i / 3f
                val gy = top + h * i / 3f
                drawLine(gridColor, Offset(gx, top), Offset(gx, top + h), 0.9.dp.toPx())
                drawLine(gridColor, Offset(left, gy), Offset(left + w, gy), 0.9.dp.toPx())
            }

            val accent = if (guide.aligned) Color(0xFF43DB91) else Color(0xFFFFE600)
            val subjColor = if (guide.aligned) Color(0xFF43DB91) else Color(0xFF67C7FF)

            guide.horizonY?.let { hy ->
                val yPos = top + hy * h
                drawLine(
                    color = Color(0xFF67C7FF).copy(alpha = 0.55f),
                    start = Offset(left + w * 0.08f, yPos),
                    end = Offset(left + w * 0.92f, yPos),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                )
            }

            val centerX = left + w * 0.5f
            val centerY = top + h * 0.5f
            val levelOk = kotlin.math.abs(guide.tiltDeg) <= 2.2f
            val levelColor = if (levelOk) Color(0xFF43DB91).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.65f)
            val halfSpan = 22.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.35f), Offset(centerX - halfSpan - 8.dp.toPx(), centerY), Offset(centerX - halfSpan, centerY), 1.4.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.35f), Offset(centerX + halfSpan, centerY), Offset(centerX + halfSpan + 8.dp.toPx(), centerY), 1.4.dp.toPx())
            rotate(degrees = guide.tiltDeg, pivot = Offset(centerX, centerY)) {
                drawLine(levelColor, Offset(centerX - halfSpan * 0.72f, centerY), Offset(centerX + halfSpan * 0.72f, centerY), 1.8.dp.toPx())
            }

            // Subject corner brackets
            if (guide.sceneTag != "地平线三分") {
                drawCornerBox(
                    left = left + guide.subjectBox.left * w,
                    top = top + guide.subjectBox.top * h,
                    right = left + guide.subjectBox.right * w,
                    bottom = top + guide.subjectBox.bottom * h,
                    color = subjColor.copy(alpha = 0.92f),
                    cornerLen = 12.dp.toPx(),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            val cx = left + guide.subjectCenterX * w
            val cy = top + guide.subjectCenterY * h
            val tx = left + guide.targetX * w
            val ty = top + guide.targetY * h

            // Guide line between current subject center and target anchor
            if (!guide.aligned) {
                drawLine(
                    color = accent.copy(alpha = 0.85f),
                    start = Offset(cx, cy),
                    end = Offset(tx, ty),
                    strokeWidth = 1.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )
            }

            // Target anchor crosshair ring
            val ringR = if (guide.aligned) 11.dp.toPx() else 9.dp.toPx()
            drawCircle(color = accent, radius = ringR, center = Offset(tx, ty), style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = accent.copy(alpha = 0.25f), radius = ringR, center = Offset(tx, ty))

            // Subject center dot
            drawCircle(color = subjColor, radius = 4.5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(cx, cy))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.68f))
                .border(
                    1.dp,
                    if (guide.aligned) Color(0xFF43DB91).copy(alpha = 0.8f) else Color(0xFFFFE600).copy(alpha = 0.55f),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = "${guide.score}分 · ${guide.subjectLabel} · ${guide.moveHint}",
                color = if (guide.aligned) Color(0xFF43DB91) else Color(0xFFFFE600),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun DrawScope.drawCornerBox(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    cornerLen: Float,
    strokeWidth: Float,
) {
    val len = min(cornerLen, min((right - left) * 0.35f, (bottom - top) * 0.35f)).coerceAtLeast(4f)
    // Top-left
    drawLine(color, Offset(left, top), Offset(left + len, top), strokeWidth)
    drawLine(color, Offset(left, top), Offset(left, top + len), strokeWidth)
    // Top-right
    drawLine(color, Offset(right - len, top), Offset(right, top), strokeWidth)
    drawLine(color, Offset(right, top), Offset(right, top + len), strokeWidth)
    // Bottom-left
    drawLine(color, Offset(left, bottom - len), Offset(left, bottom), strokeWidth)
    drawLine(color, Offset(left, bottom), Offset(left + len, bottom), strokeWidth)
    // Bottom-right
    drawLine(color, Offset(right - len, bottom), Offset(right, bottom), strokeWidth)
    drawLine(color, Offset(right, bottom - len), Offset(right, bottom), strokeWidth)
}
