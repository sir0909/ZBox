package com.zbox.ai.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.PredefinedCategory
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.zbox.ai.core.CompositionBox
import com.zbox.ai.core.LocalCompStyle
import com.zbox.ai.core.LocalCompositionGuide
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 离线实时本地智能构图引擎（零网络请求、零 Token 消耗）：
 * 1) 全场景多路识别：ML Kit 人像分割（人像头部/上三分锚点） + ML Kit 通用物体检测（美食/植物/静物/服饰/建筑）
 *    + Achanta Lab 边界背景抑制显著性 20×14 连通峰聚类（兜底复杂纹理与远景主体）；
 * 2) 地平线与水平倾角检测：提取强连续水平边界位置与旋转角，支持风景地平线三分构图与实时水平仪；
 * 3) 多规则冲突消解（防切头/贴边 > 水平仪 > 地平线三分 > 主体三分点 > 远近留白）。
 */
object LocalCompositionEngine {
    private const val ANALYSIS_MAX_DIM = 192
    private const val PERSON_INTERVAL_MS = 380L
    private const val OBJECT_INTERVAL_MS = 320L

    private val segmenter: Segmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
        Segmentation.getClient(opts)
    }

    private val objectDetector: ObjectDetector by lazy {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(opts)
    }

    private data class DetectedSubject(
        val box: CompositionBox,
        val anchorX: Float,
        val anchorY: Float,
        val hasPerson: Boolean,
        val label: String,
    )

    private var lastPersonCheckMs = 0L
    private var cachedPerson: DetectedSubject? = null
    private var cachedPersonValidUntilMs = 0L

    private var lastObjCheckMs = 0L
    private var cachedObject: DetectedSubject? = null
    private var cachedObjValidUntilMs = 0L

    private var smoothCx = -1f
    private var smoothCy = -1f
    private var smoothL = -1f
    private var smoothT = -1f
    private var smoothR = -1f
    private var smoothB = -1f
    private var smoothTilt = 0f
    private var smoothHorizonY = -1f

    fun resetSmoothing() {
        smoothCx = -1f
        smoothCy = -1f
        smoothL = -1f
        smoothT = -1f
        smoothR = -1f
        smoothB = -1f
        smoothTilt = 0f
        smoothHorizonY = -1f
        cachedPerson = null
        cachedPersonValidUntilMs = 0L
        lastPersonCheckMs = 0L
        cachedObject = null
        cachedObjValidUntilMs = 0L
        lastObjCheckMs = 0L
    }

    fun analyze(frame: Bitmap, style: LocalCompStyle): LocalCompositionGuide {
        val srcW = frame.width.coerceAtLeast(1)
        val srcH = frame.height.coerceAtLeast(1)
        val scale = min(1f, ANALYSIS_MAX_DIM.toFloat() / max(srcW, srcH).toFloat())
        val w = (srcW * scale).roundToInt().coerceIn(32, ANALYSIS_MAX_DIM)
        val h = (srcH * scale).roundToInt().coerceIn(24, ANALYSIS_MAX_DIM)
        val small = if (w == srcW && h == srcH) frame else Bitmap.createScaledBitmap(frame, w, h, true)

        try {
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)

            val now = System.currentTimeMillis()
            if (now - lastPersonCheckMs >= PERSON_INTERVAL_MS) {
                lastPersonCheckMs = now
                detectPersonFast(small)?.let { sub ->
                    cachedPerson = sub
                    cachedPersonValidUntilMs = now + 850L
                } ?: run {
                    cachedPerson = null
                    cachedPersonValidUntilMs = 0L
                }
            }

            val personSub = if (now <= cachedPersonValidUntilMs) cachedPerson else null
            if (personSub == null && now - lastObjCheckMs >= OBJECT_INTERVAL_MS) {
                lastObjCheckMs = now
                detectObjectFast(small)?.let { sub ->
                    cachedObject = sub
                    cachedObjValidUntilMs = now + 750L
                } ?: run {
                    cachedObject = null
                    cachedObjValidUntilMs = 0L
                }
            }
            val objSub = if (now <= cachedObjValidUntilMs) cachedObject else null

            val chosen: DetectedSubject = when {
                personSub != null -> personSub
                objSub != null -> objSub
                else -> detectAchantaClusterSubject(pixels, w, h)
            }

            val (rawHorizonY, rawTilt) = estimateHorizonAndTilt(pixels, w, h)
            if (rawHorizonY != null) {
                smoothHorizonY = if (smoothHorizonY < 0f) rawHorizonY else smoothHorizonY + (rawHorizonY - smoothHorizonY) * 0.35f
            } else if (smoothHorizonY >= 0f) {
                smoothHorizonY = -1f
            }

            // Temporal EMA smoothing so live viewfinder overlays never jitter.
            val alpha = 0.42f
            if (smoothCx < 0f) {
                smoothCx = chosen.anchorX
                smoothCy = chosen.anchorY
                smoothL = chosen.box.left
                smoothT = chosen.box.top
                smoothR = chosen.box.right
                smoothB = chosen.box.bottom
                smoothTilt = rawTilt
            } else {
                smoothCx += (chosen.anchorX - smoothCx) * alpha
                smoothCy += (chosen.anchorY - smoothCy) * alpha
                smoothL += (chosen.box.left - smoothL) * alpha
                smoothT += (chosen.box.top - smoothT) * alpha
                smoothR += (chosen.box.right - smoothR) * alpha
                smoothB += (chosen.box.bottom - smoothB) * alpha
                smoothTilt += (rawTilt - smoothTilt) * 0.30f
            }

            val box = CompositionBox(
                left = smoothL.coerceIn(0.02f, 0.90f),
                top = smoothT.coerceIn(0.02f, 0.90f),
                right = smoothR.coerceIn((smoothL + 0.08f).coerceAtMost(0.98f), 0.98f),
                bottom = smoothB.coerceIn((smoothT + 0.08f).coerceAtMost(0.98f), 0.98f),
            )
            val cx = smoothCx.coerceIn(0.05f, 0.95f)
            val cy = smoothCy.coerceIn(0.05f, 0.95f)
            val area = ((box.right - box.left) * (box.bottom - box.top)).coerceIn(0.01f, 0.95f)
            val hasPerson = chosen.hasPerson
            val horizonY = if (smoothHorizonY in 0.12f..0.88f) smoothHorizonY else null

            val isHorizonMode = (style == LocalCompStyle.LANDSCAPE && horizonY != null) ||
                (style == LocalCompStyle.AUTO && !hasPerson && objSub == null && area > 0.42f && horizonY != null)

            if (isHorizonMode) {
                val hy = horizonY!!
                val targetH = if (hy < 0.50f) 0.333f else 0.667f
                val dyH = targetH - hy
                val distH = abs(dyH)
                val levelScore = (1f - (abs(smoothTilt) / 10f).coerceIn(0f, 1f)) * 45f
                val thirdScore = (1f - (distH / 0.24f).coerceIn(0f, 1f)) * 55f
                val score = (levelScore + thirdScore).roundToInt().coerceIn(38, 99)
                val aligned = distH <= 0.048f && abs(smoothTilt) <= 2.4f
                val moveHint = when {
                    aligned -> "✓ 地平线对齐三分线 · 可按快门"
                    abs(smoothTilt) > 2.8f ->
                        if (smoothTilt > 0f) "水平倾斜 ${"%.1f".format(abs(smoothTilt))}° · 顺时针微调"
                        else "水平倾斜 ${"%.1f".format(abs(smoothTilt))}° · 逆时针微调"
                    abs(hy - 0.50f) < 0.055f ->
                        if (hy <= 0.50f) "地平线居中 · 镜头略下压留出天空" else "地平线居中 · 镜头略上抬留出前景"
                    dyH > 0f -> "镜头略微上抬，压低地平线至下三分"
                    else -> "镜头略微下压，抬高地平线至上三分"
                }
                return LocalCompositionGuide(
                    subjectBox = box,
                    subjectCenterX = 0.5f,
                    subjectCenterY = hy,
                    targetX = 0.5f,
                    targetY = targetH,
                    horizonY = hy,
                    tiltDeg = smoothTilt,
                    score = score,
                    aligned = aligned,
                    hasPerson = false,
                    subjectLabel = "地平线",
                    sceneTag = "地平线三分",
                    moveHint = moveHint,
                    detailHint = "水平线 ${(hy * 100f).roundToInt()}% · 倾角 ${"%.1f".format(abs(smoothTilt))}°",
                )
            }

            val (tx, ty, sceneTag) = chooseTarget(cx, cy, area, hasPerson, style)
            val dx = tx - cx
            val dy = ty - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            val (minIdealArea, maxIdealArea) = when (style) {
                LocalCompStyle.MINIMAL -> 0.06f to 0.30f
                LocalCompStyle.PORTRAIT -> 0.14f to 0.58f
                LocalCompStyle.LANDSCAPE -> 0.10f to 0.52f
                LocalCompStyle.AUTO -> if (hasPerson) (0.12f to 0.56f) else (0.08f to 0.50f)
            }

            val headCut = box.top < 0.028f && hasPerson
            val sideCut = box.left < 0.018f || box.right > 0.982f
            val edgeTouchPenalty = (if (headCut) 11 else 0) + (if (sideCut) 7 else 0)

            val distScore = (1f - (dist / 0.36f).coerceIn(0f, 1f)) * 56f
            val sizeScore = when {
                area in minIdealArea..maxIdealArea -> 26f
                area < minIdealArea -> (area / minIdealArea).coerceIn(0.2f, 1f) * 26f
                else -> (maxIdealArea / area).coerceIn(0.2f, 1f) * 26f
            }
            val levelScore = (1f - (abs(smoothTilt) / 10f).coerceIn(0f, 1f)) * 18f
            val score = (distScore + sizeScore + levelScore - edgeTouchPenalty).roundToInt().coerceIn(35, 99)

            val aligned = dist <= 0.062f &&
                abs(smoothTilt) <= 2.8f &&
                edgeTouchPenalty == 0 &&
                area in (minIdealArea * 0.68f)..(maxIdealArea * 1.18f)

            val moveHint = resolveMoveHint(
                aligned = aligned,
                headCut = headCut,
                sideCut = sideCut,
                box = box,
                tiltDeg = smoothTilt,
                dx = dx,
                dy = dy,
                area = area,
                minIdealArea = minIdealArea,
                maxIdealArea = maxIdealArea,
            )

            val detailHint = buildString {
                append(chosen.label)
                append(" · 占比 ${(area * 100f).roundToInt()}%")
                when {
                    headCut -> append(" · 顶部留白偏少")
                    abs(smoothTilt) > 2.8f -> append(" · 倾角 ${"%.1f".format(abs(smoothTilt))}°")
                    else -> append(" · 水平稳定")
                }
            }

            return LocalCompositionGuide(
                subjectBox = box,
                subjectCenterX = cx,
                subjectCenterY = cy,
                targetX = tx,
                targetY = ty,
                horizonY = horizonY,
                tiltDeg = smoothTilt,
                score = score,
                aligned = aligned,
                hasPerson = hasPerson,
                subjectLabel = chosen.label,
                sceneTag = sceneTag,
                moveHint = moveHint,
                detailHint = detailHint,
            )
        } finally {
            if (small !== frame) small.recycle()
        }
    }

    private fun resolveMoveHint(
        aligned: Boolean,
        headCut: Boolean,
        sideCut: Boolean,
        box: CompositionBox,
        tiltDeg: Float,
        dx: Float,
        dy: Float,
        area: Float,
        minIdealArea: Float,
        maxIdealArea: Float,
    ): String {
        if (aligned) return "✓ 构图到位 · 建议直接按快门"
        if (headCut) return "上方留白过少 · 镜头略微上抬"
        if (sideCut) {
            return if (box.left < 0.018f && box.right > 0.982f) "主体贴边 · 向后退半步"
            else if (box.left < 0.018f) "左侧贴边 · 机位略向左移"
            else "右侧贴边 · 机位略向右移"
        }
        if (abs(tiltDeg) > 3.4f) {
            return if (tiltDeg > 0f) "水平偏斜 ${"%.1f".format(abs(tiltDeg))}° · 顺时针微调"
            else "水平偏斜 ${"%.1f".format(abs(tiltDeg))}° · 逆时针微调"
        }
        val parts = mutableListOf<String>()
        if (abs(dx) > 0.045f) parts += if (dx > 0f) "机位略向左移" else "机位略向右移"
        if (abs(dy) > 0.045f) parts += if (dy > 0f) "镜头略微上抬" else "镜头略微下压"
        if (parts.isEmpty()) {
            if (area < minIdealArea * 0.85f) parts += "靠近半步放大主体"
            else if (area > maxIdealArea * 1.15f) parts += "退后半步留出呼吸感"
        }
        return if (parts.isEmpty()) "微调至黄圈准星后拍摄" else parts.take(2).joinToString(" · ")
    }

    private fun chooseTarget(
        cx: Float,
        cy: Float,
        area: Float,
        hasPerson: Boolean,
        style: LocalCompStyle,
    ): Triple<Float, Float, String> {
        return when (style) {
            LocalCompStyle.PORTRAIT -> Triple(0.50f, if (hasPerson) 0.36f else 0.44f, "居中人像")
            LocalCompStyle.MINIMAL -> {
                val tx = if (cx <= 0.5f) 0.333f else 0.667f
                Triple(tx, if (hasPerson) 0.56f else 0.64f, "极简留白")
            }
            LocalCompStyle.LANDSCAPE -> {
                val tx = if (cx <= 0.5f) 0.333f else 0.667f
                val ty = if (cy <= 0.5f) 0.333f else 0.667f
                Triple(tx, ty, "风景三分")
            }
            LocalCompStyle.AUTO -> {
                if (hasPerson && abs(cx - 0.5f) < 0.11f && area >= 0.20f) {
                    Triple(0.50f, 0.36f, "居中构图")
                } else {
                    val anchors = listOf(
                        0.333f to 0.35f,
                        0.667f to 0.35f,
                        0.333f to 0.64f,
                        0.667f to 0.64f,
                    )
                    val best = anchors.minBy { (ax, ay) ->
                        hypot((ax - cx).toDouble(), (ay - cy).toDouble())
                    }
                    Triple(best.first, best.second, "三分法构图")
                }
            }
        }
    }

    private fun detectPersonFast(bmp: Bitmap): DetectedSubject? {
        return try {
            val mask = Tasks.await(
                segmenter.process(InputImage.fromBitmap(bmp, 0)),
                120,
                TimeUnit.MILLISECONDS,
            )
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            var sumW = 0f
            var sumX = 0f
            var sumY = 0f
            var minX = mw
            var minY = mh
            var maxX = -1
            var maxY = -1
            var fgCount = 0
            for (y in 0 until mh) {
                for (x in 0 until mw) {
                    val p = buf.float
                    if (p > 0.48f) {
                        fgCount++
                        sumW += p
                        sumX += x * p
                        sumY += y * p
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val total = (mw * mh).coerceAtLeast(1)
            val ratio = fgCount.toFloat() / total.toFloat()
            if (ratio < 0.025f || ratio > 0.90f || sumW <= 1f || maxX <= minX || maxY <= minY) {
                null
            } else {
                val box = CompositionBox(
                    left = (minX.toFloat() / mw).coerceIn(0.01f, 0.92f),
                    top = (minY.toFloat() / mh).coerceIn(0.01f, 0.92f),
                    right = ((maxX + 1).toFloat() / mw).coerceIn(0.08f, 0.99f),
                    bottom = ((maxY + 1).toFloat() / mh).coerceIn(0.08f, 0.99f),
                )
                val cx = (sumX / sumW / mw).coerceIn(0.05f, 0.95f)
                val boxH = (box.bottom - box.top).coerceAtLeast(0.08f)
                val eyeCy = (box.top + boxH * 0.28f).coerceIn(0.06f, 0.90f)
                DetectedSubject(box = box, anchorX = cx, anchorY = eyeCy, hasPerson = true, label = "人像")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectObjectFast(bmp: Bitmap): DetectedSubject? {
        return try {
            val objs = Tasks.await(
                objectDetector.process(InputImage.fromBitmap(bmp, 0)),
                110,
                TimeUnit.MILLISECONDS,
            )
            if (objs.isNullOrEmpty()) return null
            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)

            var bestScore = -1f
            var bestSub: DetectedSubject? = null
            for (obj in objs) {
                val r = obj.boundingBox
                val l = (r.left / bw).coerceIn(0.01f, 0.95f)
                val t = (r.top / bh).coerceIn(0.01f, 0.95f)
                val ri = (r.right / bw).coerceIn(0.05f, 0.99f)
                val b = (r.bottom / bh).coerceIn(0.05f, 0.99f)
                val w = ri - l
                val h = b - t
                val area = w * h
                if (w < 0.06f || h < 0.06f || area < 0.015f || area > 0.85f) continue
                val cx = (l + ri) * 0.5f
                val cy = (t + b) * 0.5f
                val centerBonus = 1f - 0.5f * hypot((cx - 0.5f).toDouble(), (cy - 0.5f).toDouble()).toFloat()
                val cat = obj.labels.maxByOrNull { it.confidence }?.text
                val labelZh = mapCategoryZh(cat)
                val catBonus = if (cat != null) 1.22f else 1.0f
                val idealAreaScore = if (area in 0.06f..0.48f) 1.0f else 0.72f
                val s = area * centerBonus * catBonus * idealAreaScore
                if (s > bestScore) {
                    bestScore = s
                    bestSub = DetectedSubject(
                        box = CompositionBox(l, t, ri, b),
                        anchorX = cx,
                        anchorY = cy,
                        hasPerson = false,
                        label = labelZh,
                    )
                }
            }
            bestSub
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapCategoryZh(category: String?): String = when (category) {
        PredefinedCategory.FOOD -> "美食"
        PredefinedCategory.PLANT -> "植物"
        PredefinedCategory.HOME_GOOD -> "静物"
        PredefinedCategory.FASHION_GOOD -> "服饰"
        PredefinedCategory.PLACE -> "建筑/景物"
        else -> "前景物体"
    }

    /**
     * Achanta Frequency-Tuned 显著性 + 边缘背景原型抑制 + 20x14 网格主峰连通域聚类。
     */
    private fun detectAchantaClusterSubject(pixels: IntArray, w: Int, h: Int): DetectedSubject {
        val n = w * h
        val lCh = FloatArray(n)
        val aCh = FloatArray(n)
        val bCh = FloatArray(n)
        var meanL = 0f
        var meanA = 0f
        var meanB = 0f
        var borderL = 0f
        var borderA = 0f
        var borderB = 0f
        var borderCount = 0
        val borderMarginX = max(2, (w * 0.06f).toInt())
        val borderMarginY = max(2, (h * 0.06f).toInt())

        for (y in 0 until h) {
            val row = y * w
            val isBorderY = y < borderMarginY || y >= h - borderMarginY
            for (x in 0 until w) {
                val idx = row + x
                val c = pixels[idx]
                val r = ((c ushr 16) and 0xFF) / 255f
                val g = ((c ushr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val a = (r - g) * 0.866f
                val bb = (0.5f * (r + g) - b) * 0.707f
                lCh[idx] = l
                aCh[idx] = a
                bCh[idx] = bb
                meanL += l
                meanA += a
                meanB += bb
                if (isBorderY || x < borderMarginX || x >= w - borderMarginX) {
                    borderL += l
                    borderA += a
                    borderB += bb
                    borderCount++
                }
            }
        }
        val invN = 1f / n.coerceAtLeast(1)
        meanL *= invN
        meanA *= invN
        meanB *= invN
        val invB = 1f / borderCount.coerceAtLeast(1)
        borderL *= invB
        borderA *= invB
        borderB *= invB

        val gw = 20
        val gh = 14
        val gridSum = FloatArray(gw * gh)
        val gridCnt = IntArray(gw * gh)
        for (y in 1 until h - 1) {
            val gy = ((y * gh) / h).coerceIn(0, gh - 1)
            val row = y * w
            for (x in 1 until w - 1) {
                val gx = ((x * gw) / w).coerceIn(0, gw - 1)
                val idx = row + x
                val bl = (lCh[idx - w - 1] + lCh[idx - w] + lCh[idx - w + 1] +
                    lCh[idx - 1] + lCh[idx] + lCh[idx + 1] +
                    lCh[idx + w - 1] + lCh[idx + w] + lCh[idx + w + 1]) / 9f
                val ba = (aCh[idx - 1] + aCh[idx] + aCh[idx + 1] + aCh[idx - w] + aCh[idx + w]) * 0.2f
                val bb = (bCh[idx - 1] + bCh[idx] + bCh[idx + 1] + bCh[idx - w] + bCh[idx + w]) * 0.2f
                val dGlobal = sqrt((bl - meanL) * (bl - meanL) + (ba - meanA) * (ba - meanA) + (bb - meanB) * (bb - meanB))
                val dBorder = sqrt((bl - borderL) * (bl - borderL) + (ba - borderA) * (ba - borderA) + (bb - borderB) * (bb - borderB))
                val nx = (x.toFloat() / w) - 0.5f
                val ny = (y.toFloat() / h) - 0.5f
                val centerW = 0.62f + 0.38f * (1f - (nx * nx + ny * ny) * 1.4f).coerceIn(0f, 1f)
                val s = (0.40f * dGlobal + 0.60f * dBorder) * centerW
                val gIdx = gy * gw + gx
                gridSum[gIdx] += s
                gridCnt[gIdx]++
            }
        }

        val grid = FloatArray(gw * gh)
        var peakIdx = 0
        var peakVal = 0f
        for (i in 0 until gw * gh) {
            val v = if (gridCnt[i] > 0) gridSum[i] / gridCnt[i] else 0f
            grid[i] = v
            if (v > peakVal) {
                peakVal = v
                peakIdx = i
            }
        }
        if (peakVal < 0.04f) {
            return DetectedSubject(
                box = CompositionBox(0.28f, 0.24f, 0.72f, 0.74f),
                anchorX = 0.5f,
                anchorY = 0.48f,
                hasPerson = false,
                label = "画面主体",
            )
        }

        val thresh = peakVal * 0.46f
        val visited = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        var head = 0
        var tail = 0
        queue[tail++] = peakIdx
        visited[peakIdx] = true

        var sumW = 0f
        var sumX = 0f
        var sumY = 0f
        var minGx = gw
        var maxGx = 0
        var minGy = gh
        var maxGy = 0

        while (head < tail) {
            val cur = queue[head++]
            val cy = cur / gw
            val cx = cur % gw
            val v = grid[cur]
            val wt = v * v
            sumW += wt
            sumX += (cx + 0.5f) / gw * wt
            sumY += (cy + 0.5f) / gh * wt
            if (cx < minGx) minGx = cx
            if (cx > maxGx) maxGx = cx
            if (cy < minGy) minGy = cy
            if (cy > maxGy) maxGy = cy

            val neighbors = intArrayOf(
                if (cx > 0) cur - 1 else -1,
                if (cx + 1 < gw) cur + 1 else -1,
                if (cy > 0) cur - gw else -1,
                if (cy + 1 < gh) cur + gw else -1,
            )
            for (nb in neighbors) {
                if (nb >= 0 && !visited[nb] && grid[nb] >= thresh) {
                    visited[nb] = true
                    queue[tail++] = nb
                }
            }
        }

        val box = CompositionBox(
            left = (minGx.toFloat() / gw).coerceIn(0.03f, 0.88f),
            top = (minGy.toFloat() / gh).coerceIn(0.03f, 0.88f),
            right = ((maxGx + 1).toFloat() / gw).coerceIn(0.12f, 0.97f),
            bottom = ((maxGy + 1).toFloat() / gh).coerceIn(0.12f, 0.97f),
        )
        val cx = if (sumW > 1e-6f) (sumX / sumW).coerceIn(0.08f, 0.92f) else (box.left + box.right) * 0.5f
        val cy = if (sumW > 1e-6f) (sumY / sumW).coerceIn(0.08f, 0.92f) else (box.top + box.bottom) * 0.5f
        return DetectedSubject(box = box, anchorX = cx, anchorY = cy, hasPerson = false, label = "视觉主体")
    }

    /**
     * 同时估算画面水平倾角（度）与强连续水平线（地平线/天际线）归一化 Y 坐标。
     */
    private fun estimateHorizonAndTilt(pixels: IntArray, w: Int, h: Int): Pair<Float?, Float> {
        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = ((c ushr 16) and 0xFF) * 0.30f + ((c ushr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f
        }
        val yStart = (h * 0.16f).toInt().coerceAtLeast(1)
        val yEnd = (h * 0.84f).toInt().coerceAtMost(h - 2)
        var sumRowScore = 0f
        var rowCount = 0
        var bestRow = -1
        var bestRowScore = 0f
        var sumAng = 0f
        var sumW = 0f

        for (y in yStart..yEnd) {
            val row = y * w
            var horizEnergy = 0f
            for (x in 1 until w - 1) {
                val idx = row + x
                val gx = (luma[idx + 1] - luma[idx - 1]) * 0.5f
                val gy = (luma[idx + w] - luma[idx - w]) * 0.5f
                val mag = hypot(gx.toDouble(), gy.toDouble()).toFloat()
                if (abs(gy) > abs(gx) * 2.2f + 4f) {
                    horizEnergy += abs(gy)
                    if (mag > 16f && (y and 1) == 0 && (x and 1) == 0) {
                        val deg = Math.toDegrees(atan2(-gx.toDouble(), abs(gy).toDouble())).toFloat()
                        if (abs(deg) <= 14f) {
                            sumAng += deg * mag
                            sumW += mag
                        }
                    }
                }
            }
            val avgE = horizEnergy / (w - 2).coerceAtLeast(1)
            sumRowScore += avgE
            rowCount++
            if (avgE > bestRowScore) {
                bestRowScore = avgE
                bestRow = y
            }
        }
        val meanRowScore = if (rowCount > 0) sumRowScore / rowCount else 0f
        val horizonY = if (bestRow in yStart..yEnd && bestRowScore > max(8.5f, meanRowScore * 2.15f)) {
            (bestRow.toFloat() / h).coerceIn(0.12f, 0.88f)
        } else {
            null
        }
        val tiltDeg = if (sumW > 10f) (sumAng / sumW).coerceIn(-12f, 12f) else 0f
        return horizonY to tiltDeg
    }
}
