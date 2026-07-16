package com.storybrain.app.data

import android.content.Context
import android.content.SharedPreferences
import com.storybrain.app.data.remote.LlmConfig
import com.storybrain.app.data.remote.WebDavConfig

/** 全局设置（LLM 配置等），存 SharedPreferences */
class SettingsManager(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("story_brain_settings", Context.MODE_PRIVATE)

    fun loadLlmConfig(): LlmConfig {
        val provider = sp.getString(KEY_PROVIDER, LlmConfig.PROVIDER_DEEPSEEK)!!
        return when (provider) {
            LlmConfig.PROVIDER_GEMINI -> LlmConfig.GEMINI_DEFAULT.copy(
                apiKey = sp.getString(KEY_API_KEY, "")!!,
                model = sp.getString(KEY_MODEL, LlmConfig.GEMINI_DEFAULT.model)!!,
                baseUrl = sp.getString(KEY_BASE_URL, LlmConfig.GEMINI_DEFAULT.baseUrl)!!
            )
            else -> LlmConfig.DEEPSEEK_DEFAULT.copy(
                apiKey = sp.getString(KEY_API_KEY, "")!!,
                model = sp.getString(KEY_MODEL, LlmConfig.DEEPSEEK_DEFAULT.model)!!,
                baseUrl = sp.getString(KEY_BASE_URL, LlmConfig.DEEPSEEK_DEFAULT.baseUrl)!!
            )
        }
    }

    fun saveLlmConfig(config: LlmConfig) {
        sp.edit().apply {
            putString(KEY_PROVIDER, config.provider)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_MODEL, config.model)
            putString(KEY_BASE_URL, config.baseUrl)
            apply()
        }
    }

    fun hasApiKey(): Boolean = !sp.getString(KEY_API_KEY, "").isNullOrEmpty()

    // —— 阅读进度记忆（每本书上次读到第几章）——
    fun loadReadingProgress(bookId: String): Int =
        sp.getInt("progress_$bookId", 0)

    fun saveReadingProgress(bookId: String, chapterIndex: Int) {
        sp.edit().putInt("progress_$bookId", chapterIndex).apply()
    }

    // —— 最近阅读时间（用于书架排序：最近阅读排在前面）——
    fun loadLastReadTime(bookId: String): Long =
        sp.getLong("last_read_$bookId", 0L)

    fun saveLastReadTime(bookId: String, timeMs: Long) {
        sp.edit().putLong("last_read_$bookId", timeMs).apply()
    }

    // —— 阅读器偏好 ——
    fun loadNightMode(): Boolean = sp.getBoolean(KEY_NIGHT_MODE, false)
    fun saveNightMode(on: Boolean) { sp.edit().putBoolean(KEY_NIGHT_MODE, on).apply() }

    /** 字号缩放倍数，1.0 = 默认。范围 0.8 ~ 1.6 */
    fun loadFontScale(): Float = sp.getFloat(KEY_FONT_SCALE, 1.0f)
    fun saveFontScale(scale: Float) { sp.edit().putFloat(KEY_FONT_SCALE, scale).apply() }

    // —— AI 深度去广告（默认关，开启后每章多一次 LLM 调用）——
    fun loadAiCleanEnabled(): Boolean = sp.getBoolean(KEY_AI_CLEAN, false)
    fun saveAiCleanEnabled(on: Boolean) { sp.edit().putBoolean(KEY_AI_CLEAN, on).apply() }

    // —— WebDAV 备份配置 ——
    fun loadWebDavConfig(): WebDavConfig {
        return WebDavConfig(
            serverUrl = sp.getString(KEY_WEBDAV_URL, "")!!,
            username = sp.getString(KEY_WEBDAV_USER, "")!!,
            password = sp.getString(KEY_WEBDAV_PASS, "")!!,
            remotePath = sp.getString(KEY_WEBDAV_PATH, "/StoryBrain")!!
        )
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        sp.edit().apply {
            putString(KEY_WEBDAV_URL, config.serverUrl)
            putString(KEY_WEBDAV_USER, config.username)
            putString(KEY_WEBDAV_PASS, config.password)
            putString(KEY_WEBDAV_PATH, config.remotePath)
            apply()
        }
    }

    // —— TTS 语音服务器配置 ——
    fun loadTtsServerUrl(): String = sp.getString("tts_server_url", "http://10.0.2.2:18083")!!
    fun saveTtsServerUrl(url: String) { sp.edit().putString("tts_server_url", url).apply() }

    fun loadTtsMode(): String = sp.getString("tts_mode", "system")!!
    fun saveTtsMode(mode: String) { sp.edit().putString("tts_mode", mode).apply() }

    fun loadTtsSpeechInterval(): Int = sp.getInt("tts_speech_interval", 0)
    fun saveTtsSpeechInterval(ms: Int) { sp.edit().putInt("tts_speech_interval", ms).apply() }


    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_AI_CLEAN = "ai_clean"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_WEBDAV_PATH = "webdav_path"
    }
}
