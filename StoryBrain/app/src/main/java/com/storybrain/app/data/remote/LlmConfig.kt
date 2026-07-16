package com.storybrain.app.data.remote

/**
 * LLM 提供商配置，持久化在 [com.storybrain.app.data.SettingsManager]。
 * 切换 provider 时 UI 会自动填入对应默认 model/baseUrl（见 DEEPSEEK_DEFAULT / GEMINI_DEFAULT）。
 */
data class LlmConfig(
    val provider: String = PROVIDER_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val baseUrl: String = "https://api.deepseek.com",
    /** 单章最大字符预算（控制投喂上下文长度，避免超出模型 token 上限） */
    val maxContextChars: Int = 12000
) {
    companion object {
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_OPENAI = "openai"

        /** DeepSeek 默认配置（OpenAI 兼容协议） */
        val DEEPSEEK_DEFAULT = LlmConfig(
            provider = PROVIDER_DEEPSEEK,
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com"
        )
        /** Gemini 默认配置（generativelanguage API） */
        val GEMINI_DEFAULT = LlmConfig(
            provider = PROVIDER_GEMINI,
            model = "gemini-1.5-flash",
            baseUrl = "https://generativelanguage.googleapis.com"
        )
    }
}
