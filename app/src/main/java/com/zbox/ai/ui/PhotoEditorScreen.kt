package com.zbox.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zbox.ai.core.BuiltInPresets
import com.zbox.ai.core.CubicSplineLut
import com.zbox.ai.editor.CropAspect
import com.zbox.ai.editor.EditorToolTab
import com.zbox.ai.editor.ExportFormat
import com.zbox.ai.editor.HistogramData
import com.zbox.ai.editor.HslBand
import com.zbox.ai.editor.LocalMask
import com.zbox.ai.editor.LrEditParams
import com.zbox.ai.editor.LrImageEngine
import com.zbox.ai.editor.MaskPoint
import com.zbox.ai.editor.MaskType
import com.zbox.ai.editor.PhotoEditorController
import com.zbox.ai.editor.PhotoEditorState
import com.zbox.ai.ui.components.CurveEditorCanvas
import com.zbox.ai.ui.components.CurveThumbnail
import com.zbox.ai.ui.components.EditableSliderRow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PhotoEditorScreen(
    ui: UiState,
    vm: MainViewModel,
    onPickEditorPhoto: () -> Unit,
) {
    val controller = vm.editor
    val st by controller.state.collectAsStateWithLifecycle()
    var savePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("我的调色预设") }
    var exportDialogOpen by remember { mutableStateOf(false) }

    if (savePresetDialog) {
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            containerColor = ZColors.SurfaceHigh,
            title = { Text("保存为个人预设", color = ZColors.TextPrimary, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it.take(24) },
                    singleLine = true,
                    label = { Text("预设名称", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZColors.NikonYellow,
                        unfocusedBorderColor = ZColors.Outline,
                        focusedTextColor = ZColors.TextPrimary,
                        unfocusedTextColor = ZColors.TextPrimary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.saveCurrentAsPreset(presetNameInput)
                    savePresetDialog = false
                }) { Text("保存", color = ZColors.NikonYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { savePresetDialog = false }) { Text("取消", color = ZColors.TextSecondary) }
            },
        )
    }

    if (exportDialogOpen) {
        AlertDialog(
            onDismissRequest = { exportDialogOpen = false },
            containerColor = ZColors.SurfaceHigh,
            title = { Text("保存修图到手机相册", color = ZColors.TextPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将按原图分辨率应用全部调色、曲线与裁剪，导出至系统相册 Pictures/Zbox。",
                        color = ZColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    if (st.originalWidth > 0 && st.originalHeight > 0) {
                        val cw = ((st.params.cropRight - st.params.cropLeft) *
                            if (st.params.rotation90 % 2 == 1) st.originalHeight else st.originalWidth).roundToInt()
                        val ch = ((st.params.cropBottom - st.params.cropTop) *
                            if (st.params.rotation90 % 2 == 1) st.originalWidth else st.originalHeight).roundToInt()
                        Text(
                            "输出分辨率约：${cw} × ${ch}",
                            color = ZColors.Mono,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    ExportFormat.entries.forEach { fmt ->
                        val selected = st.exportFormat == fmt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) ZColors.NikonYellow.copy(alpha = 0.14f) else ZColors.Surface)
                                .border(1.dp, if (selected) ZColors.NikonYellow else ZColors.Outline, RoundedCornerShape(8.dp))
                                .clickable { controller.setExportFormat(fmt) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                fmt.label,
                                color = if (selected) ZColors.NikonYellow else ZColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Text("✓", color = ZColors.NikonYellow, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportDialogOpen = false
                    controller.exportToPhoneGallery()
                }) { Text("立即保存", color = ZColors.NikonYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { exportDialogOpen = false }) { Text("取消", color = ZColors.TextSecondary) }
            },
        )
    }

    st.lastExport?.let { res ->
        AlertDialog(
            onDismissRequest = { controller.dismissLastExport() },
            containerColor = ZColors.SurfaceHigh,
            title = { Text("已保存到手机相册", color = ZColors.Success, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("文件名：${res.displayName}", color = ZColors.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("分辨率：${res.width} × ${res.height}", color = ZColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("大小：${"%.2f MB".format(res.sizeBytes / 1024.0 / 1024.0)}", color = ZColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("位置：${res.relativePath}", color = ZColors.NikonYellow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.dismissLastExport() }) {
                    Text("好的", color = ZColors.NikonYellow, fontWeight = FontWeight.Bold)
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
        EditorTopBar(
            st = st,
            onPickPhoto = onPickEditorPhoto,
            onUndo = { controller.undo() },
            onRedo = { controller.redo() },
            onToggleSplit = { controller.toggleSplitCompare() },
            onToggleHistogram = { controller.toggleHistogram() },
            onSavePreset = {
                presetNameInput = st.params.appliedPresetName.ifBlank { "我的调色预设" }
                savePresetDialog = true
            },
            onReset = { controller.resetAllParams() },
            onExport = { exportDialogOpen = true },
        )

        EditorViewport(
            st = st,
            controller = controller,
            onPickPhoto = onPickEditorPhoto,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        EditorToolStrip(
            active = st.activeTool,
            onSelect = { controller.selectTool(it) },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(238.dp)
                .background(ZColors.Surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            when (st.activeTool) {
                EditorToolTab.PRESETS -> PresetsPanel(st, ui, controller)
                EditorToolTab.LIGHT -> LightPanel(st.params, controller)
                EditorToolTab.COLOR -> ColorPanel(st.params, controller)
                EditorToolTab.HSL -> HslPanel(st.params, st.selectedHslBand, controller)
                EditorToolTab.CURVE -> CurvePanel(st.params, controller)
                EditorToolTab.MASK -> MaskPanel(st, controller)
                EditorToolTab.DETAIL -> DetailPanel(st.params, controller)
                EditorToolTab.CROP -> CropPanel(st.params, controller)
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    st: PhotoEditorState,
    onPickPhoto: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleSplit: () -> Unit,
    onToggleHistogram: () -> Unit,
    onSavePreset: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZColors.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickPhoto, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.AddPhotoAlternate, "选择照片", tint = ZColors.NikonYellow, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = onUndo, enabled = st.canUndo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Undo, "撤销", tint = if (st.canUndo) ZColors.TextPrimary else ZColors.TextTertiary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRedo, enabled = st.canRedo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Redo, "重做", tint = if (st.canRedo) ZColors.TextPrimary else ZColors.TextTertiary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onToggleSplit, enabled = st.hasPhoto, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Compare,
                "分屏对比",
                tint = if (st.splitCompare) ZColors.NikonYellow else if (st.hasPhoto) ZColors.TextSecondary else ZColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onToggleHistogram, enabled = st.hasPhoto, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.ShowChart,
                "直方图",
                tint = if (st.showHistogram && st.hasPhoto) ZColors.Mono else ZColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onSavePreset, enabled = st.hasPhoto, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.BookmarkAdd, "存预设", tint = if (st.hasPhoto) ZColors.TextSecondary else ZColors.TextTertiary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onReset, enabled = st.hasPhoto && !st.params.isIdentity, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Refresh, "全部重置", tint = if (st.hasPhoto && !st.params.isIdentity) ZColors.TextSecondary else ZColors.TextTertiary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onExport,
            enabled = st.hasPhoto && !st.exporting,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ZColors.ButtonBg,
                contentColor = ZColors.ButtonFg,
                disabledContainerColor = ZColors.SurfaceHigher,
                disabledContentColor = ZColors.TextTertiary,
            ),
        ) {
            if (st.exporting) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = ZColors.ButtonFg, strokeWidth = 2.dp)
                Spacer(Modifier.width(5.dp))
                Text("导出中", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存相册", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EditorViewport(
    st: PhotoEditorState,
    controller: PhotoEditorController,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCropMode = st.activeTool == EditorToolTab.CROP && st.hasPhoto
    val orientedOrig = remember(st.previewOriginal, st.params.rotation90, st.params.flipHorizontal, isCropMode) {
        val orig = st.previewOriginal ?: return@remember null
        if (!isCropMode && !st.params.hasGeometryChange) orig
        else {
            val noCrop = st.params.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f)
            LrImageEngine.applyGeometry(orig, noCrop)
        }
    }
    val compareOrig = remember(
        st.previewOriginal,
        st.params.rotation90,
        st.params.flipHorizontal,
        st.params.cropLeft,
        st.params.cropTop,
        st.params.cropRight,
        st.params.cropBottom,
        st.splitCompare,
        st.holdingOriginal,
    ) {
        val orig = st.previewOriginal ?: return@remember null
        if (!st.params.hasGeometryChange) orig else LrImageEngine.applyGeometry(orig, st.params)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (ZColors.isLightTheme) ZColors.SurfaceHigh else Color(0xFF08090A))
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            st.loadingSource -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ZColors.NikonYellow, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("正在读取高清照片…", color = ZColors.TextSecondary, fontSize = 12.sp)
                }
            }
            !st.hasPhoto -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.AutoFixHigh, null, tint = ZColors.NikonYellow, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(16.dp))
                    ZPrimaryButton(
                        text = "从手机选择照片",
                        onClick = onPickPhoto,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
            }
            isCropMode && orientedOrig != null -> {
                CropInteractiveCanvas(
                    bitmap = orientedOrig,
                    params = st.params,
                    controller = controller,
                )
            }
            st.activeTool == EditorToolTab.MASK && st.activeMask != null && !st.holdingOriginal && !st.splitCompare -> {
                val edited = st.previewEdited ?: st.previewOriginal!!
                MaskInteractiveViewport(
                    bitmap = edited,
                    overlayBitmap = if (st.shouldShowMaskOverlay) st.maskOverlayBitmap else null,
                    mask = st.activeMask!!,
                    brushErase = st.maskBrushErase,
                    controller = controller,
                )
            }
            else -> {
                val edited = st.previewEdited ?: st.previewOriginal!!
                val orig = compareOrig ?: st.previewOriginal!!
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(st.splitCompare) {
                            if (st.splitCompare) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    controller.setSplitPos((change.position.x / size.width.toFloat()).coerceIn(0.05f, 0.95f))
                                }
                            } else {
                                detectTapGestures(onPress = {
                                    controller.setHoldingOriginal(true)
                                    tryAwaitRelease()
                                    controller.setHoldingOriginal(false)
                                })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (st.holdingOriginal) {
                        Image(
                            bitmap = orig.asImageBitmap(),
                            contentDescription = "原图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else if (st.splitCompare) {
                        Image(
                            bitmap = orig.asImageBitmap(),
                            contentDescription = "原图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        Image(
                            bitmap = edited.asImageBitmap(),
                            contentDescription = "调色后",
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    val splitX = size.width * st.splitPos
                                    clipRect(left = splitX, top = 0f, right = size.width, bottom = size.height) {
                                        this@drawWithContent.drawContent()
                                    }
                                },
                            contentScale = ContentScale.Fit,
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val x = size.width * st.splitPos
                            drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                            drawCircle(ZColors.NikonYellow, radius = 12.dp.toPx(), center = Offset(x, size.height * 0.5f))
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xAA000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("原图 | 调色", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Image(
                            bitmap = edited.asImageBitmap(),
                            contentDescription = "调色预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                if (st.showHistogram && st.histogram != null && !st.holdingOriginal) {
                    HistogramOverlay(
                        hist = st.histogram!!,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(width = 112.dp, height = 46.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xB8000000))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = when {
                                st.holdingOriginal -> "正在查看原图"
                                st.splitCompare -> "左右拖动分割线对比"
                                st.params.appliedPresetName.isNotBlank() -> "${st.params.appliedPresetName} · 长按看原图"
                                else -> "长按画面看原图"
                            },
                            color = if (st.holdingOriginal) ZColors.NikonYellow else Color(0xFFE0E0E0),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (st.renderingPreview) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), color = ZColors.NikonYellow, strokeWidth = 1.6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistogramOverlay(hist: HistogramData, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xA6000000))
            .border(0.5.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp))
            .padding(4.dp),
    ) {
        val bins = hist.luma.size
        if (bins < 2 || hist.maxCount <= 0) return@Canvas
        val dx = size.width / (bins - 1)
        val maxH = size.height
        val denom = hist.maxCount.toFloat()

        fun drawChannel(arr: IntArray, color: Color) {
            val path = Path().apply {
                moveTo(0f, maxH)
                for (i in 0 until bins) {
                    val x = i * dx
                    val y = maxH - (arr[i] / denom).coerceIn(0f, 1f) * maxH
                    lineTo(x, y)
                }
                lineTo(size.width, maxH)
                close()
            }
            drawPath(path, color)
        }
        drawChannel(hist.red, Color(0x55FF4D4F))
        drawChannel(hist.green, Color(0x5535D39A))
        drawChannel(hist.blue, Color(0x5540A9FF))
        drawChannel(hist.luma, Color(0x66FFFFFF))
    }
}

@Composable
private fun CropInteractiveCanvas(
    bitmap: Bitmap,
    params: LrEditParams,
    controller: PhotoEditorController,
) {
    val latestParams by rememberUpdatedState(params)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = min(boxW / bitmap.width.toFloat(), boxH / bitmap.height.toFloat())
        val imgW = bitmap.width * scale
        val imgH = bitmap.height * scale
        val imgWdp = with(density) { imgW.toDp() }
        val imgHdp = with(density) { imgH.toDp() }

        var activeHandle by remember { mutableStateOf(-1) }

        Box(modifier = Modifier.size(imgWdp, imgHdp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "裁剪原图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap.width, bitmap.height) {
                        detectDragGestures(
                            onDragStart = { start ->
                                controller.pushUndoCheckpoint()
                                val p = latestParams
                                val lx = p.cropLeft * size.width
                                val ty = p.cropTop * size.height
                                val rx = p.cropRight * size.width
                                val by = p.cropBottom * size.height
                                val hitR = 32.dp.toPx()
                                val pts = listOf(
                                    Offset(lx, ty),
                                    Offset(rx, ty),
                                    Offset(lx, by),
                                    Offset(rx, by),
                                )
                                activeHandle = pts.indexOfFirst { (it - start).getDistance() <= hitR }
                                if (activeHandle < 0 && start.x in lx..rx && start.y in ty..by) {
                                    activeHandle = 4
                                }
                            },
                            onDragEnd = { activeHandle = -1 },
                            onDragCancel = { activeHandle = -1 },
                            onDrag = { change, drag ->
                                change.consume()
                                val dx = drag.x / size.width.toFloat()
                                val dy = drag.y / size.height.toFloat()
                                val minSpan = 0.12f
                                controller.updateParams(recordUndo = false, immediate = false) { p ->
                                    var l = p.cropLeft
                                    var t = p.cropTop
                                    var r = p.cropRight
                                    var b = p.cropBottom
                                    when (activeHandle) {
                                        0 -> { l = (l + dx).coerceIn(0f, r - minSpan); t = (t + dy).coerceIn(0f, b - minSpan) }
                                        1 -> { r = (r + dx).coerceIn(l + minSpan, 1f); t = (t + dy).coerceIn(0f, b - minSpan) }
                                        2 -> { l = (l + dx).coerceIn(0f, r - minSpan); b = (b + dy).coerceIn(t + minSpan, 1f) }
                                        3 -> { r = (r + dx).coerceIn(l + minSpan, 1f); b = (b + dy).coerceIn(t + minSpan, 1f) }
                                        4 -> {
                                            val wSpan = r - l
                                            val hSpan = b - t
                                            l = (l + dx).coerceIn(0f, 1f - wSpan)
                                            t = (t + dy).coerceIn(0f, 1f - hSpan)
                                            r = l + wSpan
                                            b = t + hSpan
                                        }
                                    }
                                    p.copy(
                                        aspect = if (activeHandle in 0..3) CropAspect.FREE else p.aspect,
                                        cropLeft = l,
                                        cropTop = t,
                                        cropRight = r,
                                        cropBottom = b,
                                    )
                                }
                            },
                        )
                    },
            ) {
                val lx = params.cropLeft * size.width
                val ty = params.cropTop * size.height
                val rx = params.cropRight * size.width
                val by = params.cropBottom * size.height
                val cw = rx - lx
                val ch = by - ty

                val dim = Color(0x99000000)
                drawRect(dim, Offset.Zero, Size(size.width, ty))
                drawRect(dim, Offset(0f, by), Size(size.width, size.height - by))
                drawRect(dim, Offset(0f, ty), Size(lx, ch))
                drawRect(dim, Offset(rx, ty), Size(size.width - rx, ch))

                drawRect(Color.White, Offset(lx, ty), Size(cw, ch), style = Stroke(1.5.dp.toPx()))
                val gridColor = Color.White.copy(alpha = 0.45f)
                for (k in 1..2) {
                    val gx = lx + cw * (k / 3f)
                    val gy = ty + ch * (k / 3f)
                    drawLine(gridColor, Offset(gx, ty), Offset(gx, by), strokeWidth = 0.8.dp.toPx())
                    drawLine(gridColor, Offset(lx, gy), Offset(rx, gy), strokeWidth = 0.8.dp.toPx())
                }
                val hr = 6.dp.toPx()
                listOf(Offset(lx, ty), Offset(rx, ty), Offset(lx, by), Offset(rx, by)).forEach { pt ->
                    drawCircle(ZColors.NikonYellow, radius = hr, center = pt)
                }
            }
        }
    }
}

@Composable
private fun EditorToolStrip(active: EditorToolTab, onSelect: (EditorToolTab) -> Unit) {
    val icons = mapOf(
        EditorToolTab.PRESETS to Icons.Filled.AutoFixHigh,
        EditorToolTab.LIGHT to Icons.Filled.WbSunny,
        EditorToolTab.COLOR to Icons.Filled.Palette,
        EditorToolTab.HSL to Icons.Filled.Visibility,
        EditorToolTab.CURVE to Icons.Filled.ShowChart,
        EditorToolTab.MASK to Icons.Filled.Layers,
        EditorToolTab.DETAIL to Icons.Filled.Tune,
        EditorToolTab.CROP to Icons.Filled.Crop,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZColors.SurfaceHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EditorToolTab.entries.forEach { tab ->
            val selected = tab == active
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) ZColors.ButtonBg else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icons[tab] ?: Icons.Filled.Tune,
                    contentDescription = tab.label,
                    tint = if (selected) ZColors.ButtonFg else ZColors.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tab.label,
                    color = if (selected) ZColors.ButtonFg else ZColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LrSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    defaultValue: Float = 0f,
    format: String = "%+.0f",
    controller: PhotoEditorController,
    onChange: (Float) -> Unit,
) {
    val decimals = when {
        format.contains(".2") -> 2
        format.contains(".1") -> 1
        else -> 0
    }
    EditableSliderRow(
        label = label,
        value = value,
        min = min,
        max = max,
        defaultValue = defaultValue,
        format = format,
        inputDecimals = decimals,
        onDragStart = { controller.pushUndoCheckpoint() },
        onBeforeDirectEdit = { controller.pushUndoCheckpoint() },
        onChange = onChange,
    )
}

@Composable
private fun LightPanel(p: LrEditParams, controller: PhotoEditorController) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LrSliderRow("曝光 Exposure", p.exposure, -2f, 2f, 0f, "%+.2f EV", controller) { v ->
            controller.updateParams { it.copy(exposure = v) }
        }
        LrSliderRow("对比度 Contrast", p.contrast, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(contrast = v) }
        }
        LrSliderRow("高光 Highlights", p.highlights, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(highlights = v) }
        }
        LrSliderRow("阴影 Shadows", p.shadows, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(shadows = v) }
        }
        LrSliderRow("白色色阶 Whites", p.whites, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(whites = v) }
        }
        LrSliderRow("黑色色阶 Blacks", p.blacks, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(blacks = v) }
        }
    }
}

@Composable
private fun ColorPanel(p: LrEditParams, controller: PhotoEditorController) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LrSliderRow("色温 Temp（蓝 ↔ 黄）", p.temperature, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(temperature = v) }
        }
        LrSliderRow("色调 Tint（绿 ↔ 品红）", p.tint, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(tint = v) }
        }
        LrSliderRow("自然饱和度 Vibrance", p.vibrance, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(vibrance = v) }
        }
        LrSliderRow("饱和度 Saturation", p.saturation, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(saturation = v) }
        }
    }
}

@Composable
private fun HslPanel(p: LrEditParams, selectedBand: HslBand, controller: PhotoEditorController) {
    val adj = p.hslOf(selectedBand)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HslBand.entries.forEach { band ->
                val sel = band == selectedBand
                val modified = !p.hslOf(band).isNeutral
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) ZColors.SurfaceHigher else Color.Transparent)
                        .border(1.dp, if (sel) ZColors.NikonYellow else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { controller.selectHslBand(band) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(band.colorHex))
                            .border(if (modified) 2.dp else 0.dp, ZColors.TextPrimary, CircleShape),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(band.label, color = if (sel) ZColors.NikonYellow else ZColors.TextSecondary, fontSize = 9.sp)
                }
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LrSliderRow("${selectedBand.label} · 色相 Hue", adj.hue, -100f, 100f, 0f, "%+.0f", controller) { v ->
                controller.updateParams { it.withHsl(selectedBand, adj.copy(hue = v)) }
            }
            LrSliderRow("${selectedBand.label} · 饱和度 Sat", adj.sat, -100f, 100f, 0f, "%+.0f", controller) { v ->
                controller.updateParams { it.withHsl(selectedBand, adj.copy(sat = v)) }
            }
            LrSliderRow("${selectedBand.label} · 明亮度 Lum", adj.lum, -100f, 100f, 0f, "%+.0f", controller) { v ->
                controller.updateParams { it.withHsl(selectedBand, adj.copy(lum = v)) }
            }
        }
    }
}

@Composable
private fun CurvePanel(p: LrEditParams, controller: PhotoEditorController) {
    val lut = remember(p.curvePoints) { CubicSplineLut.buildLut(p.curvePoints) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("拖动节点微调 · 长按删除节点", color = ZColors.TextTertiary, fontSize = 10.sp)
            Text(
                "恢复线性",
                color = ZColors.NikonYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, ZColors.NikonYellow.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                    .clickable { controller.resetCurve() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        CurveEditorCanvas(
            lut = lut,
            points = p.curvePoints,
            onPointsChange = { controller.updateCurvePoints(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        LrSliderRow("曲线 / 预设强度", p.lutStrength * 100f, 0f, 100f, 100f, "%.0f%%", controller) { v ->
            controller.updateParams { it.copy(lutStrength = v / 100f) }
        }
    }
}

@Composable
private fun DetailPanel(p: LrEditParams, controller: PhotoEditorController) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LrSliderRow("清晰度 Clarity", p.clarity, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(clarity = v) }
        }
        LrSliderRow("锐化 Sharpening", p.sharpening, 0f, 100f, 0f, "%.0f", controller) { v ->
            controller.updateParams { it.copy(sharpening = v) }
        }
        LrSliderRow("暗角 Vignette", p.vignette, -100f, 100f, 0f, "%+.0f", controller) { v ->
            controller.updateParams { it.copy(vignette = v) }
        }
        LrSliderRow("胶片颗粒 Grain", p.grain, 0f, 100f, 0f, "%.0f", controller) { v ->
            controller.updateParams { it.copy(grain = v) }
        }
    }
}

@Composable
private fun CropPanel(p: LrEditParams, controller: PhotoEditorController) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CropToolButton("左转 90°", Icons.Filled.RotateLeft, Modifier.weight(1f)) { controller.rotate90(-1) }
            CropToolButton("右转 90°", Icons.Filled.RotateRight, Modifier.weight(1f)) { controller.rotate90(1) }
            CropToolButton("水平镜像", Icons.Filled.Flip, Modifier.weight(1f)) { controller.toggleFlipHorizontal() }
            CropToolButton("重置裁剪", Icons.Filled.Refresh, Modifier.weight(1f)) { controller.resetCropOnly() }
        }
        Text("裁切比例（可在上方画面拖动四角调整范围）", color = ZColors.TextTertiary, fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CropAspect.entries.forEach { aspect ->
                val selected = p.aspect == aspect
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) ZColors.ButtonBg else ZColors.SurfaceHigh)
                        .border(1.dp, if (selected) ZColors.ButtonBorder else ZColors.Outline, RoundedCornerShape(7.dp))
                        .clickable { controller.setCropAspect(aspect) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        aspect.label,
                        color = if (selected) ZColors.ButtonFg else ZColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun CropToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ZColors.SurfaceHigh)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = ZColors.TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = ZColors.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun PresetsPanel(st: PhotoEditorState, ui: UiState, controller: PhotoEditorController) {
    val builtIn = remember(st.presetCategory, st.presetQuery) {
        val base = if (st.presetQuery.isNotBlank()) BuiltInPresets.search(st.presetQuery)
        else BuiltInPresets.byCategory(st.presetCategory)
        base.take(50)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZColors.NikonYellow.copy(alpha = 0.16f))
                    .border(1.dp, ZColors.NikonYellow, RoundedCornerShape(6.dp))
                    .clickable { controller.applyRecipePreset(ui.recipe, "逆向配方·${ui.recipe.name}") }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("套用逆向配方", color = ZColors.NikonYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            val cats = listOf(null to "全部") + BuiltInPresets.CATEGORIES
            cats.forEach { (id, label) ->
                val sel = st.presetCategory == id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) ZColors.ButtonBg else ZColors.SurfaceHigh)
                        .clickable { controller.setPresetCategory(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(label, color = if (sel) ZColors.ButtonFg else ZColors.TextSecondary, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(st.savedPresets, key = { "saved_${it.id}" }) { saved ->
                val active = st.params.appliedPresetName == saved.name
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) ZColors.NikonYellow.copy(alpha = 0.14f) else ZColors.SurfaceHigh)
                        .border(1.dp, if (active) ZColors.NikonYellow else ZColors.Outline, RoundedCornerShape(8.dp))
                        .clickable { controller.applySavedLrPreset(saved) }
                        .padding(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的", color = ZColors.Success, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Filled.DeleteOutline,
                            "删除",
                            tint = ZColors.TextTertiary,
                            modifier = Modifier.size(13.dp).clickable { controller.deleteSavedPreset(saved.id) },
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    CurveThumbnail(lut = CubicSplineLut.buildLut(saved.params.curvePoints), modifier = Modifier.fillMaxWidth().height(36.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(saved.name, color = ZColors.TextPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(builtIn, key = { it.id }) { preset ->
                val active = st.params.appliedPresetName == preset.displayName
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) ZColors.NikonYellow.copy(alpha = 0.14f) else ZColors.SurfaceHigh)
                        .border(1.dp, if (active) ZColors.NikonYellow else ZColors.Outline, RoundedCornerShape(8.dp))
                        .clickable { controller.applyBuiltInPreset(preset) }
                        .padding(6.dp),
                ) {
                    Text(preset.categoryLabel, color = ZColors.TextTertiary, fontSize = 8.sp)
                    Spacer(Modifier.height(2.dp))
                    CurveThumbnail(lut = preset.recipe.lut, modifier = Modifier.fillMaxWidth().height(36.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(preset.displayName, color = ZColors.TextPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LrSliderRow("预设强度", st.params.lutStrength * 100f, 0f, 100f, 100f, "%.0f%%", controller) { v ->
            controller.updateParams { it.copy(lutStrength = v / 100f) }
        }
    }
}

@Composable
private fun MaskInteractiveViewport(
    bitmap: Bitmap,
    overlayBitmap: Bitmap?,
    mask: LocalMask,
    brushErase: Boolean,
    controller: PhotoEditorController,
) {
    val latestMask by rememberUpdatedState(mask)
    val latestErase by rememberUpdatedState(brushErase)
    val liveBrushPts = remember { mutableStateListOf<MaskPoint>() }
    var dragMode by remember { mutableStateOf(0) } // 0=none, 1=center/start, 2=rx/end, 3=ry, 4=moveLinear

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = min(boxW / bitmap.width.toFloat(), boxH / bitmap.height.toFloat())
        val imgW = (bitmap.width * scale).coerceAtLeast(1f)
        val imgH = (bitmap.height * scale).coerceAtLeast(1f)
        val imgWdp = with(density) { imgW.toDp() }
        val imgHdp = with(density) { imgH.toDp() }

        Box(modifier = Modifier.size(imgWdp, imgHdp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "蒙版预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (overlayBitmap != null) {
                Image(
                    bitmap = overlayBitmap.asImageBitmap(),
                    contentDescription = "蒙版区域",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mask.id, mask.type) {
                        if (mask.type == MaskType.LUMINANCE || mask.type.isAi) {
                            detectTapGestures(onPress = {
                                controller.setHoldingOriginal(true)
                                tryAwaitRelease()
                                controller.setHoldingOriginal(false)
                            })
                        } else {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val m = latestMask
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    when (m.type) {
                                        MaskType.RADIAL -> {
                                            controller.pushUndoCheckpoint()
                                            controller.setTransientMaskOverlay(true)
                                            val cx = m.centerX * w
                                            val cy = m.centerY * h
                                            val rxPt = Offset((m.centerX + m.radiusX) * w, cy)
                                            val ryPt = Offset(cx, (m.centerY + m.radiusY) * h)
                                            val hitR = 28.dp.toPx()
                                            dragMode = when {
                                                (start - rxPt).getDistance() <= hitR -> 2
                                                (start - ryPt).getDistance() <= hitR -> 3
                                                else -> 1
                                            }
                                        }
                                        MaskType.LINEAR -> {
                                            controller.pushUndoCheckpoint()
                                            controller.setTransientMaskOverlay(true)
                                            val p0 = Offset(m.startX * w, m.startY * h)
                                            val p1 = Offset(m.endX * w, m.endY * h)
                                            val hitR = 28.dp.toPx()
                                            dragMode = when {
                                                (start - p0).getDistance() <= hitR -> 1
                                                (start - p1).getDistance() <= hitR -> 2
                                                else -> 4
                                            }
                                        }
                                        MaskType.BRUSH -> {
                                            liveBrushPts.clear()
                                            liveBrushPts.add(
                                                MaskPoint(
                                                    (start.x / w).coerceIn(0f, 1f),
                                                    (start.y / h).coerceIn(0f, 1f),
                                                )
                                            )
                                        }
                                        else -> Unit
                                    }
                                },
                                onDragEnd = {
                                    if (latestMask.type == MaskType.BRUSH && liveBrushPts.isNotEmpty()) {
                                        controller.appendBrushStroke(liveBrushPts.toList())
                                        liveBrushPts.clear()
                                    }
                                    controller.setTransientMaskOverlay(false)
                                    dragMode = 0
                                },
                                onDragCancel = {
                                    liveBrushPts.clear()
                                    controller.setTransientMaskOverlay(false)
                                    dragMode = 0
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val m = latestMask
                                    when (m.type) {
                                        MaskType.RADIAL -> {
                                            val dx = drag.x / w
                                            val dy = drag.y / h
                                            controller.updateSelectedMask(recordUndo = false, immediate = false) { cur ->
                                                when (dragMode) {
                                                    2 -> cur.copy(radiusX = (cur.radiusX + dx).coerceIn(0.05f, 1.2f))
                                                    3 -> cur.copy(radiusY = (cur.radiusY + dy).coerceIn(0.05f, 1.2f))
                                                    else -> cur.copy(
                                                        centerX = (cur.centerX + dx).coerceIn(0f, 1f),
                                                        centerY = (cur.centerY + dy).coerceIn(0f, 1f),
                                                    )
                                                }
                                            }
                                        }
                                        MaskType.LINEAR -> {
                                            val dx = drag.x / w
                                            val dy = drag.y / h
                                            controller.updateSelectedMask(recordUndo = false, immediate = false) { cur ->
                                                when (dragMode) {
                                                    1 -> cur.copy(
                                                        startX = (cur.startX + dx).coerceIn(-0.2f, 1.2f),
                                                        startY = (cur.startY + dy).coerceIn(-0.2f, 1.2f),
                                                    )
                                                    2 -> cur.copy(
                                                        endX = (cur.endX + dx).coerceIn(-0.2f, 1.2f),
                                                        endY = (cur.endY + dy).coerceIn(-0.2f, 1.2f),
                                                    )
                                                    else -> cur.copy(
                                                        startX = cur.startX + dx,
                                                        startY = cur.startY + dy,
                                                        endX = cur.endX + dx,
                                                        endY = cur.endY + dy,
                                                    )
                                                }
                                            }
                                        }
                                        MaskType.BRUSH -> {
                                            val nx = (change.position.x / w).coerceIn(0f, 1f)
                                            val ny = (change.position.y / h).coerceIn(0f, 1f)
                                            val prev = liveBrushPts.lastOrNull()
                                            if (prev == null || hypot((nx - prev.x).toDouble(), (ny - prev.y).toDouble()) > 0.005) {
                                                liveBrushPts.add(MaskPoint(nx, ny))
                                            }
                                        }
                                        else -> Unit
                                    }
                                },
                            )
                        }
                    },
            ) {
                val m = latestMask
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                when (m.type) {
                    MaskType.RADIAL -> {
                        val cx = m.centerX * size.width
                        val cy = m.centerY * size.height
                        val rx = m.radiusX * size.width
                        val ry = m.radiusY * size.height
                        drawOval(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(cx - rx, cy - ry),
                            size = Size(rx * 2f, ry * 2f),
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                        val innerScale = (1f - m.feather).coerceIn(0.05f, 0.98f)
                        drawOval(
                            color = Color.White.copy(alpha = 0.5f),
                            topLeft = Offset(cx - rx * innerScale, cy - ry * innerScale),
                            size = Size(rx * 2f * innerScale, ry * 2f * innerScale),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                        )
                        drawCircle(ZColors.NikonYellow, radius = 6.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(cx + rx, cy))
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(cx, cy + ry))
                    }
                    MaskType.LINEAR -> {
                        val p0 = Offset(m.startX * size.width, m.startY * size.height)
                        val p1 = Offset(m.endX * size.width, m.endY * size.height)
                        drawLine(
                            color = Color.White.copy(alpha = 0.88f),
                            start = p0,
                            end = p1,
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = dash,
                        )
                        drawCircle(ZColors.NikonYellow, radius = 7.dp.toPx(), center = p0)
                        drawCircle(Color.White, radius = 6.dp.toPx(), center = p1, style = Stroke(width = 2.dp.toPx()))
                    }
                    MaskType.BRUSH -> {
                        if (liveBrushPts.isNotEmpty()) {
                            val strokePx = m.brushSize * max(size.width, size.height) * 2f
                            val path = Path().apply {
                                moveTo(liveBrushPts[0].x * size.width, liveBrushPts[0].y * size.height)
                                for (i in 1 until liveBrushPts.size) {
                                    lineTo(liveBrushPts[i].x * size.width, liveBrushPts[i].y * size.height)
                                }
                            }
                            drawPath(
                                path = path,
                                color = if (latestErase) Color(0x8833B5E5) else Color(0x88FF384E),
                                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun MaskPanel(st: PhotoEditorState, controller: PhotoEditorController) {
    val masks = st.params.masks
    val active = st.activeMask
    var showCreateBar by remember(masks.isEmpty()) { mutableStateOf(masks.isEmpty()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                masks.forEach { m ->
                    val selected = active?.id == m.id
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) ZColors.ButtonBg else ZColors.SurfaceHigh)
                            .border(
                                1.dp,
                                if (selected) ZColors.ButtonBorder else ZColors.Outline,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { controller.selectMask(m.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (m.enabled) m.name else "${m.name}(关)",
                            color = if (selected) ZColors.ButtonFg else if (m.enabled) ZColors.TextPrimary else ZColors.TextTertiary,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (showCreateBar) ZColors.SurfaceHigher else ZColors.SurfaceHigh)
                        .border(1.dp, ZColors.NikonYellow.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .clickable { showCreateBar = !showCreateBar }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = ZColors.NikonYellow, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("新建", color = ZColors.NikonYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (active != null) {
                IconButton(onClick = { controller.toggleMaskOverlay() }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (st.showMaskOverlay) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "红膜显隐",
                        tint = if (st.showMaskOverlay) ZColors.NikonYellow else ZColors.TextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { controller.toggleMaskInvert(active.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.InvertColors,
                        contentDescription = "反选蒙版",
                        tint = if (active.invert) ZColors.NikonYellow else ZColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { controller.deleteMask(active.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除蒙版",
                        tint = ZColors.Danger,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (showCreateBar) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MaskType.entries.filter { it.isAi }.forEach { t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(ZColors.SurfaceHigh)
                                .border(1.dp, ZColors.NikonYellow.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                                .clickable(enabled = !st.detectingMask) {
                                    controller.addMask(t)
                                    showCreateBar = false
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.label, color = ZColors.NikonYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MaskType.entries.filterNot { it.isAi }.forEach { t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(ZColors.SurfaceHigh)
                                .border(1.dp, ZColors.Outline, RoundedCornerShape(7.dp))
                                .clickable(enabled = !st.detectingMask) {
                                    controller.addMask(t)
                                    showCreateBar = false
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.label, color = ZColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (st.detectingMask) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ZColors.NikonYellow, strokeWidth = 2.dp)
                    Text("正在 AI 智能识别选区…", color = ZColors.TextSecondary, fontSize = 11.sp)
                }
            }
        } else if (active == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "支持 AI 识别人物/天空/主体/背景，以及径向/线性/画笔/亮度蒙版",
                    color = ZColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        } else {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (active.type) {
                            MaskType.AI_PERSON,
                            MaskType.AI_SKY,
                            MaskType.AI_SUBJECT,
                            MaskType.AI_BACKGROUND -> "已识别${active.type.label} · 点右上角眼睛可查看红膜"
                            MaskType.RADIAL -> "拖动中心移动 · 拖动白点缩放半径"
                            MaskType.LINEAR -> "黄点 100% 生效 · 白圈 0% 过渡"
                            MaskType.BRUSH -> "直接在画面涂抹（共 ${active.strokes.size} 笔）"
                            MaskType.LUMINANCE -> "按像素亮度自动选区 · 适合天空/高光/暗部"
                        },
                        color = ZColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = if (active.enabled) "已启用" else "已暂停",
                        color = if (active.enabled) ZColors.Success else ZColors.TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .border(1.dp, ZColors.Outline, RoundedCornerShape(5.dp))
                            .clickable { controller.toggleMaskEnabled(active.id) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                when (active.type) {
                    MaskType.AI_PERSON,
                    MaskType.AI_SKY,
                    MaskType.AI_SUBJECT,
                    MaskType.AI_BACKGROUND -> Unit
                    MaskType.RADIAL -> {
                        LrSliderRow("羽化 Feather", active.feather * 100f, 0f, 100f, 45f, "%.0f%%", controller) { v ->
                            controller.updateSelectedMask { it.copy(feather = v / 100f) }
                        }
                    }
                    MaskType.LINEAR -> Unit
                    MaskType.BRUSH -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (!st.maskBrushErase) ZColors.ButtonBg else ZColors.SurfaceHigh)
                                    .clickable { controller.setMaskBrushErase(false) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Brush, null, tint = if (!st.maskBrushErase) ZColors.ButtonFg else ZColors.TextSecondary, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("涂抹", color = if (!st.maskBrushErase) ZColors.ButtonFg else ZColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (st.maskBrushErase) ZColors.ButtonBg else ZColors.SurfaceHigh)
                                    .clickable { controller.setMaskBrushErase(true) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("橡皮擦", color = if (st.maskBrushErase) ZColors.ButtonFg else ZColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ZColors.SurfaceHigh)
                                    .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                                    .clickable(enabled = active.strokes.isNotEmpty()) { controller.undoLastBrushStroke() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("撤一笔", color = if (active.strokes.isNotEmpty()) ZColors.TextPrimary else ZColors.TextTertiary, fontSize = 10.sp)
                            }
                        }
                        LrSliderRow("画笔大小", active.brushSize * 100f, 1.5f, 25f, 8f, "%.1f%%", controller) { v ->
                            controller.updateSelectedMask { it.copy(brushSize = v / 100f) }
                        }
                        LrSliderRow("画笔羽化", active.brushFeather * 100f, 0f, 100f, 50f, "%.0f%%", controller) { v ->
                            controller.updateSelectedMask { it.copy(brushFeather = v / 100f) }
                        }
                    }
                    MaskType.LUMINANCE -> {
                        LrSliderRow("亮度下限", active.lumaMin * 100f, 0f, 100f, 55f, "%.0f%%", controller) { v ->
                            controller.updateSelectedMask { cur ->
                                val mn = (v / 100f).coerceIn(0f, 1f)
                                cur.copy(lumaMin = mn, lumaMax = max(cur.lumaMax, mn))
                            }
                        }
                        LrSliderRow("亮度上限", active.lumaMax * 100f, 0f, 100f, 100f, "%.0f%%", controller) { v ->
                            controller.updateSelectedMask { cur ->
                                val mx = (v / 100f).coerceIn(0f, 1f)
                                cur.copy(lumaMax = mx, lumaMin = min(cur.lumaMin, mx))
                            }
                        }
                        LrSliderRow("过渡羽化", active.lumaFeather * 100f, 2f, 45f, 15f, "%.0f%%", controller) { v ->
                            controller.updateSelectedMask { it.copy(lumaFeather = v / 100f) }
                        }
                    }
                }

                LrSliderRow("蒙版不透明度", active.opacity * 100f, 0f, 100f, 100f, "%.0f%%", controller) { v ->
                    controller.updateSelectedMask { it.copy(opacity = v / 100f) }
                }
                LrSliderRow("局部曝光", active.exposure, -2f, 2f, 0f, "%+.2f EV", controller) { v ->
                    controller.updateSelectedMask { it.copy(exposure = v) }
                }
                LrSliderRow("局部对比度", active.contrast, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(contrast = v) }
                }
                LrSliderRow("局部高光", active.highlights, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(highlights = v) }
                }
                LrSliderRow("局部阴影", active.shadows, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(shadows = v) }
                }
                LrSliderRow("局部色温", active.temperature, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(temperature = v) }
                }
                LrSliderRow("局部色调", active.tint, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(tint = v) }
                }
                LrSliderRow("局部饱和度", active.saturation, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(saturation = v) }
                }
                LrSliderRow("局部清晰度", active.clarity, -100f, 100f, 0f, "%+.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(clarity = v) }
                }
                LrSliderRow("局部锐化", active.sharpening, 0f, 100f, 0f, "%.0f", controller) { v ->
                    controller.updateSelectedMask { it.copy(sharpening = v) }
                }
            }
        }
    }
}
