package com.zbox.ai.core

/** Android-free connection routing and frame-budget policy; UI values are target profiles. */
object MonitorPolicy {
    enum class ConnectionRoute { HOTSPOT, USB_SINGLE, USB_CHOOSE }
    fun connectionRoute(usbCount: Int): ConnectionRoute {
        require(usbCount >= 0)
        return when (usbCount) { 0 -> ConnectionRoute.HOTSPOT; 1 -> ConnectionRoute.USB_SINGLE; else -> ConnectionRoute.USB_CHOOSE }
    }
    val intervals = listOf(33, 63, 500)
    fun normalizeInterval(value: Int): Int = when (value) {
        33 -> 33
        60, 63, 150 -> 63 // Old smooth 16 fps and standard become the new standard.
        300, 500 -> 500 // Preserve power-saving intent after removing the old economy profile.
        else -> 63
    }
    fun intervalLabel(value: Int): String = when (normalizeInterval(value)) {
        33 -> "流畅 · 30fps"
        500 -> "省电 · 2fps"
        else -> "标准 · 16fps"
    }
    fun framePeriodNanos(value: Int): Long = when (normalizeInterval(value)) {
        33 -> 1_000_000_000L / 30
        500 -> 500_000_000L
        else -> 1_000_000_000L / 16
    }
    /** Account for camera transfer AND decode time, rather than sleeping a whole interval on top. */
    fun remainingDelayMs(elapsedNanos: Long, value: Int): Long {
        require(elapsedNanos >= 0)
        val remaining = framePeriodNanos(value) - elapsedNanos
        return if (remaining <= 0) 0 else (remaining + 999_999L) / 1_000_000L
    }
}
