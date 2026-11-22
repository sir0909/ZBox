package com.zbox.ai.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.zbox.ai.core.BuiltInPreset
import com.zbox.ai.core.CubicSplineLut
import com.zbox.ai.core.Recipe
import com.zbox.ai.data.SettingsStore
import com.zbox.ai.util.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class EditorToolTab(val label: String) {
    PRESETS("预设"),
    LIGHT("光效"),
    COLOR("色彩"),
    HSL("HSL"),
    CURVE("曲线"),
    MASK("蒙版"),
    DETAIL("细节"),
    CROP("裁剪"),
}

data class PhotoEditorState(
    val sourceName: String = "",
    val sourceUri: Uri? = null,
    val sourceFilePath: String? = null,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val previewOriginal: Bitmap? = null,
    val previewEdited: Bitmap? = null,
    val histogram: HistogramData? = null,
    val showHistogram: Boolean = true,
    val params: LrEditParams = LrEditParams.DEFAULT,
    val undoStack: List<LrEditParams> = emptyList(),
    val redoStack: List<LrEditParams> = emptyList(),
    val activeTool: EditorToolTab = EditorToolTab.LIGHT,
    val selectedHslBand: HslBand = HslBand.RED,
    val splitCompare: Boolean = false,
    val splitPos: Float = 0.5f,
    val holdingOriginal: Boolean = false,
    val loadingSource: Boolean = false,
    val renderingPreview: Boolean = false,
    val exporting: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.JPEG_HIGH,
    val lastExport: ExportResult? = null,
    val savedPresets: List<SavedLrPreset> = emptyList(),
    val presetCategory: String? = null,
    val presetQuery: String = "",
    val selectedMaskId: String? = null,
    val showMaskOverlay: Boolean = false,
    val transientMaskOverlay: Boolean = false,
    val detectingMask: Boolean = false,
    val maskBrushErase: Boolean = false,
    val maskOverlayBitmap: Bitmap? = null,
) {
    val hasPhoto: Boolean get() = previewOriginal != null
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val activeMask: LocalMask?
        get() = params.masks.firstOrNull { it.id == selectedMaskId } ?: params.masks.lastOrNull()
    val shouldShowMaskOverlay: Boolean
        get() = showMaskOverlay || transientMaskOverlay
}

class PhotoEditorController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val onToast: (String) -> Unit,
) {
    private val _state = MutableStateFlow(PhotoEditorState())
    val state: StateFlow<PhotoEditorState> = _state.asStateFlow()

    private var renderJob: Job? = null
    private var overlayJob: Job? = null
    private var aiMaskJob: Job? = null
    private var loadJob: Job? = null
    private var exportJob: Job? = null

    init {
        scope.launch {
            store.lrPresets.collect { list ->
                _state.update { it.copy(savedPresets = list) }
            }
        }
    }

    fun selectTool(tab: EditorToolTab) {
        _state.update { it.copy(activeTool = tab) }
        scheduleMaskOverlayRender()
    }

    fun selectHslBand(band: HslBand) {
        _state.update { it.copy(selectedHslBand = band) }
    }

    fun toggleHistogram() {
        _state.update { it.copy(showHistogram = !it.showHistogram) }
    }

    fun toggleSplitCompare() {
        _state.update { it.copy(splitCompare = !it.splitCompare) }
    }

    fun setSplitPos(pos: Float) {
        _state.update { it.copy(splitPos = pos.coerceIn(0.05f, 0.95f)) }
    }

    fun setHoldingOriginal(hold: Boolean) {
        _state.update { it.copy(holdingOriginal = hold) }
    }

    fun setExportFormat(fmt: ExportFormat) {
        _state.update { it.copy(exportFormat = fmt) }
    }

    fun setPresetCategory(cat: String?) {
        _state.update { it.copy(presetCategory = cat) }
    }

    fun setPresetQuery(q: String) {
        _state.update { it.copy(presetQuery = q) }
    }

    fun dismissLastExport() {
        _state.update { it.copy(lastExport = null) }
    }

    fun openFromUri(uri: Uri) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loadingSource = true, lastExport = null) }
            val loaded = withContext(Dispatchers.IO) {
                val preview = ImageLoader.load(app, uri, 1280) ?: return@withContext null
                val name = queryDisplayName(uri) ?: "IMG_${System.currentTimeMillis() % 10000}"
                val dims = queryImageBounds(uri) ?: (preview.width to preview.height)
                val hist = LrImageEngine.computeHistogram(preview)
                Triple(preview, name, Pair(dims, hist))
            }
            if (loaded == null) {
                _state.update { it.copy(loadingSource = false) }
                onToast("无法读取所选照片")
                return@launch
            }
            val (preview, name, meta) = loaded
            val (dims, hist) = meta
            _state.update {
                it.copy(
                    sourceName = name,
                    sourceUri = uri,
                    sourceFilePath = null,
                    originalWidth = dims.first,
                    originalHeight = dims.second,
                    previewOriginal = preview,
                    previewEdited = preview,
                    histogram = hist,
                    params = LrEditParams.DEFAULT,
                    undoStack = emptyList(),
                    redoStack = emptyList(),
                    loadingSource = false,
                    selectedMaskId = null,
                    maskOverlayBitmap = null,
                )
            }
            scheduleMaskOverlayRender()
        }
    }

    fun openFromBytesOrBitmap(
        name: String,
        rawBytes: ByteArray?,
        fallbackBitmap: Bitmap?,
        initialParams: LrEditParams? = null,
    ) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loadingSource = true, lastExport = null) }
            val prepared = withContext(Dispatchers.IO) {
                val cacheDir = File(app.cacheDir, "lr_source").apply { mkdirs() }
                cacheDir.listFiles()?.forEach { old -> old.delete() }
                val outFile = File(cacheDir, "active_source.jpg")

                val fullBmp = if (rawBytes != null) {
                    val jpegBytes = extractJpegBytes(rawBytes)
                    if (jpegBytes != null) {
                        FileOutputStream(outFile).use { it.write(jpegBytes) }
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    } else null
                } else null

                val sourceBmp = fullBmp ?: fallbackBitmap?.let { LrImageEngine.ensureSoftwareArgb8888(it) }
                if (sourceBmp == null) return@withContext null

                if (!outFile.exists() || outFile.length() == 0L) {
                    FileOutputStream(outFile).use { fos ->
                        sourceBmp.compress(Bitmap.CompressFormat.JPEG, 98, fos)
                    }
                }
                val preview = downscaleIfNeeded(sourceBmp, 1280)
                val dims = sourceBmp.width to sourceBmp.height
                if (fullBmp != null && fullBmp !== preview) fullBmp.recycle()
                Triple(preview, outFile.absolutePath, dims)
            }

            if (prepared == null) {
                _state.update { it.copy(loadingSource = false) }
                onToast("照片解码失败")
                return@launch
            }
            val (preview, path, dims) = prepared
            val params = (initialParams ?: LrEditParams.DEFAULT).clamped()
            val edited = withContext(Dispatchers.Default) {
                if (params.isIdentity) preview else LrImageEngine.render(preview, params)
            }
            val hist = withContext(Dispatchers.Default) {
                LrImageEngine.computeHistogram(edited)
            }
            _state.update {
                it.copy(
                    sourceName = name.ifBlank { "ZBOX_PHOTO" },
                    sourceUri = null,
                    sourceFilePath = path,
                    originalWidth = dims.first,
                    originalHeight = dims.second,
                    previewOriginal = preview,
                    previewEdited = edited,
                    histogram = hist,
                    params = params,
                    undoStack = emptyList(),
                    redoStack = emptyList(),
                    loadingSource = false,
                    selectedMaskId = params.masks.lastOrNull()?.id,
                )
            }
            scheduleMaskOverlayRender()
        }
    }

    /** 在连续拖动滑杆前先压栈一次撤销点。 */
    fun pushUndoCheckpoint() {
        _state.update { st ->
            if (!st.hasPhoto) return@update st
            val last = st.undoStack.lastOrNull()
            if (last == st.params) st
            else st.copy(
                undoStack = (st.undoStack + st.params).takeLast(30),
                redoStack = emptyList(),
            )
        }
    }

    fun updateParams(
        recordUndo: Boolean = false,
        immediate: Boolean = false,
        transform: (LrEditParams) -> LrEditParams,
    ) {
        _state.update { st ->
            val next = transform(st.params).clamped()
            if (next == st.params) return@update st
            val undo = if (recordUndo && st.undoStack.lastOrNull() != st.params) {
                (st.undoStack + st.params).takeLast(30)
            } else {
                st.undoStack
            }
            val redo = if (recordUndo) emptyList() else st.redoStack
            st.copy(params = next, undoStack = undo, redoStack = redo)
        }
        schedulePreviewRender(immediate = immediate)
    }

    fun applyBuiltInPreset(preset: BuiltInPreset) {
        updateParams(recordUndo = true, immediate = true) { cur ->
            LrPresetCodec.fromRecipe(preset.recipe, preset.displayName, cur)
        }
        onToast("已应用预设：${preset.displayName}")
    }

    fun applyRecipePreset(recipe: Recipe, label: String = recipe.name) {
        updateParams(recordUndo = true, immediate = true) { cur ->
            LrPresetCodec.fromRecipe(recipe, label, cur)
        }
        onToast("已应用配方：$label")
    }

    fun applySavedLrPreset(saved: SavedLrPreset) {
        updateParams(recordUndo = true, immediate = true) { cur ->
            saved.params.copy(
                rotation90 = cur.rotation90,
                flipHorizontal = cur.flipHorizontal,
                aspect = cur.aspect,
                cropLeft = cur.cropLeft,
                cropTop = cur.cropTop,
                cropRight = cur.cropRight,
                cropBottom = cur.cropBottom,
                appliedPresetName = saved.name,
            )
        }
        onToast("已应用我的预设：${saved.name}")
    }

    fun resetAllParams() {
        updateParams(recordUndo = true, immediate = true) { LrEditParams.DEFAULT }
        onToast("已还原所有调色与裁剪")
    }

    fun resetCropOnly() {
        updateParams(recordUndo = true, immediate = true) {
            it.copy(
                rotation90 = 0,
                flipHorizontal = false,
                aspect = CropAspect.ORIGINAL,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 1f,
                cropBottom = 1f,
            )
        }
    }

    fun rotate90(delta: Int = 1) {
        updateParams(recordUndo = true, immediate = true) {
            val nextRot = (((it.rotation90 + delta) % 4) + 4) % 4
            it.copy(rotation90 = nextRot)
        }
    }

    fun toggleFlipHorizontal() {
        updateParams(recordUndo = true, immediate = true) {
            it.copy(flipHorizontal = !it.flipHorizontal)
        }
    }

    fun setCropAspect(aspect: CropAspect) {
        val st = _state.value
        val orig = st.previewOriginal ?: return
        val swapped = (st.params.rotation90 % 2) == 1
        val imgW = (if (swapped) orig.height else orig.width).toFloat().coerceAtLeast(1f)
        val imgH = (if (swapped) orig.width else orig.height).toFloat().coerceAtLeast(1f)
        val imgAspect = imgW / imgH
        val target = when (aspect) {
            CropAspect.ORIGINAL -> imgAspect
            CropAspect.FREE -> {
                updateParams(recordUndo = true, immediate = true) { it.copy(aspect = aspect) }
                return
            }
            else -> aspect.ratio ?: imgAspect
        }
        val (nw, nh) = if (target > imgAspect) {
            1f to (imgAspect / target).coerceIn(0.15f, 1f)
        } else {
            (target / imgAspect).coerceIn(0.15f, 1f) to 1f
        }
        val l = (1f - nw) * 0.5f
        val t = (1f - nh) * 0.5f
        updateParams(recordUndo = true, immediate = true) {
            it.copy(
                aspect = aspect,
                cropLeft = l,
                cropTop = t,
                cropRight = l + nw,
                cropBottom = t + nh,
            )
        }
    }

    fun updateCurvePoints(points: List<CubicSplineLut.Point>) {
        val clean = points
            .map { CubicSplineLut.Point(it.x.coerceIn(0f, 255f), it.y.coerceIn(0f, 255f)) }
            .sortedBy { it.x }
        if (clean.size < 2) return
        updateParams(recordUndo = false, immediate = false) {
            it.copy(curvePoints = clean)
        }
    }

    fun resetCurve() {
        updateParams(recordUndo = true, immediate = true) {
            it.copy(curvePoints = Recipe.DEFAULT_CURVE, lutStrength = 1f)
        }
    }

    fun undo() {
        _state.update { st ->
            val prev = st.undoStack.lastOrNull() ?: return@update st
            val selId = prev.masks.firstOrNull { it.id == st.selectedMaskId }?.id ?: prev.masks.lastOrNull()?.id
            st.copy(
                params = prev,
                undoStack = st.undoStack.dropLast(1),
                redoStack = (st.redoStack + st.params).takeLast(30),
                selectedMaskId = selId,
            )
        }
        schedulePreviewRender(immediate = true)
    }

    fun redo() {
        _state.update { st ->
            val next = st.redoStack.lastOrNull() ?: return@update st
            val selId = next.masks.firstOrNull { it.id == st.selectedMaskId }?.id ?: next.masks.lastOrNull()?.id
            st.copy(
                params = next,
                undoStack = (st.undoStack + st.params).takeLast(30),
                redoStack = st.redoStack.dropLast(1),
                selectedMaskId = selId,
            )
        }
        schedulePreviewRender(immediate = true)
    }

    fun addMask(type: MaskType) {
        if (!type.isAi) {
            val count = _state.value.params.masks.count { it.type == type } + 1
            val created = LocalMask(
                name = "${type.label} $count",
                type = type,
            )
            updateParams(recordUndo = true, immediate = true) { p ->
                p.copy(masks = p.masks + created)
            }
            _state.update {
                it.copy(
                    selectedMaskId = created.id,
                    maskBrushErase = false,
                    transientMaskOverlay = false,
                )
            }
            scheduleMaskOverlayRender()
            return
        }

        val orig = _state.value.previewOriginal ?: return
        val curParams = _state.value.params
        aiMaskJob?.cancel()
        aiMaskJob = scope.launch {
            _state.update { it.copy(detectingMask = true) }
            try {
                val res = withContext(Dispatchers.Default) {
                    val geo = LrImageEngine.applyGeometry(orig, curParams)
                    try {
                        SmartMaskSegmenter.segment(geo, type)
                    } finally {
                        if (geo !== orig) geo.recycle()
                    }
                }
                if (res == null) {
                    _state.update { it.copy(detectingMask = false) }
                    onToast(
                        when (type) {
                            MaskType.AI_PERSON -> "未检测到明显人物"
                            MaskType.AI_SKY -> "未检测到明显天空区域"
                            else -> "未能识别出有效${type.label}选区"
                        }
                    )
                    return@launch
                }
                val count = _state.value.params.masks.count { it.type == type } + 1
                val created = LocalMask(
                    name = "${type.label} $count",
                    type = type,
                    aiMaskBase64 = res.encodedBase64,
                    aiMaskWidth = res.width,
                    aiMaskHeight = res.height,
                )
                updateParams(recordUndo = true, immediate = true) { p ->
                    p.copy(masks = p.masks + created)
                }
                _state.update {
                    it.copy(
                        selectedMaskId = created.id,
                        maskBrushErase = false,
                        detectingMask = false,
                        transientMaskOverlay = false,
                    )
                }
                scheduleMaskOverlayRender()
                onToast("已智能识别${type.label}（覆盖 ${(res.coverage * 100f).toInt().coerceIn(1, 99)}%）")
            } catch (e: CancellationException) {
                _state.update { it.copy(detectingMask = false) }
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(detectingMask = false) }
                onToast("智能识别失败：${t.message ?: "未知错误"}")
            }
        }
    }

    fun setTransientMaskOverlay(show: Boolean) {
        if (_state.value.transientMaskOverlay == show) return
        _state.update { it.copy(transientMaskOverlay = show) }
        scheduleMaskOverlayRender()
    }

    fun selectMask(id: String?) {
        _state.update { it.copy(selectedMaskId = id) }
        scheduleMaskOverlayRender()
    }

    fun deleteMask(id: String) {
        updateParams(recordUndo = true, immediate = true) { p ->
            p.copy(masks = p.masks.filterNot { it.id == id })
        }
        _state.update { st ->
            val nextId = if (st.selectedMaskId == id) st.params.masks.lastOrNull()?.id else st.selectedMaskId
            st.copy(selectedMaskId = nextId)
        }
        scheduleMaskOverlayRender()
    }

    fun toggleMaskEnabled(id: String) {
        updateParams(recordUndo = true, immediate = true) { p ->
            p.copy(masks = p.masks.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        }
    }

    fun toggleMaskInvert(id: String) {
        updateParams(recordUndo = true, immediate = true) { p ->
            p.copy(masks = p.masks.map { if (it.id == id) it.copy(invert = !it.invert) else it })
        }
        scheduleMaskOverlayRender()
    }

    fun toggleMaskOverlay() {
        _state.update { it.copy(showMaskOverlay = !it.showMaskOverlay) }
        scheduleMaskOverlayRender()
    }

    fun setMaskBrushErase(erase: Boolean) {
        _state.update { it.copy(maskBrushErase = erase) }
    }

    fun updateSelectedMask(
        recordUndo: Boolean = false,
        immediate: Boolean = false,
        transform: (LocalMask) -> LocalMask,
    ) {
        val targetId = _state.value.activeMask?.id ?: return
        updateParams(recordUndo = recordUndo, immediate = immediate) { p ->
            p.copy(masks = p.masks.map { if (it.id == targetId) transform(it) else it })
        }
        scheduleMaskOverlayRender()
    }

    fun appendBrushStroke(points: List<MaskPoint>) {
        if (points.isEmpty()) return
        val st = _state.value
        val target = st.activeMask ?: return
        if (target.type != MaskType.BRUSH) return
        val stroke = MaskStroke(
            points = points,
            radius = target.brushSize,
            hardness = target.brushFeather,
            erase = st.maskBrushErase,
        )
        updateParams(recordUndo = true, immediate = true) { p ->
            p.copy(masks = p.masks.map {
                if (it.id == target.id) it.copy(strokes = it.strokes + stroke) else it
            })
        }
        scheduleMaskOverlayRender()
    }

    fun undoLastBrushStroke() {
        val target = _state.value.activeMask ?: return
        if (target.type != MaskType.BRUSH || target.strokes.isEmpty()) return
        updateParams(recordUndo = true, immediate = true) { p ->
            p.copy(masks = p.masks.map {
                if (it.id == target.id) it.copy(strokes = it.strokes.dropLast(1)) else it
            })
        }
        scheduleMaskOverlayRender()
    }

    fun saveCurrentAsPreset(nameInput: String) {
        val cleanName = nameInput.trim().ifBlank { "我的调色预设" }
        val p = _state.value.params.copy(appliedPresetName = cleanName)
        scope.launch {
            store.saveLrPreset(SavedLrPreset(name = cleanName, params = p))
            _state.update { it.copy(params = p) }
            onToast("已保存个人预设：$cleanName")
        }
    }

    fun deleteSavedPreset(id: String) {
        scope.launch {
            store.deleteLrPreset(id)
            onToast("已删除预设")
        }
    }

    fun exportToPhoneGallery() {
        val st = _state.value
        if (!st.hasPhoto || st.exporting) return
        exportJob?.cancel()
        exportJob = scope.launch {
            _state.update { it.copy(exporting = true, lastExport = null) }
            try {
                val res = withContext(Dispatchers.Default) {
                    val fullSrc = PhotoExporter.loadFullResolution(
                        context = app,
                        uri = st.sourceUri,
                        filePath = st.sourceFilePath,
                        maxExportEdge = 6000,
                    ) ?: st.previewOriginal ?: error("找不到源图")
                    val rendered = LrImageEngine.render(fullSrc, st.params, applyGeometryStep = true)
                    val saved = PhotoExporter.saveBitmapToPhoneGallery(
                        context = app,
                        bitmap = rendered,
                        baseName = st.sourceName,
                        format = st.exportFormat,
                        quality = st.exportFormat.defaultQuality,
                    ).getOrThrow()
                    if (rendered !== fullSrc) rendered.recycle()
                    if (fullSrc !== st.previewOriginal) fullSrc.recycle()
                    saved
                }
                _state.update { it.copy(exporting = false, lastExport = res) }
                onToast("已保存到系统相册：${res.relativePath}/${res.displayName}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(exporting = false) }
                onToast("保存失败：${t.message ?: "未知错误"}")
            }
        }
    }

    private fun schedulePreviewRender(immediate: Boolean) {
        renderJob?.cancel()
        renderJob = scope.launch {
            if (!immediate) delay(45)
            val st = _state.value
            val orig = st.previewOriginal ?: return@launch
            val params = st.params
            _state.update { it.copy(renderingPreview = true) }
            val result = withContext(Dispatchers.Default) {
                val bmp = LrImageEngine.render(orig, params, applyGeometryStep = true)
                val hist = LrImageEngine.computeHistogram(bmp)
                bmp to hist
            }
            _state.update {
                it.copy(
                    previewEdited = result.first,
                    histogram = result.second,
                    renderingPreview = false,
                )
            }
            scheduleMaskOverlayRender()
        }
    }

    private fun scheduleMaskOverlayRender() {
        overlayJob?.cancel()
        val st = _state.value
        val mask = st.activeMask
        val baseBmp = st.previewEdited ?: st.previewOriginal
        if (st.activeTool != EditorToolTab.MASK || !st.shouldShowMaskOverlay || mask == null || baseBmp == null) {
            if (st.maskOverlayBitmap != null) {
                _state.update { it.copy(maskOverlayBitmap = null) }
            }
            return
        }
        overlayJob = scope.launch {
            delay(24)
            val bmp = withContext(Dispatchers.Default) {
                LrImageEngine.buildMaskOverlayBitmap(baseBmp.width, baseBmp.height, mask, baseBmp)
            }
            _state.update { it.copy(maskOverlayBitmap = bmp) }
        }
    }

    private fun downscaleIfNeeded(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return LrImageEngine.ensureSoftwareArgb8888(src)
        val scale = maxEdge.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(LrImageEngine.ensureSoftwareArgb8888(src), nw, nh, true)
    }

    private fun extractJpegBytes(data: ByteArray): ByteArray? {
        for (i in 0 until data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                return if (i == 0) data else data.copyOfRange(i, data.size)
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun queryImageBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()
}
