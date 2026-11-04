package com.zbox.ai.core

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class ReleaseApk(val name: String, val url: String, val uploaded: Boolean, val size: Long)
data class ReleaseUpdate(val version: String, val apkUrl: String)

/** Version-name only. No build-number manifest, telemetry, download or install side effects. */
object ReleaseUpdatePolicy {
    private fun parts(value: String): List<Int>? {
        if (!Regex("^[vV]?[0-9]{1,6}(\\.[0-9]{1,6}){1,3}$").matches(value)) return null
        return value.removePrefix("v").removePrefix("V").split('.').map { it.toInt() }
    }
    fun isNewer(remote: String, current: String): Boolean {
        val a = parts(remote) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val cmp = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (cmp != 0) return cmp > 0
        }
        return false
    }
    fun trustedApk(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port in listOf(-1, 443) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/sir0909/ZBox/releases/download/") &&
            uri.rawPath.endsWith(".apk", ignoreCase = true) &&
            !uri.rawPath.contains("%", ignoreCase = true) && !uri.rawPath.contains("/../") && !uri.rawPath.contains("/./")
    }.getOrDefault(false)
    fun candidate(tag: String, draft: Boolean, prerelease: Boolean, assets: List<ReleaseApk>, current: String): ReleaseUpdate? {
        if (draft || prerelease || !isNewer(tag, current)) return null
        val apk = assets.firstOrNull { it.uploaded && it.size in 1..209_715_200L && it.name.endsWith(".apk", true) && trustedApk(it.url) } ?: return null
        return ReleaseUpdate(tag.removePrefix("v").removePrefix("V"), apk.url)
    }
    fun startupWindowAllows(ageMs: Long, foreground: Boolean, cameraBusy: Boolean): Boolean =
        ageMs in 0..8000 && foreground && !cameraBusy
}

/** One claim per process; dismiss/failure does not schedule another check. */
class StartupUpdateGate {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
