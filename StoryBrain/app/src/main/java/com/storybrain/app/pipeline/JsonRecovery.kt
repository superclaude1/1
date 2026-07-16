package com.storybrain.app.pipeline

import com.storybrain.app.data.model.AppJson
import com.storybrain.app.data.model.LlmDeltaResponse
import kotlinx.serialization.KSerializer

/**
 * LLM 输出 JSON 自愈工具。
 * 即使模型偶尔没遵守 response_format，输出带前缀说明或围栏，也尽量抢救出有效 JSON。
 */
object JsonRecovery {

    private val SERIALIZER = LlmDeltaResponse.serializer()

    fun parseDelta(raw: String): LlmDeltaResponse {
        // 1) 直接解析
        runCatching { return AppJson.decodeFromString(SERIALIZER, raw) }
        // 2) 剥离围栏后再解析
        val stripped = stripFencesAndNoise(raw)
        runCatching { return AppJson.decodeFromString(SERIALIZER, stripped) }
        // 3) 截取第一个 { 到最后一个 }
        val sub = substringJson(stripped)
            ?: throw IllegalArgumentException("无法从模型输出中提取 JSON：${raw.take(200)}")
        runCatching { return AppJson.decodeFromString(SERIALIZER, sub) }
        // 4) 尝试修复常见尾逗号（加全局标志，否则只替换第一个）
        val fixed = sub
            .replace(Regex(""",\s*}""", RegexOption.MULTILINE), "}")
            .replace(Regex(""",\s*]""", RegexOption.MULTILINE), "]")
        return AppJson.decodeFromString(SERIALIZER, fixed)
    }

    fun stripFencesAndNoise(s: String): String {
        var t = s.trim()
        // ```json ... ``` 或 ``` ... ```
        if (t.startsWith("```")) {
            t = t.substringAfter("\n", t)
            val last = t.lastIndexOf("```")
            if (last >= 0) t = t.substring(0, last)
        }
        return t.trim()
    }

    private fun substringJson(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }
}
