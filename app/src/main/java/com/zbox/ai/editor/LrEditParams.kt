package com.zbox.ai.editor

import com.zbox.ai.core.CubicSplineLut
import com.zbox.ai.core.Recipe
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class CropAspect(val label: String, val ratio: Float?) {
    ORIGINAL("原比例", null),
    FREE("自由", null),
    R1_1("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R3_2("3:2", 3f / 2f),
    R16_9("16:9", 16f / 9f),
}

enum class HslBand(val label: String, val colorHex: Long, val centerDeg: Float) {
    RED("红", 0xFFE5484D, 0f),
    ORANGE("橙", 0xFFF76B15, 30f),
    YELLOW("黄", 0xFFF5D90A, 60f),
    GREEN("绿", 0xFF46A758, 120f),
    AQUA("青", 0xFF12A594, 180f),
    BLUE("蓝", 0xFF0091FF, 225f),
    PURPLE("紫", 0xFF8E4EC6, 275f),
    MAGENTA("品红", 0xFFE93D82, 325f),
}

data class HslAdjust(
    val hue: Float = 0f,        // -100..100
    val sat: Float = 0f,        // -100..100
    val lum: Float = 0f,        // -100..100
) {
    val isNeutral: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

enum class MaskType(val label: String, val isAi: Boolean = false) {
    AI_PERSON("人物", true),
    AI_SKY("天空", true),
    AI_SUBJECT("主体", true),
    AI_BACKGROUND("背景", true),
    RADIAL("径向"),
    LINEAR("线性"),
    BRUSH("画笔"),
    LUMINANCE("亮度"),
}

object MaskAlphaCodec {
    fun encode(alpha: FloatArray): String {
        val raw = ByteArray(alpha.size) { i ->
            (alpha[i].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream(raw.size / 2 + 32)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n) else break
        }
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun decode(encoded: String, expectedSize: Int): FloatArray? {
        if (encoded.isBlank() || expectedSize <= 0) return null
        return runCatching {
            val compressed = Base64.getDecoder().decode(encoded)
            val inflater = Inflater()
            inflater.setInput(compressed)
            val raw = ByteArray(expectedSize)
            var offset = 0
            while (!inflater.finished() && offset < expectedSize) {
                val n = inflater.inflate(raw, offset, expectedSize - offset)
                if (n <= 0) break
                offset += n
            }
            inflater.end()
            if (offset <= 0) return null
            FloatArray(expectedSize) { i ->
                if (i < offset) (raw[i].toInt() and 0xFF) / 255f else 0f
            }
        }.getOrNull()
    }
}

data class MaskPoint(
    val x: Float,
    val y: Float,
)

data class MaskStroke(
    val points: List<MaskPoint>,
    val radius: Float = 0.08f,
    val hardness: Float = 0.5f,
    val erase: Boolean = false,
)

data class LocalMask(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "蒙版",
    val type: MaskType = MaskType.RADIAL,
    val enabled: Boolean = true,
    val invert: Boolean = false,
    val opacity: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusX: Float = 0.30f,
    val radiusY: Float = 0.30f,
    val feather: Float = 0.45f,
    val startX: Float = 0.5f,
    val startY: Float = 0.16f,
    val endX: Float = 0.5f,
    val endY: Float = 0.58f,
    val strokes: List<MaskStroke> = emptyList(),
    val brushSize: Float = 0.08f,
    val brushFeather: Float = 0.5f,
    val lumaMin: Float = 0.55f,
    val lumaMax: Float = 1.0f,
    val lumaFeather: Float = 0.15f,
    val aiMaskBase64: String = "",
    val aiMaskWidth: Int = 0,
    val aiMaskHeight: Int = 0,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val clarity: Float = 0f,
    val sharpening: Float = 0f,
) {
    val hasAdjustment: Boolean
        get() = exposure != 0f || contrast != 0f || highlights != 0f || shadows != 0f ||
            temperature != 0f || tint != 0f || saturation != 0f || clarity != 0f || sharpening != 0f

    fun clamped(): LocalMask {
        val lMin = lumaMin.coerceIn(0f, 1f)
        val lMax = lumaMax.coerceIn(lMin, 1f)
        return copy(
            opacity = opacity.coerceIn(0f, 1f),
            centerX = centerX.coerceIn(-0.5f, 1.5f),
            centerY = centerY.coerceIn(-0.5f, 1.5f),
            radiusX = radiusX.coerceIn(0.04f, 1.5f),
            radiusY = radiusY.coerceIn(0.04f, 1.5f),
            feather = feather.coerceIn(0f, 1f),
            startX = startX.coerceIn(-0.5f, 1.5f),
            startY = startY.coerceIn(-0.5f, 1.5f),
            endX = endX.coerceIn(-0.5f, 1.5f),
            endY = endY.coerceIn(-0.5f, 1.5f),
            brushSize = brushSize.coerceIn(0.015f, 0.25f),
            brushFeather = brushFeather.coerceIn(0f, 1f),
            lumaMin = lMin,
            lumaMax = lMax,
            lumaFeather = lumaFeather.coerceIn(0.02f, 0.45f),
            exposure = exposure.coerceIn(-2f, 2f),
            contrast = contrast.coerceIn(-100f, 100f),
            highlights = highlights.coerceIn(-100f, 100f),
            shadows = shadows.coerceIn(-100f, 100f),
            temperature = temperature.coerceIn(-100f, 100f),
            tint = tint.coerceIn(-100f, 100f),
            saturation = saturation.coerceIn(-100f, 100f),
            clarity = clarity.coerceIn(-100f, 100f),
            sharpening = sharpening.coerceIn(0f, 100f),
        )
    }
}

/**
 * 非破坏性 Lightroom 风格照片调色参数模型。
 */
data class LrEditParams(
    val exposure: Float = 0f,       // -2.0 .. +2.0 EV
    val contrast: Float = 0f,       // -100 .. +100
    val highlights: Float = 0f,     // -100 .. +100
    val shadows: Float = 0f,        // -100 .. +100
    val whites: Float = 0f,         // -100 .. +100
    val blacks: Float = 0f,         // -100 .. +100

    val temperature: Float = 0f,    // -100 .. +100
    val tint: Float = 0f,           // -100 .. +100
    val vibrance: Float = 0f,       // -100 .. +100
    val saturation: Float = 0f,     // -100 .. +100

    val hsl: Map<HslBand, HslAdjust> = emptyMap(),

    val curvePoints: List<CubicSplineLut.Point> = Recipe.DEFAULT_CURVE,
    val lutStrength: Float = 1f,    // 0 .. 1
    val appliedPresetName: String = "",

    val clarity: Float = 0f,        // -100 .. +100
    val sharpening: Float = 0f,     // 0 .. 100
    val vignette: Float = 0f,       // -100 .. +100
    val grain: Float = 0f,          // 0 .. 100

    val rotation90: Int = 0,        // 0..3
    val flipHorizontal: Boolean = false,
    val aspect: CropAspect = CropAspect.ORIGINAL,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val masks: List<LocalMask> = emptyList(),
) {
    fun hslOf(band: HslBand): HslAdjust = hsl[band] ?: HslAdjust()

    fun withHsl(band: HslBand, adj: HslAdjust): LrEditParams {
        val next = hsl.toMutableMap()
        if (adj.isNeutral) next.remove(band) else next[band] = adj
        return copy(hsl = next)
    }

    fun clamped(): LrEditParams {
        val l = cropLeft.coerceIn(0f, 0.9f)
        val t = cropTop.coerceIn(0f, 0.9f)
        val r = cropRight.coerceIn(l + 0.08f, 1f)
        val b = cropBottom.coerceIn(t + 0.08f, 1f)
        return copy(
            exposure = exposure.coerceIn(-2f, 2f),
            contrast = contrast.coerceIn(-100f, 100f),
            highlights = highlights.coerceIn(-100f, 100f),
            shadows = shadows.coerceIn(-100f, 100f),
            whites = whites.coerceIn(-100f, 100f),
            blacks = blacks.coerceIn(-100f, 100f),
            temperature = temperature.coerceIn(-100f, 100f),
            tint = tint.coerceIn(-100f, 100f),
            vibrance = vibrance.coerceIn(-100f, 100f),
            saturation = saturation.coerceIn(-100f, 100f),
            lutStrength = lutStrength.coerceIn(0f, 1f),
            clarity = clarity.coerceIn(-100f, 100f),
            sharpening = sharpening.coerceIn(0f, 100f),
            vignette = vignette.coerceIn(-100f, 100f),
            grain = grain.coerceIn(0f, 100f),
            rotation90 = ((rotation90 % 4) + 4) % 4,
            cropLeft = l,
            cropTop = t,
            cropRight = r,
            cropBottom = b,
            masks = masks.map { it.clamped() },
        )
    }

    val hasGeometryChange: Boolean
        get() = (rotation90 % 4) != 0 || flipHorizontal ||
            cropLeft > 0.002f || cropTop > 0.002f || cropRight < 0.998f || cropBottom < 0.998f

    val isIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && temperature == 0f && tint == 0f &&
            vibrance == 0f && saturation == 0f && hsl.values.all { it.isNeutral } &&
            clarity == 0f && sharpening == 0f && vignette == 0f && grain == 0f &&
            !hasGeometryChange &&
            masks.none { it.enabled && it.hasAdjustment } &&
            (lutStrength <= 0.001f || isDefaultCurve(curvePoints))

    companion object {
        val DEFAULT = LrEditParams()

        fun isDefaultCurve(pts: List<CubicSplineLut.Point>): Boolean {
            if (pts.size < 2) return true
            return pts.all { kotlin.math.abs(it.x - it.y) < 0.6f }
        }
    }
}

data class SavedLrPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "我的调色预设",
    val createdAt: Long = System.currentTimeMillis(),
    val params: LrEditParams = LrEditParams.DEFAULT,
)

object LrPresetCodec {

    fun toJson(p: LrEditParams): JSONObject = JSONObject().apply {
        put("exposure", p.exposure.toDouble())
        put("contrast", p.contrast.toDouble())
        put("highlights", p.highlights.toDouble())
        put("shadows", p.shadows.toDouble())
        put("whites", p.whites.toDouble())
        put("blacks", p.blacks.toDouble())
        put("temperature", p.temperature.toDouble())
        put("tint", p.tint.toDouble())
        put("vibrance", p.vibrance.toDouble())
        put("saturation", p.saturation.toDouble())
        put("lutStrength", p.lutStrength.toDouble())
        put("appliedPresetName", p.appliedPresetName)
        put("clarity", p.clarity.toDouble())
        put("sharpening", p.sharpening.toDouble())
        put("vignette", p.vignette.toDouble())
        put("grain", p.grain.toDouble())
        put("rotation90", p.rotation90)
        put("flipHorizontal", p.flipHorizontal)
        put("aspect", p.aspect.name)
        put("cropLeft", p.cropLeft.toDouble())
        put("cropTop", p.cropTop.toDouble())
        put("cropRight", p.cropRight.toDouble())
        put("cropBottom", p.cropBottom.toDouble())

        val curveArr = JSONArray()
        p.curvePoints.forEach { pt ->
            curveArr.put(JSONObject().put("x", pt.x.toDouble()).put("y", pt.y.toDouble()))
        }
        put("curvePoints", curveArr)

        val hslObj = JSONObject()
        p.hsl.forEach { (band, adj) ->
            if (!adj.isNeutral) {
                hslObj.put(
                    band.name,
                    JSONObject()
                        .put("hue", adj.hue.toDouble())
                        .put("sat", adj.sat.toDouble())
                        .put("lum", adj.lum.toDouble())
                )
            }
        }
        put("hsl", hslObj)

        if (p.masks.isNotEmpty()) {
            val masksArr = JSONArray()
            p.masks.forEach { m ->
                val mo = JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("type", m.type.name)
                    .put("enabled", m.enabled)
                    .put("invert", m.invert)
                    .put("opacity", m.opacity.toDouble())
                    .put("centerX", m.centerX.toDouble())
                    .put("centerY", m.centerY.toDouble())
                    .put("radiusX", m.radiusX.toDouble())
                    .put("radiusY", m.radiusY.toDouble())
                    .put("feather", m.feather.toDouble())
                    .put("startX", m.startX.toDouble())
                    .put("startY", m.startY.toDouble())
                    .put("endX", m.endX.toDouble())
                    .put("endY", m.endY.toDouble())
                    .put("brushSize", m.brushSize.toDouble())
                    .put("brushFeather", m.brushFeather.toDouble())
                    .put("lumaMin", m.lumaMin.toDouble())
                    .put("lumaMax", m.lumaMax.toDouble())
                    .put("lumaFeather", m.lumaFeather.toDouble())
                    .put("aiMaskBase64", m.aiMaskBase64)
                    .put("aiMaskWidth", m.aiMaskWidth)
                    .put("aiMaskHeight", m.aiMaskHeight)
                    .put("exposure", m.exposure.toDouble())
                    .put("contrast", m.contrast.toDouble())
                    .put("highlights", m.highlights.toDouble())
                    .put("shadows", m.shadows.toDouble())
                    .put("temperature", m.temperature.toDouble())
                    .put("tint", m.tint.toDouble())
                    .put("saturation", m.saturation.toDouble())
                    .put("clarity", m.clarity.toDouble())
                    .put("sharpening", m.sharpening.toDouble())
                val strokesArr = JSONArray()
                m.strokes.forEach { st ->
                    val ptsArr = JSONArray()
                    st.points.forEach { pt ->
                        ptsArr.put(JSONObject().put("x", pt.x.toDouble()).put("y", pt.y.toDouble()))
                    }
                    strokesArr.put(JSONObject().put("r", st.radius.toDouble()).put("h", st.hardness.toDouble()).put("e", st.erase).put("pts", ptsArr))
                }
                mo.put("strokes", strokesArr)
                masksArr.put(mo)
            }
            put("masks", masksArr)
        }
    }

    fun fromJson(o: JSONObject): LrEditParams {
        val pts = mutableListOf<CubicSplineLut.Point>()
        o.optJSONArray("curvePoints")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pt = arr.optJSONObject(i) ?: continue
                pts.add(CubicSplineLut.Point(pt.optDouble("x", 0.0).toFloat(), pt.optDouble("y", 0.0).toFloat()))
            }
        }
        val hslMap = mutableMapOf<HslBand, HslAdjust>()
        o.optJSONObject("hsl")?.let { ho ->
            HslBand.entries.forEach { band ->
                ho.optJSONObject(band.name)?.let { bObj ->
                    val adj = HslAdjust(
                        hue = bObj.optDouble("hue", 0.0).toFloat(),
                        sat = bObj.optDouble("sat", 0.0).toFloat(),
                        lum = bObj.optDouble("lum", 0.0).toFloat(),
                    )
                    if (!adj.isNeutral) hslMap[band] = adj
                }
            }
        }
        val aspect = runCatching { CropAspect.valueOf(o.optString("aspect", "ORIGINAL")) }
            .getOrDefault(CropAspect.ORIGINAL)
        val maskList = mutableListOf<LocalMask>()
        o.optJSONArray("masks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val mo = arr.optJSONObject(i) ?: continue
                val type = runCatching { MaskType.valueOf(mo.optString("type", "RADIAL")) }.getOrDefault(MaskType.RADIAL)
                val strokes = mutableListOf<MaskStroke>()
                mo.optJSONArray("strokes")?.let { sArr ->
                    for (j in 0 until sArr.length()) {
                        val so = sArr.optJSONObject(j) ?: continue
                        val ptsList = mutableListOf<MaskPoint>()
                        so.optJSONArray("pts")?.let { pArr ->
                            for (k in 0 until pArr.length()) {
                                val po = pArr.optJSONObject(k) ?: continue
                                ptsList.add(MaskPoint(po.optDouble("x", 0.0).toFloat(), po.optDouble("y", 0.0).toFloat()))
                            }
                        }
                        if (ptsList.isNotEmpty()) {
                            strokes.add(MaskStroke(ptsList, so.optDouble("r", 0.08).toFloat(), so.optDouble("h", 0.5).toFloat(), so.optBoolean("e", false)))
                        }
                    }
                }
                maskList.add(
                    LocalMask(
                        id = mo.optString("id", UUID.randomUUID().toString()),
                        name = mo.optString("name", "蒙版"),
                        type = type,
                        enabled = mo.optBoolean("enabled", true),
                        invert = mo.optBoolean("invert", false),
                        opacity = mo.optDouble("opacity", 1.0).toFloat(),
                        centerX = mo.optDouble("centerX", 0.5).toFloat(),
                        centerY = mo.optDouble("centerY", 0.5).toFloat(),
                        radiusX = mo.optDouble("radiusX", 0.3).toFloat(),
                        radiusY = mo.optDouble("radiusY", 0.3).toFloat(),
                        feather = mo.optDouble("feather", 0.45).toFloat(),
                        startX = mo.optDouble("startX", 0.5).toFloat(),
                        startY = mo.optDouble("startY", 0.16).toFloat(),
                        endX = mo.optDouble("endX", 0.5).toFloat(),
                        endY = mo.optDouble("endY", 0.58).toFloat(),
                        strokes = strokes,
                        brushSize = mo.optDouble("brushSize", 0.08).toFloat(),
                        brushFeather = mo.optDouble("brushFeather", 0.5).toFloat(),
                        lumaMin = mo.optDouble("lumaMin", 0.55).toFloat(),
                        lumaMax = mo.optDouble("lumaMax", 1.0).toFloat(),
                        lumaFeather = mo.optDouble("lumaFeather", 0.15).toFloat(),
                        aiMaskBase64 = mo.optString("aiMaskBase64", ""),
                        aiMaskWidth = mo.optInt("aiMaskWidth", 0),
                        aiMaskHeight = mo.optInt("aiMaskHeight", 0),
                        exposure = mo.optDouble("exposure", 0.0).toFloat(),
                        contrast = mo.optDouble("contrast", 0.0).toFloat(),
                        highlights = mo.optDouble("highlights", 0.0).toFloat(),
                        shadows = mo.optDouble("shadows", 0.0).toFloat(),
                        temperature = mo.optDouble("temperature", 0.0).toFloat(),
                        tint = mo.optDouble("tint", 0.0).toFloat(),
                        saturation = mo.optDouble("saturation", 0.0).toFloat(),
                        clarity = mo.optDouble("clarity", 0.0).toFloat(),
                        sharpening = mo.optDouble("sharpening", 0.0).toFloat(),
                    )
                )
            }
        }

        return LrEditParams(
            exposure = o.optDouble("exposure", 0.0).toFloat(),
            contrast = o.optDouble("contrast", 0.0).toFloat(),
            highlights = o.optDouble("highlights", 0.0).toFloat(),
            shadows = o.optDouble("shadows", 0.0).toFloat(),
            whites = o.optDouble("whites", 0.0).toFloat(),
            blacks = o.optDouble("blacks", 0.0).toFloat(),
            temperature = o.optDouble("temperature", 0.0).toFloat(),
            tint = o.optDouble("tint", 0.0).toFloat(),
            vibrance = o.optDouble("vibrance", 0.0).toFloat(),
            saturation = o.optDouble("saturation", 0.0).toFloat(),
            hsl = hslMap,
            curvePoints = if (pts.size >= 2) pts else Recipe.DEFAULT_CURVE,
            lutStrength = o.optDouble("lutStrength", 1.0).toFloat(),
            appliedPresetName = o.optString("appliedPresetName", ""),
            clarity = o.optDouble("clarity", 0.0).toFloat(),
            sharpening = o.optDouble("sharpening", 0.0).toFloat(),
            vignette = o.optDouble("vignette", 0.0).toFloat(),
            grain = o.optDouble("grain", 0.0).toFloat(),
            rotation90 = o.optInt("rotation90", 0),
            flipHorizontal = o.optBoolean("flipHorizontal", false),
            aspect = aspect,
            cropLeft = o.optDouble("cropLeft", 0.0).toFloat(),
            cropTop = o.optDouble("cropTop", 0.0).toFloat(),
            cropRight = o.optDouble("cropRight", 1.0).toFloat(),
            cropBottom = o.optDouble("cropBottom", 1.0).toFloat(),
            masks = maskList,
        ).clamped()
    }

    fun presetToJson(p: SavedLrPreset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("createdAt", p.createdAt)
        put("params", toJson(p.params))
    }

    fun presetFromJson(o: JSONObject): SavedLrPreset = SavedLrPreset(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "我的预设"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        params = fromJson(o.optJSONObject("params") ?: JSONObject()),
    )

    fun fromRecipe(recipe: Recipe, displayName: String = recipe.name, current: LrEditParams = LrEditParams.DEFAULT): LrEditParams {
        val curve = if (recipe.useCustomCurve && recipe.curvePoints.size >= 2) {
            recipe.curvePoints
        } else {
            Recipe.DEFAULT_CURVE
        }
        val isMono = recipe.pcdType == 1 || recipe.baseProfile.equals("MONOCHROME", ignoreCase = true)
        return current.copy(
            exposure = (recipe.brightness * 0.45f).coerceIn(-2f, 2f),
            contrast = (recipe.contrast * 18f).coerceIn(-100f, 100f),
            saturation = if (isMono) -100f else (recipe.saturation * 22f).coerceIn(-100f, 100f),
            clarity = (recipe.clarity * 14f).coerceIn(-100f, 100f),
            sharpening = ((recipe.sharpening.coerceAtLeast(0f)) * 9f).coerceIn(0f, 100f),
            curvePoints = curve,
            lutStrength = 1f,
            appliedPresetName = displayName.ifBlank { recipe.name },
        ).clamped()
    }
}
