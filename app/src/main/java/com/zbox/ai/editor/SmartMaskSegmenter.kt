package com.zbox.ai.editor

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 离线端侧智能蒙版识别（人物 / 天空 / 主体 / 背景）：
 * - 人物：Google ML Kit 内置人像分割模型（无需 GMS，离线运行）
 * - 天空：顶部连通区域生长 + 地平线梯度屏障 + 人像剔除
 * - 主体：优先使用 ML Kit 人物置信图，无人像时使用边缘对比显著性 + 前后景色彩迭代
 * - 背景：智能主体选区反选
 */
object SmartMaskSegmenter {

    data class SegmentationResult(
        val encodedBase64: String,
        val width: Int,
        val height: Int,
        val coverage: Float,
    )

    private val selfieSegmenter: Segmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(opts)
    }

    suspend fun segment(source: Bitmap, type: MaskType): SegmentationResult? = withContext(Dispatchers.Default) {
        val safe = LrImageEngine.ensureSoftwareArgb8888(source)
        val maxEdge = 256
        val scale = min(1f, maxEdge.toFloat() / max(safe.width, safe.height).coerceAtLeast(1))
        val w = (safe.width * scale).roundToInt().coerceAtLeast(16)
        val h = (safe.height * scale).roundToInt().coerceAtLeast(16)
        val work = if (safe.width == w && safe.height == h) safe else Bitmap.createScaledBitmap(safe, w, h, true)

        try {
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val rawAlpha = when (type) {
                MaskType.AI_PERSON -> {
                    val person = detectPersonMask(work, w, h) ?: return@withContext null
                    val area = countCoverage(person)
                    if (area < 0.008f) return@withContext null
                    smoothEdges(person, pixels, w, h)
                }
                MaskType.AI_SKY -> {
                    val person = detectPersonMask(work, w, h)
                    val sky = detectSkyMask(pixels, w, h, person)
                    if (countCoverage(sky) < 0.01f) return@withContext null
                    smoothEdges(sky, pixels, w, h)
                }
                MaskType.AI_SUBJECT -> {
                    val subj = detectSubjectMask(work, pixels, w, h)
                    if (countCoverage(subj) < 0.01f) return@withContext null
                    smoothEdges(subj, pixels, w, h)
                }
                MaskType.AI_BACKGROUND -> {
                    val subj = detectSubjectMask(work, pixels, w, h)
                    val inv = FloatArray(w * h) { i -> (1f - subj[i]).coerceIn(0f, 1f) }
                    if (countCoverage(inv) < 0.01f) return@withContext null
                    smoothEdges(inv, pixels, w, h)
                }
                else -> return@withContext null
            }
            val cov = countCoverage(rawAlpha)
            val encoded = MaskAlphaCodec.encode(rawAlpha)
            SegmentationResult(
                encodedBase64 = encoded,
                width = w,
                height = h,
                coverage = cov,
            )
        } finally {
            if (work !== safe) work.recycle()
        }
    }

    private suspend fun detectPersonMask(work: Bitmap, w: Int, h: Int): FloatArray? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(work, 0)
            selfieSegmenter.process(image)
                .addOnSuccessListener { mask ->
                    val mw = mask.width
                    val mh = mask.height
                    val buf = mask.buffer
                    buf.rewind()
                    val raw = FloatArray(mw * mh)
                    var idx = 0
                    while (buf.hasRemaining() && idx < raw.size) {
                        raw[idx++] = buf.float.coerceIn(0f, 1f)
                    }
                    val out = if (mw == w && mh == h) raw else bilinearResize(raw, mw, mh, w, h)
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun detectSkyMask(
        pixels: IntArray,
        w: Int,
        h: Int,
        personMask: FloatArray?,
    ): FloatArray {
        val n = w * h
        val rArr = FloatArray(n)
        val gArr = FloatArray(n)
        val bArr = FloatArray(n)
        val luma = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            rArr[i] = r; gArr[i] = g; bArr[i] = b
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        val grad = FloatArray(n)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val gx = abs(luma[row + x + 1] - luma[row + x - 1])
                val gy = abs(luma[(y + 1) * w + x] - luma[(y - 1) * w + x])
                grad[row + x] = gx + gy
            }
        }

        val skyScore = FloatArray(n)
        for (y in 0 until h) {
            val yNorm = y.toFloat() / h.coerceAtLeast(1)
            val spatialPrior = when {
                yNorm <= 0.50f -> 1f
                yNorm <= 0.85f -> 1f - (yNorm - 0.50f) / 0.35f * 0.85f
                else -> 0.05f
            }
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val pVal = personMask?.get(i) ?: 0f
                if (pVal > 0.45f) {
                    skyScore[i] = 0f
                    continue
                }
                val r = rArr[i]; val g = gArr[i]; val b = bArr[i]
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC > 0.001f) (maxC - minC) / maxC else 0f
                val blueDominant = (b - r) >= 0.015f && (b - g) >= -0.06f
                val overcastBright = luma[i] >= 0.50f && sat <= 0.36f
                val sunsetWarm = yNorm < 0.45f && r >= b && luma[i] >= 0.38f && sat <= 0.68f
                val colorCue = when {
                    blueDominant -> 0.78f + min(0.22f, (b - r) * 1.4f)
                    overcastBright -> 0.72f
                    sunsetWarm -> 0.62f
                    else -> 0.12f
                }
                val texPenalty = (1f - (grad[i] / 0.16f)).coerceIn(0f, 1f)
                skyScore[i] = (colorCue * spatialPrior * texPenalty * (1f - pVal)).coerceIn(0f, 1f)
            }
        }

        // 从顶部边缘向下做连通区域生长，确保只选中连通天空而非地面高光反光
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        var seedSumR = 0f
        var seedSumG = 0f
        var seedSumB = 0f
        var seedCount = 0

        val seedRows = min(3, h)
        for (y in 0 until seedRows) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (skyScore[idx] >= 0.38f && grad[idx] < 0.14f) {
                    visited[idx] = true
                    queue[tail++] = idx
                    seedSumR += rArr[idx]
                    seedSumG += gArr[idx]
                    seedSumB += bArr[idx]
                    seedCount++
                }
            }
        }
        if (seedCount == 0) return FloatArray(n)
        val meanR = seedSumR / seedCount
        val meanG = seedSumG / seedCount
        val meanB = seedSumB / seedCount

        val out = FloatArray(n)
        while (head < tail) {
            val cur = queue[head++]
            out[cur] = skyScore[cur].coerceIn(0.45f, 1f)
            val cx = cur % w
            val cy = cur / w
            val neighbors = intArrayOf(
                if (cx > 0) cur - 1 else -1,
                if (cx + 1 < w) cur + 1 else -1,
                if (cy > 0) cur - w else -1,
                if (cy + 1 < h) cur + w else -1,
            )
            for (nb in neighbors) {
                if (nb < 0 || visited[nb]) continue
                if (skyScore[nb] < 0.28f || grad[nb] >= 0.15f) continue
                val stepDiff = abs(rArr[nb] - rArr[cur]) + abs(gArr[nb] - gArr[cur]) + abs(bArr[nb] - bArr[cur])
                val seedDiff = abs(rArr[nb] - meanR) + abs(gArr[nb] - meanG) + abs(bArr[nb] - meanB)
                if (stepDiff < 0.24f && seedDiff < 0.56f) {
                    visited[nb] = true
                    queue[tail++] = nb
                }
            }
        }
        for (i in 0 until n) {
            val pVal = personMask?.get(i) ?: 0f
            out[i] = (out[i] * (1f - pVal)).coerceIn(0f, 1f)
        }
        return out
    }

    private suspend fun detectSubjectMask(work: Bitmap, pixels: IntArray, w: Int, h: Int): FloatArray {
        val person = detectPersonMask(work, w, h)
        if (person != null && countCoverage(person) >= 0.015f) {
            return person
        }
        // 无人像时使用边缘色彩对比显著性 + 中心空间先验 + 前后景二次精修
        val n = w * h
        val rArr = FloatArray(n)
        val gArr = FloatArray(n)
        val bArr = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            rArr[i] = ((c ushr 16) and 0xFF) / 255f
            gArr[i] = ((c ushr 8) and 0xFF) / 255f
            bArr[i] = (c and 0xFF) / 255f
        }

        val borderR = mutableListOf<Float>()
        val borderG = mutableListOf<Float>()
        val borderB = mutableListOf<Float>()
        val bx = max(2, (w * 0.06f).toInt())
        val by = max(2, (h * 0.06f).toInt())
        val step = max(1, (w + h) / 60)
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (x < bx || x >= w - bx || y < by || y >= h - by) {
                    val i = y * w + x
                    borderR.add(rArr[i]); borderG.add(gArr[i]); borderB.add(bArr[i])
                }
            }
        }
        val protoCount = borderR.size
        val sal = FloatArray(n)
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h - 0.52f
            val row = y * w
            for (x in 0 until w) {
                val nx = (x + 0.5f) / w - 0.5f
                val i = row + x
                val r = rArr[i]; val g = gArr[i]; val b = bArr[i]
                var minDist = 1f
                for (p in 0 until protoCount) {
                    val dr = r - borderR[p]
                    val dg = g - borderG[p]
                    val db = b - borderB[p]
                    val d = sqrt(dr * dr + dg * dg + db * db)
                    if (d < minDist) minDist = d
                }
                val centerPrior = exp(-(nx * nx / 0.18f + ny * ny / 0.22f))
                val raw = (minDist * 2.35f) * (0.35f + 0.65f * centerPrior)
                sal[i] = smooth01(raw)
            }
        }

        // 迭代估计前景/背景均色进行二次软精修
        var fgR = 0f; var fgG = 0f; var fgB = 0f; var fgW = 0.001f
        var bgR = 0f; var bgG = 0f; var bgB = 0f; var bgW = 0.001f
        for (i in 0 until n) {
            val wFg = sal[i] * sal[i]
            val wBg = (1f - sal[i]) * (1f - sal[i])
            fgR += rArr[i] * wFg; fgG += gArr[i] * wFg; fgB += bArr[i] * wFg; fgW += wFg
            bgR += rArr[i] * wBg; bgG += gArr[i] * wBg; bgB += bArr[i] * wBg; bgW += wBg
        }
        fgR /= fgW; fgG /= fgW; fgB /= fgW
        bgR /= bgW; bgG /= bgW; bgB /= bgW

        val refined = FloatArray(n)
        for (i in 0 until n) {
            val dFg = sqrt((rArr[i] - fgR) * (rArr[i] - fgR) + (gArr[i] - fgG) * (gArr[i] - fgG) + (bArr[i] - fgB) * (bArr[i] - fgB))
            val dBg = sqrt((rArr[i] - bgR) * (rArr[i] - bgR) + (gArr[i] - bgG) * (gArr[i] - bgG) + (bArr[i] - bgB) * (bArr[i] - bgB))
            val ratio = (dBg / (dFg + dBg + 1e-4f)).coerceIn(0f, 1f)
            val combined = sal[i] * 0.6f + smooth01((ratio - 0.35f) * 2.2f) * 0.4f
            refined[i] = smooth01((combined - 0.25f) * 1.45f)
        }
        return refined
    }

    private fun smoothEdges(alpha: FloatArray, pixels: IntArray, w: Int, h: Int): FloatArray {
        val out = alpha.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = pixels[row + x]
                val rc = (c ushr 16) and 0xFF
                val gc = (c ushr 8) and 0xFF
                val bc = c and 0xFF
                var sum = 0f
                var wSum = 0f
                for (dy in -1..1) {
                    val nRow = (y + dy) * w
                    for (dx in -1..1) {
                        val ni = nRow + x + dx
                        val nc = pixels[ni]
                        val diff = abs(((nc ushr 16) and 0xFF) - rc) +
                            abs(((nc ushr 8) and 0xFF) - gc) +
                            abs((nc and 0xFF) - bc)
                        val weight = exp(-(diff * diff) / 1800f)
                        sum += alpha[ni] * weight
                        wSum += weight
                    }
                }
                out[row + x] = (sum / wSum.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun bilinearResize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) / dh) * sh - 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val r0 = y0 * sw
            val r1 = y1 * sw
            val rowOut = y * dw
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) / dw) * sw - 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val wx = (fx - x0).coerceIn(0f, 1f)
                val top = src[r0 + x0] + (src[r0 + x1] - src[r0 + x0]) * wx
                val bot = src[r1 + x0] + (src[r1 + x1] - src[r1 + x0]) * wx
                out[rowOut + x] = (top + (bot - top) * wy).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun countCoverage(alpha: FloatArray): Float {
        if (alpha.isEmpty()) return 0f
        var hit = 0
        for (v in alpha) if (v > 0.35f) hit++
        return hit.toFloat() / alpha.size
    }

    private fun smooth01(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}
