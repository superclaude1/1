package com.storybrain.app.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.remote.WebDavBackupService
import com.storybrain.app.data.remote.WebDavClient
import com.storybrain.app.data.remote.WebDavConfig
import com.storybrain.app.data.remote.LlmConfig
import com.storybrain.app.databinding.ActivitySettingsBinding
import com.storybrain.app.pipeline.PipelineWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 设置页：承载三类配置，分区管理。
 *  1. LLM 配置：provider 单选(DeepSeek/Gemini) + apiKey/model/baseUrl + 连接测试
 *  2. 阅读偏好：AI 深度去广告开关（默认关，开启后每章多一次 LLM 调用）
 *  3. WebDAV 备份：服务器配置 + 连接测试 + 整库备份/恢复
 * 所有配置通过 [com.storybrain.app.data.SettingsManager] 持久化到 SharedPreferences。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "LLM 设置"

        val app = application as StoryBrainApp
        val cfg = app.settingsManager.loadLlmConfig()

        // —— LLM 配置区 ——
        // provider 选择
        when (cfg.provider) {
            LlmConfig.PROVIDER_GEMINI -> binding.radioGemini.isChecked = true
            else -> binding.radioDeepseek.isChecked = true
        }
        binding.editApiKey.setText(cfg.apiKey)
        binding.editModel.setText(cfg.model)
        binding.editBaseUrl.setText(cfg.baseUrl)

        applyProviderHint(cfg.provider)

        binding.radioGroupProvider.setOnCheckedChangeListener { _, id ->
            val p = when (id) {
                binding.radioGemini.id -> LlmConfig.PROVIDER_GEMINI
                else -> LlmConfig.PROVIDER_DEEPSEEK
            }
            applyProviderHint(p)
            // 切换时填默认 model / baseUrl
            val def = if (p == LlmConfig.PROVIDER_GEMINI) LlmConfig.GEMINI_DEFAULT else LlmConfig.DEEPSEEK_DEFAULT
            if (binding.editModel.text.isNullOrBlank()) binding.editModel.setText(def.model)
            if (binding.editBaseUrl.text.toString() == def.baseUrl || binding.editBaseUrl.text.isNullOrBlank()) {
                binding.editBaseUrl.setText(def.baseUrl)
            }
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }
        binding.btnCheckModels.setOnClickListener { checkModels() }

        // —— 阅读偏好区 ——
        // AI 去广告开关
        binding.switchAiClean.isChecked = app.settingsManager.loadAiCleanEnabled()
        binding.switchAiClean.setOnCheckedChangeListener { _, isChecked ->
            app.settingsManager.saveAiCleanEnabled(isChecked)
        }

        // —— WebDAV 备份区 ——
        // WebDAV 配置初始化
        val davCfg = app.settingsManager.loadWebDavConfig()
        binding.editWebDavUrl.setText(davCfg.serverUrl)
        binding.editWebDavUser.setText(davCfg.username)
        binding.editWebDavPass.setText(davCfg.password)
        binding.editWebDavPath.setText(davCfg.remotePath)

        binding.btnWebDavTest.setOnClickListener { testWebDav() }
        binding.btnWebDavSave.setOnClickListener { saveWebDav() }
        binding.btnBackup.setOnClickListener { backupAll() }
        binding.btnRestore.setOnClickListener { restoreFromCloud() }

        // —— MOSS-TTS 语音配置区 ——
        val ttsMode = app.settingsManager.loadTtsMode()
        when (ttsMode) {
            "system" -> {
                binding.rbTtsSystem.isChecked = true
                binding.layoutTtsServerConfig.visibility = android.view.View.GONE
            }
            "edge" -> {
                binding.rbTtsEdge.isChecked = true
                binding.layoutTtsServerConfig.visibility = android.view.View.GONE
            }
            else -> {
                binding.rbTtsServer.isChecked = true
                binding.layoutTtsServerConfig.visibility = android.view.View.VISIBLE
            }
        }

        binding.rgTtsEngine.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbTtsServer.id) {
                binding.layoutTtsServerConfig.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutTtsServerConfig.visibility = android.view.View.GONE
            }
        }

        binding.editTtsServer.setText(app.settingsManager.loadTtsServerUrl())
        binding.btnTtsSave.setOnClickListener {
            val mode = when {
                binding.rbTtsSystem.isChecked -> "system"
                binding.rbTtsEdge.isChecked -> "edge"
                else -> "server"
            }
            app.settingsManager.saveTtsMode(mode)
            if (mode == "server") {
                val url = binding.editTtsServer.text.toString().trim()
                if (url.isNotEmpty()) {
                    app.settingsManager.saveTtsServerUrl(url)
                    binding.ttsStatusText.text = "配置已保存 ✓"
                } else {
                    binding.ttsStatusText.text = "地址不能为空"
                }
            }
        }

        // —— 句间停顿调节 ——
        val currentInterval = app.settingsManager.loadTtsSpeechInterval()
        binding.sbTtsInterval.progress = currentInterval
        binding.textTtsInterval.text = "句间停顿: $currentInterval 毫秒"
        binding.sbTtsInterval.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.textTtsInterval.text = "句间停顿: $progress 毫秒"
                app.settingsManager.saveTtsSpeechInterval(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }


    private fun applyProviderHint(provider: String) {
        if (provider == LlmConfig.PROVIDER_GEMINI) {
            binding.editModel.hint = "gemini-1.5-flash / gemini-1.5-pro"
            binding.editBaseUrl.hint = "https://generativelanguage.googleapis.com"
        } else {
            binding.editModel.hint = "deepseek-chat / deepseek-reasoner"
            binding.editBaseUrl.hint = "https://api.deepseek.com"
        }
    }

    private fun currentConfig(): LlmConfig {
        val provider = if (binding.radioGemini.isChecked) LlmConfig.PROVIDER_GEMINI else LlmConfig.PROVIDER_DEEPSEEK
        val def = if (provider == LlmConfig.PROVIDER_GEMINI) LlmConfig.GEMINI_DEFAULT else LlmConfig.DEEPSEEK_DEFAULT
        return LlmConfig(
            provider = provider,
            apiKey = binding.editApiKey.text.toString().trim(),
            model = binding.editModel.text.toString().trim().ifEmpty { def.model },
            baseUrl = binding.editBaseUrl.text.toString().trim().ifEmpty { def.baseUrl }
        )
    }

    private fun save() {
        val app = (application as StoryBrainApp)
        val cfg = currentConfig()
        app.settingsManager.saveLlmConfig(cfg)
        binding.statusText.text = "已保存"

        // 保存配置后，自动为未分析完的书籍触发后台分析流水线
        if (cfg.apiKey.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                app.bookRepository.books().forEach { bookDir ->
                    val bookId = bookDir.name
                    val brain = app.bookRepository.loadBrain(bookId)
                    val chapters = app.bookRepository.loadChapterIndex(bookId)
                    val processed = brain?.processedChapterCount ?: 0
                    if (processed < chapters.size) {
                        PipelineWorker.ensureAhead(this@SettingsActivity, bookId)
                    }
                }
            }
        }
    }

    private fun checkModels() {
        val apiKey = binding.editApiKey.text.toString().trim()
        val baseUrl = binding.editBaseUrl.text.toString().trim()
        val provider = if (binding.radioGemini.isChecked) LlmConfig.PROVIDER_GEMINI else LlmConfig.PROVIDER_DEEPSEEK

        if (apiKey.isBlank()) {
            binding.statusText.text = "请填写 API Key"
            return
        }
        if (baseUrl.isBlank()) {
            binding.statusText.text = "请填写 Base URL"
            return
        }
        binding.statusText.text = "获取模型列表中…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = com.storybrain.app.data.remote.OpenAiCompatClient.sharedHttp
                    if (provider == LlmConfig.PROVIDER_GEMINI) {
                        val url = "${baseUrl.trimEnd('/')}/v1beta/models?key=$apiKey"
                        val req = okhttp3.Request.Builder().url(url).get().build()
                        client.newCall(req).execute().use { resp ->
                            val raw = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}: ${raw.take(300)}")
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
                            val modelsArray = root["models"]?.jsonArray
                            val models = mutableListOf<String>()
                            modelsArray?.forEach { el ->
                                el.jsonObject["name"]?.jsonPrimitive?.content?.let { name ->
                                    models.add(name.removePrefix("models/"))
                                }
                            }
                            models.sorted()
                        }
                    } else {
                        val baseUrlClean = baseUrl.trimEnd('/')
                        val url = if (baseUrlClean.endsWith("/v1")) {
                            "$baseUrlClean/models"
                        } else if (baseUrlClean.endsWith("/v1/models")) {
                            baseUrlClean
                        } else {
                            "$baseUrlClean/v1/models"
                        }
                        val req = okhttp3.Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer $apiKey")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val raw = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${raw.take(300)}")
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
                            val dataArray = root["data"]?.jsonArray
                            val models = mutableListOf<String>()
                            dataArray?.forEach { el ->
                                el.jsonObject["id"]?.jsonPrimitive?.content?.let { id ->
                                    models.add(id)
                                }
                            }
                            models.sorted()
                        }
                    }
                }
            }

            result.onSuccess { models ->
                if (models.isEmpty()) {
                    binding.statusText.text = "获取成功，但未发现可用模型"
                } else {
                    binding.statusText.text = "获取成功 ✓"
                    showModelSelector(models)
                }
            }.onFailure { e ->
                binding.statusText.text = "获取失败: ${e.message}"
            }
        }
    }

    private fun showModelSelector(models: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(models.toTypedArray()) { _, which ->
                binding.editModel.setText(models[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun test() {
        val app = application as StoryBrainApp
        val cfg = currentConfig()
        app.settingsManager.saveLlmConfig(cfg)
        binding.statusText.text = "测试中…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val client = com.storybrain.app.data.remote.LlmClientFactory.create(cfg)
                client.chatJson("你是测试助手，只输出 JSON。", "请输出 {\"ok\":true}")
                    .map { it.contains("true", ignoreCase = true) }
                    .getOrDefault(false)
            }
            binding.statusText.text = if (ok) "连接成功 ✓" else "连接失败，请检查 Key/网络"
        }
    }

    // —— WebDAV 相关 ——

    private fun currentWebDavConfig(): WebDavConfig {
        return WebDavConfig(
            serverUrl = binding.editWebDavUrl.text.toString().trim(),
            username = binding.editWebDavUser.text.toString().trim(),
            password = binding.editWebDavPass.text.toString().trim(),
            remotePath = binding.editWebDavPath.text.toString().trim().ifEmpty { "/StoryBrain" }
        )
    }

    private fun testWebDav() {
        val cfg = currentWebDavConfig()
        if (!cfg.isValid()) {
            binding.webdavStatusText.text = "请填写服务器地址"
            return
        }
        binding.webdavStatusText.text = "测试中…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                WebDavClient(cfg).testConnection().isSuccess
            }
            binding.webdavStatusText.text = if (ok) "连接成功 ✓" else "连接失败，请检查配置"
        }
    }

    private fun saveWebDav() {
        (application as StoryBrainApp).settingsManager.saveWebDavConfig(currentWebDavConfig())
        binding.webdavStatusText.text = "已保存"
    }

    private fun backupAll() {
        val cfg = currentWebDavConfig()
        if (!cfg.isValid()) {
            binding.webdavStatusText.text = "请先配置 WebDAV"
            return
        }
        binding.webdavStatusText.text = "备份中…"
        lifecycleScope.launch {
            val app = application as StoryBrainApp
            val res = withContext(Dispatchers.IO) {
                WebDavBackupService(this@SettingsActivity, app.bookRepository).backupAll(cfg)
            }
            binding.webdavStatusText.text = res.getOrElse { "备份失败: ${it.message}" }
        }
    }

    private fun restoreFromCloud() {
        val cfg = currentWebDavConfig()
        if (!cfg.isValid()) {
            binding.webdavStatusText.text = "请先配置 WebDAV"
            return
        }
        binding.webdavStatusText.text = "获取备份列表…"
        lifecycleScope.launch {
            val app = application as StoryBrainApp
            val backups = withContext(Dispatchers.IO) {
                WebDavBackupService(this@SettingsActivity, app.bookRepository).listBackups(cfg)
            }
            if (backups.isFailure) {
                binding.webdavStatusText.text = "获取列表失败: ${backups.exceptionOrNull()?.message}"
                return@launch
            }
            val list = backups.getOrThrow()
            if (list.isEmpty()) {
                binding.webdavStatusText.text = "云端暂无备份"
                return@launch
            }
            showBackupPicker(list)
        }
    }

    private fun showBackupPicker(backups: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("选择备份文件")
            .setItems(backups.toTypedArray()) { _, which ->
                val selected = backups[which]
                binding.webdavStatusText.text = "恢复中…"
                // 恢复前先停掉所有书的后台流水线，避免 Worker 写回旧数据覆盖刚恢复的记忆盘
                val app = application as StoryBrainApp
                app.bookRepository.books().forEach { book ->
                    com.storybrain.app.pipeline.PipelineWorker.stop(this, book.name)
                }
                lifecycleScope.launch {
                    val cfg = currentWebDavConfig()
                    val res = withContext(Dispatchers.IO) {
                        WebDavBackupService(this@SettingsActivity, app.bookRepository).restore(cfg, selected)
                    }
                    binding.webdavStatusText.text = res.getOrElse { "恢复失败: ${it.message}" }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
