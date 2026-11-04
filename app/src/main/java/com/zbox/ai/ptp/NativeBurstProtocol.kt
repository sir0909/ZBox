package com.zbox.ai.ptp

/** Bounds-checked PTP decoding. No Android dependency, no shutter side effects. */
object NativeBurstProtocol {
    data class U16Prop(
        val code: Int, val writable: Boolean, val current: Int, val choices: Set<Int>,
        val minimum: Int?, val maximum: Int?, val step: Int?,
    ) {
        fun allows(value: Int): Boolean {
            if (value !in 0..65535) return false
            if (choices.isNotEmpty()) return value in choices
            if (minimum != null && maximum != null && step != null)
                return value in minimum..maximum && (value - minimum) % step == 0
            return true
        }
        fun frameLimit(cameraMaximum: Int?): Int {
            val upper = minOf(cameraMaximum ?: 100, maximum ?: 65535, 100)
            require(upper >= 2) { "机身每组连拍上限不足 2 张" }
            val limit = when {
                choices.isNotEmpty() -> choices.filter { it in 2..upper }.maxOrNull()
                minimum != null && step != null && upper >= minimum -> upper - (upper - minimum) % step
                else -> upper
            }
            require(limit != null && limit >= 2 && allows(limit)) { "没有可安全使用的连拍张数" }
            return limit
        }
    }
    fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 2) { "Truncated UINT16" }
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }
    fun descriptor(bytes: ByteArray, code: Int): U16Prop {
        require(bytes.size >= 10 && u16(bytes, 0) == code && u16(bytes, 2) == 4) { "Unsupported burst descriptor" }
        require(bytes[4].toInt() in 0..1) { "Invalid GetSet" }
        var choices = emptySet<Int>()
        var minimum: Int? = null
        var maximum: Int? = null
        var step: Int? = null
        when (bytes[9].toInt() and 255) {
            0 -> Unit
            1 -> {
                minimum = u16(bytes, 10); maximum = u16(bytes, 12); step = u16(bytes, 14)
                require(minimum <= maximum && step > 0) { "Invalid burst range" }
            }
            2 -> {
                val count = u16(bytes, 10)
                require(count in 1..1024 && count * 2 <= bytes.size - 12) { "Truncated burst enumeration" }
                choices = buildSet { repeat(count) { add(u16(bytes, 12 + it * 2)) } }
            }
            else -> error("Unknown burst descriptor form")
        }
        val result = U16Prop(code, bytes[4].toInt() == 1, u16(bytes, 7), choices, minimum, maximum, step)
        require(result.allows(result.current)) { "Burst current value outside advertised domain" }
        return result
    }
    fun operations(bytes: ByteArray): Set<Int> {
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 9)
        b.short; b.int; b.short
        val chars = b.get().toInt() and 255
        require(chars * 2 + 6 <= b.remaining())
        b.position(b.position() + chars * 2); b.short
        val count = b.int
        require(count in 0..2048 && count * 2 <= b.remaining()) { "Invalid operation list" }
        return buildSet { repeat(count) { add(b.short.toInt() and 65535) } }
    }
}

/** Main-thread owner, volatile flags for stop visibility. Resume denial is sticky until a NEW press. */
class NativeBurstRequest {
    @Volatile var held: Boolean = false
        private set
    @Volatile var mayResumeLiveView: Boolean = true
        private set
    fun begin() { held = true; mayResumeLiveView = true }
    fun release(resumeLiveView: Boolean = true) {
        held = false
        if (!resumeLiveView) mayResumeLiveView = false
    }
}
