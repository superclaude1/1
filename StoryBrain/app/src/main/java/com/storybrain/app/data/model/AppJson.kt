package com.storybrain.app.data.model

import kotlinx.serialization.json.Json

/** 全局 JSON 配置：宽松解析，忽略未知字段，便于 LLM 输出容错 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = true
}
