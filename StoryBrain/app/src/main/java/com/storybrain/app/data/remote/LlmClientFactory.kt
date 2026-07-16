package com.storybrain.app.data.remote

/**
 * LLM 客户端工厂：根据 [LlmConfig.provider] 创建对应实现。
 * - gemini → [GeminiClient]（独立协议）
 * - deepseek / openai / 其它兼容服务 → [OpenAiCompatClient]（OpenAI 兼容协议）
 */
object LlmClientFactory {
    fun create(config: LlmConfig): LlmClient = when (config.provider) {
        LlmConfig.PROVIDER_GEMINI -> GeminiClient(config)
        else -> OpenAiCompatClient(config)  // deepseek / openai / 兼容服务
    }
}
