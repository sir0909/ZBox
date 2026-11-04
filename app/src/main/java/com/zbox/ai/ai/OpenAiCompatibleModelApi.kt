package com.zbox.ai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI-compatible /models listing and minimal selected-model health probe. */
object OpenAiCompatibleModelApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "请先填写 API Key" }
            val endpoint = modelsEndpoint(baseUrl)
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { errorMessage("GET /models", response.code, text) }
                val arr = JSONObject(text).optJSONArray("data")
                    ?: error("返回内容中没有标准 OpenAI 格式的 data 模型列表")
                val models = buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty().trim()
                        if (id.isNotBlank()) add(id)
                    }
                }.distinct().sorted()
                check(models.isNotEmpty()) { "API 返回成功，但没有可用模型" }
                models
            }
        }
    }

    /** Sends one tiny, non-streaming request; provider may charge a minimal token. */
    suspend fun testModel(baseUrl: String, apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "请先填写 API Key" }
            require(model.isNotBlank()) { "请选择或填写模型 ID" }
            val endpoint = chatEndpoint(baseUrl)
            val payload = JSONObject()
                .put("model", model.trim())
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK")))
                .put("max_tokens", 1)
                .put("temperature", 0)
                .put("stream", false)
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { errorMessage("POST /chat/completions", response.code, text) }
                "HTTP ${response.code} · $model 调用成功"
            }
        }
    }

    private fun modelsEndpoint(baseUrl: String): String = base(baseUrl) + "/models"
    private fun chatEndpoint(baseUrl: String): String = base(baseUrl) + "/chat/completions"

    private fun base(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
            "Base URL 必须以 http:// 或 https:// 开头"
        }
        return value.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
    }

    private fun errorMessage(operation: String, code: Int, body: String): String {
        val excerpt = body.replace(Regex("\\s+"), " ").take(260)
        return "$operation 返回 HTTP $code${if (excerpt.isBlank()) "" else "：$excerpt"}"
    }
}
