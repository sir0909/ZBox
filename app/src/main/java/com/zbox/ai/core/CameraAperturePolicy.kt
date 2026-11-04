package com.zbox.ai.core

/** Parsed PTP DevicePropDesc capability for standard ApertureValue (F_NUMBER). */
data class CameraApertureCapabilities(
    val values: List<Float>,
    val current: Float?,
    val writable: Boolean,
)

object CameraAperturePolicy {
    private const val F_NUMBER = 0x5007
    private const val TYPE_UINT16 = 0x0004
    private const val MIN_CENTI_F_NUMBER = 50
    private const val MAX_CENTI_F_NUMBER = 6400

    /** PTP DevicePropDesc decoder; returns null for unsupported or malformed descriptors. */
    fun parseDescriptor(bytes: ByteArray, expectedCode: Int = F_NUMBER): CameraApertureCapabilities? = runCatching {
        fun u16(offset: Int): Int {
            require(offset >= 0 && offset + 1 < bytes.size)
            return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
        }
        require(bytes.size >= 12)
        require(u16(0) == expectedCode && u16(2) == TYPE_UINT16)
        val writable = (bytes[4].toInt() and 0xff) == 1
        val currentRaw = u16(7)
        val current = currentRaw.takeIf { it in MIN_CENTI_F_NUMBER..MAX_CENTI_F_NUMBER }?.div(100f)
        val form = bytes[9].toInt() and 0xff
        val values = when (form) {
            1 -> {
                require(bytes.size >= 16)
                val min = u16(10); val max = u16(12); val step = u16(14)
                require(min in MIN_CENTI_F_NUMBER..MAX_CENTI_F_NUMBER && max in min..MAX_CENTI_F_NUMBER && step > 0)
                val count = (max - min) / step + 1
                require(count in 1..128)
                (0 until count).map { (min + it * step) / 100f }
            }
            2 -> {
                val count = u16(10)
                require(count in 1..128 && bytes.size >= 12 + count * 2)
                (0 until count).map { u16(12 + it * 2) }
                    .filter { it in MIN_CENTI_F_NUMBER..MAX_CENTI_F_NUMBER }.map { it / 100f }.distinct().sorted()
            }
            else -> emptyList()
        }
        require(values.isNotEmpty())
        CameraApertureCapabilities(values = values, current = current, writable = writable)
    }.getOrNull()
}
