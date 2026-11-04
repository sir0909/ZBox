package com.zbox.ai.core

/** Normalized coordinates refer only to the exact analyzed snapshot, never a later live frame. */
data class CompositionBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class CompositionAdvice(val summary: String, val moves: List<String>, val tips: List<String>,
    val crop: CompositionBox, val subject: CompositionBox?)

object CompositionPolicy {
    @JvmStatic fun box(left: Double, top: Double, right: Double, bottom: Double): CompositionBox? {
        if (listOf(left, top, right, bottom).any { !it.isFinite() || it < 0 || it > 1 }) return null
        if (right - left < 0.08 || bottom - top < 0.08 || (right-left)*(bottom-top) < 0.03) return null
        return CompositionBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
    @JvmStatic fun cleanText(value: String): String = value
        .replace(Regex("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]"), " ").trim().take(160)
    @JvmStatic fun canAnalyze(open: Boolean, busy: Boolean, connected: Boolean, live: Boolean,
        hasFrame: Boolean, shooting: Boolean, vision: Boolean, enabled: Boolean, hasKey: Boolean): Boolean =
        open && !busy && connected && live && hasFrame && !shooting && vision && enabled && hasKey
}
