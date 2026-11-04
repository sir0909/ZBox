package com.zbox.ai.data

import com.zbox.ai.core.ReleaseApk
import com.zbox.ai.core.ReleaseUpdate
import com.zbox.ai.core.ReleaseUpdatePolicy
import com.zbox.ai.core.StartupUpdateGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

object GitHubReleaseUpdater {
    val startupGate = StartupUpdateGate()
    const val RELEASE_API = "https://api.github.com/repos/sir0909/ZBox/releases/latest"

    /** Public metadata only. No account, token, user settings, images or device identifier is sent. */
    suspend fun latest(current: String): ReleaseUpdate? = withContext(Dispatchers.IO) {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Zbox/$current")
            if (connection.responseCode != 200) throw IOException("GitHub 返回 HTTP ${connection.responseCode}")
            val maxBytes = 262144
            if (connection.contentLengthLong > maxBytes) throw IOException("更新信息超过大小限制")
            val bytes = connection.inputStream.use { it.readBytesUpTo(maxBytes + 1) }
            if (bytes.size > maxBytes) throw IOException("更新信息超过大小限制")
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val assets = root.optJSONArray("assets") ?: return@withContext null
            val apks = (0 until minOf(assets.length(), 100)).mapNotNull { i ->
                val a = assets.optJSONObject(i) ?: return@mapNotNull null
                ReleaseApk(a.optString("name"), a.optString("browser_download_url"), a.optString("state") == "uploaded", a.optLong("size", 0))
            }
            ReleaseUpdatePolicy.candidate(root.optString("tag_name"), root.optBoolean("draft", true), root.optBoolean("prerelease", true), apks, current)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
