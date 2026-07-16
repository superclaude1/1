package com.storybrain.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议客户端，同时服务 DeepSeek / OpenAI / 其它兼容服务。
 * 走 /v1/chat/completions，开启 response_format=json_object 强制 JSON 输出。
 */
class OpenAiCompatClient(
    override val config: LlmConfig,
    private val http: OkHttpClient = sharedHttp
) : LlmClient {

    override suspend fun chatJson(systemPrompt: String, userContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildRequestBody(systemPrompt, userContent)
                val baseUrlClean = config.baseUrl.trimEnd('/')
                val url = if (baseUrlClean.endsWith("/v1")) {
                    "$baseUrlClean/chat/completions"
                } else if (baseUrlClean.endsWith("/v1/chat/completions")) {
                    baseUrlClean
                } else {
                    "$baseUrlClean/v1/chat/completions"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("LLM HTTP ${resp.code}: ${raw.take(500)}")
                    extractContent(raw)
                }
            }
        }

    override suspend fun chatText(systemPrompt: String, userContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildRequestBodyText(systemPrompt, userContent)
                val baseUrlClean = config.baseUrl.trimEnd('/')
                val url = if (baseUrlClean.endsWith("/v1")) {
                    "$baseUrlClean/chat/completions"
                } else if (baseUrlClean.endsWith("/v1/chat/completions")) {
                    baseUrlClean
                } else {
                    "$baseUrlClean/v1/chat/completions"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("LLM HTTP ${resp.code}: ${raw.take(500)}")
                    extractContent(raw)
                }
            }
        }

    private fun buildRequestBody(system: String, user: String): String {
        val root = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            put("temperature", 0.2)
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        return root.toString()
    }

    private fun buildRequestBodyText(system: String, user: String): String {
        val root = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            put("temperature", 0.7)
        }
        return root.toString()
    }

    private fun extractContent(raw: String): String {
        val obj = AppJsonRef.parseToJsonElement(raw).jsonObject
        val contentEl = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
        // content 可能为 null 或 JsonNull（模型拒绝回答时返回 "content": null），
        // 直接访问 jsonPrimitive 会抛异常，这里显式判空
        val content = when {
            contentEl == null || contentEl is JsonNull -> null
            contentEl is JsonPrimitive && contentEl.isString -> contentEl.content
            else -> contentEl.toString()
        }
        return stripFences(content ?: error("LLM 响应缺少 content: ${raw.take(300)}"))
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val AppJsonRef = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 全局共享 OkHttpClient（连接池/线程池复用，避免每次创建泄漏） */
        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /** 剥离 ```json ... ``` 围栏 */
        fun stripFences(s: String): String {
            val t = s.trim()
            if (!t.startsWith("```")) return t
            val noHead = t.substringAfter("\n", t)
            val noTail = noHead.substringBeforeLast("```", noHead)
            return noTail.trim()
        }
    }
}
