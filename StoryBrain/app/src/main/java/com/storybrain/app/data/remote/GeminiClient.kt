package com.storybrain.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini 客户端 (generativelanguage API)。
 * 走 /v1beta/models/{model}:generateContent。
 * - API Key 通过 `x-goog-api-key` 请求头传递（不放 URL query，避免被代理日志/浏览器历史记录泄露）
 * - generationConfig.responseMimeType = application/json 强制 JSON 输出
 * - 复用 [OpenAiCompatClient.sharedHttp] 共享连接池
 */
class GeminiClient(
    override val config: LlmConfig,
    private val http: OkHttpClient = OpenAiCompatClient.sharedHttp
) : LlmClient {

    override suspend fun chatJson(systemPrompt: String, userContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildRequestBody(systemPrompt, userContent)
                // API Key 放 header 不放 URL，避免被代理日志/浏览器历史记录泄露
                val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent"
                val req = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", config.apiKey)
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}: ${raw.take(500)}")
                    extractContent(raw)
                }
            }
        }

    override suspend fun chatText(systemPrompt: String, userContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildRequestBodyText(systemPrompt, userContent)
                val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent"
                val req = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", config.apiKey)
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}: ${raw.take(500)}")
                    extractContent(raw)
                }
            }
        }

    private fun buildRequestBody(system: String, user: String): String {
        val root = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", user) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", system) }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            }
        }
        return root.toString()
    }

    private fun buildRequestBodyText(system: String, user: String): String {
        val root = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", user) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", system) }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
            }
        }
        return root.toString()
    }

    private fun extractContent(raw: String): String {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val textEl = obj["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")
        // text 可能为 null 或 JsonNull（安全过滤触发时 candidates 为空），显式判空避免抛异常
        val text = when {
            textEl == null || textEl is JsonNull -> null
            textEl is JsonPrimitive && textEl.isString -> textEl.content
            else -> textEl.toString()
        }
        return OpenAiCompatClient.stripFences(text ?: error("Gemini 响应缺少 text: ${raw.take(300)}"))
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
