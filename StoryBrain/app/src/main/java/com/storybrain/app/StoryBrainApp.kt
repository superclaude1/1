package com.storybrain.app

import android.app.Application
import com.storybrain.app.data.SettingsManager
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.remote.LlmClient
import com.storybrain.app.data.remote.LlmClientFactory

/**
 * Application 入口：进程级单例持有者。
 * - [settingsManager] / [bookRepository] 用 `by lazy` 延迟初始化，全局共享同一实例
 *   （BookRepository 内部的 LruCache / 记忆盘缓存因此全应用复用）
 * - [makeLlmClient] 供流水线 Worker 调用，每次返回新客户端实例但底层 OkHttpClient 复用单例
 */
class StoryBrainApp : Application() {

    val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    val bookRepository: BookRepository by lazy { BookRepository(this) }

    /** 根据当前设置构造 LLM 客户端；未配置 key 返回 null */
    fun makeLlmClient(): LlmClient? {
        val cfg = settingsManager.loadLlmConfig()
        if (cfg.apiKey.isBlank()) return null
        return LlmClientFactory.create(cfg)
    }
}
