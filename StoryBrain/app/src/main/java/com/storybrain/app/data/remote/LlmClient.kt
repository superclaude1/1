package com.storybrain.app.data.remote

/**
 * 统一 LLM 客户端接口。无论 DeepSeek / Gemini / OpenAI 兼容服务，都收敛为：
 * 给定系统指令 + 用户内容，要求模型返回 **纯 JSON 字符串**。
 */
interface LlmClient {
    val config: LlmConfig

    /**
     * @param systemPrompt 系统指令（Schema 与任务约束）
     * @param userContent  本章正文 + 上一状态上下文
     * @return 模型输出的 JSON 字符串（已剥离 markdown 围栏）
     */
    suspend fun chatJson(systemPrompt: String, userContent: String): Result<String>

    /**
     * 与角色自由对话（不限制 JSON，输出普通文本，温度设高以增加口语色彩）
     */
    suspend fun chatText(systemPrompt: String, userContent: String): Result<String>
}
