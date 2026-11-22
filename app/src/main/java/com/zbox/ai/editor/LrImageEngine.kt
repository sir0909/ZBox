package com.zbox.ai.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import com.zbox.ai.core.CubicSplineLut
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HistogramData(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val maxCount: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = maxCount
}

/**
 * Lightroom 风格图像调色引擎（CPU ARGB_8888 浮点像素管线）。
 * 兼顾预览响应速度与全分辨率高清导出画质。
 */
object LrImageEngine {

    fun ensureSoftwareArgb8888(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ARGB_8888 && !isHardware(src)) return src
        return src.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun isHardware(bmp: Bitmap): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && bmp.config == Bitmap.Config.HARDWARE

    /** 先应用旋转、镜像与归一化裁剪区域。 */
    fun applyGeometry(src: Bitmap, params: LrEditParams): Bitmap {
        val safe = ensureSoftwareArgb8888(src)
        val p = params.clamped()
        if (!p.hasGeometryChange) return safe

        val rot = ((p.rotation90 % 4) + 4) % 4
        var oriented = safe
        if (rot != 0 || p.flipHorizontal) {
            val m = Matrix()
            if (p.flipHorizontal) m.postScale(-1f, 1f)
            if (rot != 0) m.postRotate((rot * 90).toFloat())
            oriented = Bitmap.createBitmap(safe, 0, 0, safe.width, safe.height, m, true)
        }

        val w = oriented.width
        val h = oriented.height
        val x0 = (p.cropLeft * w).roundToInt().coerceIn(0, (w - 8).coerceAtLeast(0))
        val y0 = (p.cropTop * h).roundToInt().coerceIn(0, (h - 8).coerceAtLeast(0))
        val x1 = (p.cropRight * w).roundToInt().coerceIn(x0 + 1, w)
        val y1 = (p.cropBottom * h).roundToInt().coerceIn(y0 + 1, h)
        val cw = (x1 - x0).coerceAtLeast(1)
        val ch = (y1 - y0).coerceAtLeast(1)
        if (x0 == 0 && y0 == 0 && cw == w && ch == h) return oriented
        val cropped = Bitmap.createBitmap(oriented, x0, y0, cw, ch)
        if (oriented !== safe && oriented !== cropped) oriented.recycle()
        return cropped
    }

    fun render(
        src: Bitmap,
        rawParams: LrEditParams,
        applyGeometryStep: Boolean = true,
    ): Bitmap {
        val p = rawParams.clamped()
        val geo = if (applyGeometryStep) applyGeometry(src, p) else ensureSoftwareArgb8888(src)
        val w = geo.width
        val h = geo.height
        if (w <= 0 || h <= 0) return geo

        if (p.isIdentity && !p.hasGeometryChange) {
            return geo.copy(Bitmap.Config.ARGB_8888, false)
        }

        val n = w * h
        val pixels = IntArray(n)
        geo.getPixels(pixels, 0, w, 0, 0, w, h)

        val expMul = if (p.exposure != 0f) 2f.pow(p.exposure) else 1f
        val tempNorm = p.temperature / 100f
        val tintNorm = p.tint / 100f
        val wbR = (1f + tempNorm * 0.24f + tintNorm * 0.08f).coerceIn(0.6f, 1.5f)
        val wbG = (1f - tintNorm * 0.20f).coerceIn(0.6f, 1.5f)
        val wbB = (1f - tempNorm * 0.24f + tintNorm * 0.08f).coerceIn(0.6f, 1.5f)

        val conFactor = 1f + (p.contrast / 100f) * 0.65f
        val hiGain = (p.highlights / 100f) * 0.48f
        val shGain = (p.shadows / 100f) * 0.52f
        val whGain = (p.whites / 100f) * 0.32f
        val blGain = (p.blacks / 100f) * 0.28f

        val vibNorm = p.vibrance / 100f
        val satFactor = (1f + p.saturation / 100f).coerceIn(0f, 2.2f)

        val useCurve = p.lutStrength > 0.001f && !LrEditParams.isDefaultCurve(p.curvePoints)
        val lut = if (useCurve) CubicSplineLut.buildLut(p.curvePoints) else null
        val lutBlend = p.lutStrength.coerceIn(0f, 1f)

        val activeHsl = HslBand.entries.mapNotNull { band ->
            val adj = p.hslOf(band)
            if (adj.isNeutral) null else band to adj
        }
        val hasHsl = activeHsl.isNotEmpty()
        val hasVignette = abs(p.vignette) > 0.5f
        val vigStrength = p.vignette / 100f
        val hasGrain = p.grain > 0.5f
        val grainAmp = (p.grain / 100f) * 0.09f

        val invW = if (w > 1) 2f / (w - 1) else 0f
        val invH = if (h > 1) 2f / (h - 1) else 0f

        for (i in 0 until n) {
            val c = pixels[i]
            var r = ((c ushr 16) and 0xFF) / 255f
            var g = ((c ushr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            if (tempNorm != 0f || tintNorm != 0f) {
                r *= wbR
                g *= wbG
                b *= wbB
            }

            if (expMul != 1f) {
                r = softExposure(r * expMul)
                g = softExposure(g * expMul)
                b = softExposure(b * expMul)
            }

            if (whGain != 0f || blGain != 0f || hiGain != 0f || shGain != 0f) {
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                val shMask = (1f - luma) * (1f - luma)
                val hiMask = luma * luma
                val blMask = (1f - luma * 2.2f).coerceIn(0f, 1f)
                val whMask = ((luma - 0.55f) / 0.45f).coerceIn(0f, 1f)
                val toneDelta = shGain * shMask + hiGain * hiMask + blGain * blMask + whGain * whMask
                if (luma > 0.0001f) {
                    val targetLuma = (luma + toneDelta).coerceIn(0f, 1.15f)
                    val ratio = targetLuma / luma
                    r *= ratio
                    g *= ratio
                    b *= ratio
                } else if (toneDelta > 0f) {
                    r += toneDelta
                    g += toneDelta
                    b += toneDelta
                }
            }

            if (conFactor != 1f) {
                r = ((r - 0.5f) * conFactor + 0.5f).coerceIn(0f, 1f)
                g = ((g - 0.5f) * conFactor + 0.5f).coerceIn(0f, 1f)
                b = ((b - 0.5f) * conFactor + 0.5f).coerceIn(0f, 1f)
            } else {
                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)
            }

            if (lut != null) {
                val lr = sampleLut(lut, r)
                val lg = sampleLut(lut, g)
                val lb = sampleLut(lut, b)
                r = r + (lr - r) * lutBlend
                g = g + (lg - g) * lutBlend
                b = b + (lb - b) * lutBlend
            }

            if (vibNorm != 0f) {
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val curSat = if (maxC > 0.0001f) (maxC - minC) / maxC else 0f
                val skinProtect = if (r > g && g > b) 0.65f else 1f
                val vFactor = (1f + vibNorm * (1f - curSat) * 1.15f * skinProtect).coerceIn(0.1f, 2.2f)
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = (l + (r - l) * vFactor).coerceIn(0f, 1f)
                g = (l + (g - l) * vFactor).coerceIn(0f, 1f)
                b = (l + (b - l) * vFactor).coerceIn(0f, 1f)
            }

            if (satFactor != 1f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = (l + (r - l) * satFactor).coerceIn(0f, 1f)
                g = (l + (g - l) * satFactor).coerceIn(0f, 1f)
                b = (l + (b - l) * satFactor).coerceIn(0f, 1f)
            }

            if (hasHsl) {
                val rgb = applyHsl(r, g, b, activeHsl)
                r = rgb[0]; g = rgb[1]; b = rgb[2]
            }

            if (hasVignette) {
                val px = i % w
                val py = i / w
                val nx = px * invW - 1f
                val ny = py * invH - 1f
                val dist2 = (nx * nx + ny * ny) * 0.5f
                val vig = (1f + vigStrength * dist2 * 0.85f).coerceIn(0.15f, 1.65f)
                r = (r * vig).coerceIn(0f, 1f)
                g = (g * vig).coerceIn(0f, 1f)
                b = (b * vig).coerceIn(0f, 1f)
            }

            if (hasGrain) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val mid = 4f * l * (1f - l)
                val noise = hashNoise(i) * grainAmp * (0.35f + 0.65f * mid)
                r = (r + noise).coerceIn(0f, 1f)
                g = (g + noise).coerceIn(0f, 1f)
                b = (b + noise).coerceIn(0f, 1f)
            }

            pixels[i] = (0xFF shl 24) or
                ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                (b * 255f).roundToInt().coerceIn(0, 255)
        }

        if ((abs(p.clarity) > 0.5f || p.sharpening > 0.5f) && w >= 5 && h >= 5) {
            applyClarityAndSharpen(pixels, w, h, p.clarity, p.sharpening)
        }

        if (p.masks.any { it.enabled && it.hasAdjustment }) {
            applyMasks(pixels, w, h, p.masks)
        }

        val out = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (geo !== src) geo.recycle()
        return out
    }

    fun buildMaskOverlayBitmap(
        targetW: Int,
        targetH: Int,
        mask: LocalMask,
        referenceBitmap: Bitmap?,
    ): Bitmap {
        val maxEdge = 360
        val scale = min(1f, maxEdge.toFloat() / max(targetW, targetH).coerceAtLeast(1))
        val ow = (targetW * scale).roundToInt().coerceAtLeast(1)
        val oh = (targetH * scale).roundToInt().coerceAtLeast(1)
        val refPixels = if (mask.type == MaskType.LUMINANCE && referenceBitmap != null) {
            val safe = ensureSoftwareArgb8888(referenceBitmap)
            val scaled = if (safe.width == ow && safe.height == oh) safe else Bitmap.createScaledBitmap(safe, ow, oh, true)
            IntArray(ow * oh).also {
                scaled.getPixels(it, 0, ow, 0, 0, ow, oh)
                if (scaled !== safe) scaled.recycle()
            }
        } else null
        val alpha = computeMaskAlpha(mask, refPixels, ow, oh)
        val overlay = IntArray(ow * oh)
        for (i in overlay.indices) {
            val a = (alpha[i] * 145f).roundToInt().coerceIn(0, 175)
            overlay[i] = if (a > 0) (a shl 24) or 0xFF384E else 0
        }
        return Bitmap.createBitmap(overlay, ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun applyMasks(
        pixels: IntArray,
        w: Int,
        h: Int,
        masks: List<LocalMask>,
    ) {
        val n = w * h
        for (rawMask in masks) {
            val mask = rawMask.clamped()
            if (!mask.enabled || !mask.hasAdjustment) continue
            val alpha = computeMaskAlpha(mask, pixels, w, h)

            val expMul = if (mask.exposure != 0f) 2f.pow(mask.exposure) else 1f
            val tempNorm = mask.temperature / 100f
            val tintNorm = mask.tint / 100f
            val wbR = (1f + tempNorm * 0.24f + tintNorm * 0.08f).coerceIn(0.6f, 1.5f)
            val wbG = (1f - tintNorm * 0.20f).coerceIn(0.6f, 1.5f)
            val wbB = (1f - tempNorm * 0.24f + tintNorm * 0.08f).coerceIn(0.6f, 1.5f)
            val conFactor = 1f + (mask.contrast / 100f) * 0.65f
            val hiGain = (mask.highlights / 100f) * 0.48f
            val shGain = (mask.shadows / 100f) * 0.52f
            val satFactor = (1f + mask.saturation / 100f).coerceIn(0f, 2.2f)
            val hasToneOrColor = expMul != 1f || tempNorm != 0f || tintNorm != 0f ||
                conFactor != 1f || hiGain != 0f || shGain != 0f || satFactor != 1f

            if (hasToneOrColor) {
                for (i in 0 until n) {
                    val m = alpha[i]
                    if (m <= 0.004f) continue
                    val c = pixels[i]
                    val r0 = ((c ushr 16) and 0xFF) / 255f
                    val g0 = ((c ushr 8) and 0xFF) / 255f
                    val b0 = (c and 0xFF) / 255f
                    var r = r0
                    var g = g0
                    var b = b0

                    if (tempNorm != 0f || tintNorm != 0f) {
                        r *= wbR; g *= wbG; b *= wbB
                    }
                    if (expMul != 1f) {
                        r = softExposure(r * expMul)
                        g = softExposure(g * expMul)
                        b = softExposure(b * expMul)
                    }
                    if (hiGain != 0f || shGain != 0f) {
                        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                        val shMask = (1f - luma) * (1f - luma)
                        val hiMask = luma * luma
                        val delta = shGain * shMask * 0.65f + hiGain * hiMask * 0.65f
                        r += delta; g += delta; b += delta
                    }
                    if (conFactor != 1f) {
                        r = (r - 0.5f) * conFactor + 0.5f
                        g = (g - 0.5f) * conFactor + 0.5f
                        b = (b - 0.5f) * conFactor + 0.5f
                    }
                    r = r.coerceIn(0f, 1f)
                    g = g.coerceIn(0f, 1f)
                    b = b.coerceIn(0f, 1f)
                    if (satFactor != 1f) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        r = (luma + (r - luma) * satFactor).coerceIn(0f, 1f)
                        g = (luma + (g - luma) * satFactor).coerceIn(0f, 1f)
                        b = (luma + (b - luma) * satFactor).coerceIn(0f, 1f)
                    }

                    val rf = r0 + (r - r0) * m
                    val gf = g0 + (g - g0) * m
                    val bf = b0 + (b - b0) * m
                    pixels[i] = (0xFF shl 24) or
                        ((rf * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                        ((gf * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                        (bf * 255f).roundToInt().coerceIn(0, 255)
                }
            }

            if ((abs(mask.clarity) > 0.5f || mask.sharpening > 0.5f) && w >= 5 && h >= 5) {
                applyClarityAndSharpen(pixels, w, h, mask.clarity, mask.sharpening, alpha)
            }
        }
    }

    private fun computeMaskAlpha(
        mask: LocalMask,
        pixels: IntArray?,
        w: Int,
        h: Int,
    ): FloatArray {
        val n = w * h
        val out = FloatArray(n)
        when (mask.type) {
            MaskType.RADIAL -> {
                val invRx = 1f / mask.radiusX.coerceAtLeast(0.02f)
                val invRy = 1f / mask.radiusY.coerceAtLeast(0.02f)
                val inner = (1f - mask.feather).coerceIn(0f, 0.99f)
                val span = (1f - inner).coerceAtLeast(0.01f)
                for (y in 0 until h) {
                    val ny = (y + 0.5f) / h
                    val dy = (ny - mask.centerY) * invRy
                    val dy2 = dy * dy
                    val row = y * w
                    for (x in 0 until w) {
                        val nx = (x + 0.5f) / w
                        val dx = (nx - mask.centerX) * invRx
                        val d = sqrt(dx * dx + dy2)
                        out[row + x] = when {
                            d <= inner -> 1f
                            d >= 1f -> 0f
                            else -> smooth01(1f - (d - inner) / span)
                        }
                    }
                }
            }
            MaskType.LINEAR -> {
                val vx = mask.endX - mask.startX
                val vy = mask.endY - mask.startY
                val lenSq = vx * vx + vy * vy
                if (lenSq < 1e-6f) {
                    out.fill(1f)
                } else {
                    val invLenSq = 1f / lenSq
                    for (y in 0 until h) {
                        val ny = (y + 0.5f) / h
                        val yTerm = (ny - mask.startY) * vy
                        val row = y * w
                        for (x in 0 until w) {
                            val nx = (x + 0.5f) / w
                            val t = ((nx - mask.startX) * vx + yTerm) * invLenSq
                            out[row + x] = when {
                                t <= 0f -> 1f
                                t >= 1f -> 0f
                                else -> smooth01(1f - t)
                            }
                        }
                    }
                }
            }
            MaskType.BRUSH -> {
                if (mask.strokes.isNotEmpty()) {
                    val maxDim = 320
                    val sScale = min(1f, maxDim.toFloat() / max(w, h).coerceAtLeast(1))
                    val mw = (w * sScale).roundToInt().coerceAtLeast(1)
                    val mh = (h * sScale).roundToInt().coerceAtLeast(1)
                    val buf = FloatArray(mw * mh)
                    val refEdge = max(mw, mh).toFloat()
                    for (stroke in mask.strokes) {
                        if (stroke.points.isEmpty()) continue
                        val rPx = (stroke.radius * refEdge).coerceAtLeast(2f)
                        val inner = (1f - stroke.hardness).coerceIn(0f, 0.98f)
                        val span = (1f - inner).coerceAtLeast(0.02f)
                        val stepPx = max(1f, rPx * 0.25f)
                        fun stamp(cx: Float, cy: Float) {
                            val x0 = (cx - rPx).toInt().coerceIn(0, mw - 1)
                            val x1 = (cx + rPx).toInt().coerceIn(0, mw - 1)
                            val y0 = (cy - rPx).toInt().coerceIn(0, mh - 1)
                            val y1 = (cy + rPx).toInt().coerceIn(0, mh - 1)
                            val invR = 1f / rPx
                            for (sy in y0..y1) {
                                val dy = (sy + 0.5f - cy) * invR
                                val dy2 = dy * dy
                                val row = sy * mw
                                for (sx in x0..x1) {
                                    val dx = (sx + 0.5f - cx) * invR
                                    val d = sqrt(dx * dx + dy2)
                                    if (d < 1f) {
                                        val v = if (d <= inner) 1f else smooth01(1f - (d - inner) / span)
                                        val idx = row + sx
                                        buf[idx] = if (stroke.erase) {
                                            (buf[idx] * (1f - v)).coerceAtLeast(0f)
                                        } else {
                                            max(buf[idx], v)
                                        }
                                    }
                                }
                            }
                        }
                        val pts = stroke.points
                        stamp(pts[0].x * mw, pts[0].y * mh)
                        for (k in 1 until pts.size) {
                            val xA = pts[k - 1].x * mw
                            val yA = pts[k - 1].y * mh
                            val xB = pts[k].x * mw
                            val yB = pts[k].y * mh
                            val dist = sqrt((xB - xA) * (xB - xA) + (yB - yA) * (yB - yA))
                            val steps = max(1, (dist / stepPx).toInt())
                            for (s in 1..steps) {
                                val f = s.toFloat() / steps
                                stamp(xA + (xB - xA) * f, yA + (yB - yA) * f)
                            }
                        }
                    }
                    if (mw == w && mh == h) {
                        buf.copyInto(out)
                    } else {
                        for (y in 0 until h) {
                            val fy = ((y + 0.5f) / h) * mh - 0.5f
                            val y0 = fy.toInt().coerceIn(0, mh - 1)
                            val y1 = (y0 + 1).coerceAtMost(mh - 1)
                            val wy = (fy - y0).coerceIn(0f, 1f)
                            val rowOut = y * w
                            val r0 = y0 * mw
                            val r1 = y1 * mw
                            for (x in 0 until w) {
                                val fx = ((x + 0.5f) / w) * mw - 0.5f
                                val x0 = fx.toInt().coerceIn(0, mw - 1)
                                val x1 = (x0 + 1).coerceAtMost(mw - 1)
                                val wx = (fx - x0).coerceIn(0f, 1f)
                                val v00 = buf[r0 + x0]
                                val v10 = buf[r0 + x1]
                                val v01 = buf[r1 + x0]
                                val v11 = buf[r1 + x1]
                                val top = v00 + (v10 - v00) * wx
                                val bot = v01 + (v11 - v01) * wx
                                out[rowOut + x] = top + (bot - top) * wy
                            }
                        }
                    }
                }
            }
            MaskType.LUMINANCE -> {
                if (pixels != null && pixels.size >= n) {
                    val f = mask.lumaFeather.coerceAtLeast(0.01f)
                    val noLow = mask.lumaMin <= 0.005f
                    val noHigh = mask.lumaMax >= 0.995f
                    for (i in 0 until n) {
                        val c = pixels[i]
                        val luma = (0.2126f * ((c ushr 16) and 0xFF) +
                            0.7152f * ((c ushr 8) and 0xFF) +
                            0.0722f * (c and 0xFF)) / 255f
                        val low = if (noLow) 1f else ((luma - (mask.lumaMin - f)) / f).coerceIn(0f, 1f)
                        val high = if (noHigh) 1f else (((mask.lumaMax + f) - luma) / f).coerceIn(0f, 1f)
                        out[i] = smooth01(min(low, high))
                    }
                }
            }
            MaskType.AI_PERSON,
            MaskType.AI_SKY,
            MaskType.AI_SUBJECT,
            MaskType.AI_BACKGROUND -> {
                val mw = mask.aiMaskWidth
                val mh = mask.aiMaskHeight
                val decoded = MaskAlphaCodec.decode(mask.aiMaskBase64, mw * mh)
                if (decoded != null && mw > 0 && mh > 0) {
                    if (mw == w && mh == h) {
                        decoded.copyInto(out)
                    } else {
                        for (y in 0 until h) {
                            val fy = ((y + 0.5f) / h) * mh - 0.5f
                            val y0 = fy.toInt().coerceIn(0, mh - 1)
                            val y1 = (y0 + 1).coerceAtMost(mh - 1)
                            val wy = (fy - y0).coerceIn(0f, 1f)
                            val r0 = y0 * mw
                            val r1 = y1 * mw
                            val rowOut = y * w
                            for (x in 0 until w) {
                                val fx = ((x + 0.5f) / w) * mw - 0.5f
                                val x0 = fx.toInt().coerceIn(0, mw - 1)
                                val x1 = (x0 + 1).coerceAtMost(mw - 1)
                                val wx = (fx - x0).coerceIn(0f, 1f)
                                val top = decoded[r0 + x0] + (decoded[r0 + x1] - decoded[r0 + x0]) * wx
                                val bot = decoded[r1 + x0] + (decoded[r1 + x1] - decoded[r1 + x0]) * wx
                                out[rowOut + x] = (top + (bot - top) * wy).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            }
        }
        val op = mask.opacity.coerceIn(0f, 1f)
        if (mask.invert) {
            for (i in 0 until n) out[i] = (1f - out[i]) * op
        } else if (op < 0.999f) {
            for (i in 0 until n) out[i] *= op
        }
        return out
    }

    private fun smooth01(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun softExposure(v: Float): Float {
        if (v <= 0.82f) return v.coerceAtLeast(0f)
        val excess = v - 0.82f
        return (0.82f + 0.18f * (excess / (excess + 0.22f))).coerceIn(0f, 1f)
    }

    private fun sampleLut(lut: IntArray, v: Float): Float {
        val pos = (v.coerceIn(0f, 1f)) * 255f
        val idx0 = pos.toInt().coerceIn(0, 255)
        val idx1 = (idx0 + 1).coerceAtMost(255)
        val frac = pos - idx0
        return (lut[idx0] * (1f - frac) + lut[idx1] * frac) / 255f
    }

    private fun hashNoise(i: Int): Float {
        var x = i * 747796405 + 2891336453.toInt()
        x = ((x ushr ((x ushr 28) + 4)) xor x) * 277803737
        x = (x ushr 22) xor x
        return ((x and 0xFFFF) / 32767.5f) - 1f
    }

    private fun applyHsl(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        bands: List<Pair<HslBand, HslAdjust>>,
    ): FloatArray {
        val maxC = max(rIn, max(gIn, bIn))
        val minC = min(rIn, min(gIn, bIn))
        val d = maxC - minC
        var l = (maxC + minC) * 0.5f
        if (d < 0.002f) return floatArrayOf(rIn, gIn, bIn)

        var s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        var hDeg = when (maxC) {
            rIn -> ((gIn - bIn) / d + (if (gIn < bIn) 6f else 0f)) * 60f
            gIn -> ((bIn - rIn) / d + 2f) * 60f
            else -> ((rIn - gIn) / d + 4f) * 60f
        }

        var dh = 0f
        var ds = 0f
        var dl = 0f
        val chromaWeight = (s * 2.5f).coerceIn(0f, 1f)
        for ((band, adj) in bands) {
            var diff = abs(hDeg - band.centerDeg)
            if (diff > 180f) diff = 360f - diff
            val radius = 45f
            if (diff < radius) {
                val w = 0.5f * (1f + cos(Math.PI * diff / radius).toFloat())
                dh += (adj.hue / 100f) * 28f * w
                ds += (adj.sat / 100f) * w
                dl += (adj.lum / 100f) * 0.28f * w
            }
        }
        if (dh == 0f && ds == 0f && dl == 0f) return floatArrayOf(rIn, gIn, bIn)

        hDeg = ((hDeg + dh) % 360f + 360f) % 360f
        s = (s * (1f + ds)).coerceIn(0f, 1f)
        l = (l + dl * chromaWeight).coerceIn(0f, 1f)

        return hslToRgb(hDeg, s, l)
    }

    private fun hslToRgb(hDeg: Float, s: Float, l: Float): FloatArray {
        if (s <= 0.0001f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = hDeg / 360f
        return floatArrayOf(
            hue2rgb(p, q, hk + 1f / 3f).coerceIn(0f, 1f),
            hue2rgb(p, q, hk).coerceIn(0f, 1f),
            hue2rgb(p, q, hk - 1f / 3f).coerceIn(0f, 1f),
        )
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun applyClarityAndSharpen(
        pixels: IntArray,
        w: Int,
        h: Int,
        clarity: Float,
        sharpening: Float,
        maskAlpha: FloatArray? = null,
    ) {
        val n = w * h
        val luma = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            luma[i] = 0.2126f * ((c ushr 16) and 0xFF) +
                0.7152f * ((c ushr 8) and 0xFF) +
                0.0722f * (c and 0xFF)
        }

        val tmp3 = FloatArray(n)
        val blur3 = FloatArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = luma[row + (x - 1).coerceAtLeast(0)]
                val c = luma[row + x]
                val r = luma[row + (x + 1).coerceAtMost(w - 1)]
                tmp3[row + x] = (l + 2f * c + r) * 0.25f
            }
        }
        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0) * w
            val y1 = y * w
            val y2 = (y + 1).coerceAtMost(h - 1) * w
            for (x in 0 until w) {
                blur3[y1 + x] = (tmp3[y0 + x] + 2f * tmp3[y1 + x] + tmp3[y2 + x]) * 0.25f
            }
        }

        val blurWide = if (abs(clarity) > 0.5f) {
            val tmpW = FloatArray(n)
            val outW = FloatArray(n)
            val r = 4
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sum = 0f
                    var cnt = 0
                    for (dx in -r..r step 2) {
                        sum += blur3[row + (x + dx).coerceIn(0, w - 1)]
                        cnt++
                    }
                    tmpW[row + x] = sum / cnt
                }
            }
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sum = 0f
                    var cnt = 0
                    for (dy in -r..r step 2) {
                        sum += tmpW[(y + dy).coerceIn(0, h - 1) * w + x]
                        cnt++
                    }
                    outW[row + x] = sum / cnt
                }
            }
            outW
        } else null

        val sharpGain = (sharpening / 100f) * 1.35f
        val clarityGain = (clarity / 100f) * 1.15f

        for (i in 0 until n) {
            val l = luma[i]
            var delta = 0f
            if (sharpGain > 0.01f) {
                val dFine = l - blur3[i]
                if (abs(dFine) > 2.2f) delta += dFine * sharpGain
            }
            if (blurWide != null && abs(clarityGain) > 0.01f) {
                val dWide = l - blurWide[i]
                val midWeight = (4f * (l / 255f) * (1f - l / 255f)).coerceIn(0f, 1f)
                delta += dWide * clarityGain * midWeight
            }
            if (maskAlpha != null) {
                delta *= maskAlpha[i]
            }
            if (abs(delta) < 0.2f) continue
            val c = pixels[i]
            val r = (((c ushr 16) and 0xFF) + delta).roundToInt().coerceIn(0, 255)
            val g = (((c ushr 8) and 0xFF) + delta).roundToInt().coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun computeHistogram(bmp: Bitmap): HistogramData {
        val safe = ensureSoftwareArgb8888(bmp)
        val w = safe.width
        val h = safe.height
        val bins = 64
        val luma = IntArray(bins)
        val red = IntArray(bins)
        val green = IntArray(bins)
        val blue = IntArray(bins)
        if (w <= 0 || h <= 0) return HistogramData(luma, red, green, blue, 1)

        val pixels = IntArray(w * h)
        safe.getPixels(pixels, 0, w, 0, 0, w, h)
        val step = max(1, pixels.size / 65_536)
        var maxCount = 1
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
            val rb = (r shr 2).coerceIn(0, bins - 1)
            val gb = (g shr 2).coerceIn(0, bins - 1)
            val bb = (b shr 2).coerceIn(0, bins - 1)
            val lb = (l shr 2).coerceIn(0, bins - 1)
            red[rb]++
            green[gb]++
            blue[bb]++
            luma[lb]++
            if (luma[lb] > maxCount) maxCount = luma[lb]
            if (red[rb] > maxCount) maxCount = red[rb]
            if (green[gb] > maxCount) maxCount = green[gb]
            if (blue[bb] > maxCount) maxCount = blue[bb]
            i += step
        }
        return HistogramData(luma, red, green, blue, maxCount)
    }
}
