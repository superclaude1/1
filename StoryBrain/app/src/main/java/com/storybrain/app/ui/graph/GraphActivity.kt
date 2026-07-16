package com.storybrain.app.ui.graph

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.model.AppJson
import com.storybrain.app.data.model.StoryBrain
import com.storybrain.app.databinding.ActivityGraphBinding
import com.storybrain.app.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/** 浏览“故事大脑”：使用 WebView 展示由 ECharts 渲染的可交互剧情树和内嵌局部人物网 */
class GraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraphBinding
    private var storyBrainJson: String = ""
    private var bookTitle: String = ""

    /** 当前书 ID，从 Intent 取 */
    private val bookId: String by lazy {
        intent.getStringExtra(ReaderActivity.EXTRA_BOOK_ID).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "故事大脑"

        val repo = (application as StoryBrainApp).bookRepository

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val b = withContext(Dispatchers.IO) { repo.loadBrain(bookId) }
            binding.progressBar.visibility = View.GONE
            if (b == null) {
                binding.emptyText.visibility = View.VISIBLE
                binding.webView.visibility = View.GONE
                binding.emptyText.text = "记忆盘尚未生成。请在设置中配置 LLM 后，等待后台流水线分析。"
            } else {
                binding.emptyText.visibility = View.GONE
                bookTitle = b.bookTitle.ifBlank { "未命名书籍" }
                // 异步在 IO 线程序列化 JSON，防止大 JSON 序列化时卡死主线程
                storyBrainJson = withContext(Dispatchers.IO) {
                    // 添加 bookId 以供导入 Neo4j 使用
                    val extendedBrainMap = AppJson.encodeToJsonElement(StoryBrain.serializer(), b)
                        .let { elem ->
                            if (elem is kotlinx.serialization.json.JsonObject) {
                                val map = elem.toMutableMap()
                                map["bookId"] = kotlinx.serialization.json.JsonPrimitive(bookId)
                                kotlinx.serialization.json.JsonObject(map)
                            } else {
                                elem
                            }
                        }
                    AppJson.encodeToString(extendedBrainMap)
                }
                setupWebView()
            }
        }
    }

    private fun setupWebView() {
        binding.webView.visibility = View.VISIBLE
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 允许 Chrome 调试 WebView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 注入 Javascript 接口
        binding.webView.addJavascriptInterface(AndroidInterface(), "Android")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
            }
        }

        binding.webView.loadUrl("file:///android_asset/graph_view.html")
    }

    private fun isSystemDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /** Javascript 接口供 H5 页面调用 */
    inner class AndroidInterface {
        @JavascriptInterface
        fun getStoryBrainJson(): String {
            return storyBrainJson
        }

        @JavascriptInterface
        fun isDarkMode(): Boolean {
            return isSystemDarkMode()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.storybrain.app.R.menu.graph, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == com.storybrain.app.R.id.action_export) {
            exportStoryBrainJson()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun exportStoryBrainJson() {
        if (storyBrainJson.isBlank()) {
            Toast.makeText(this, "暂无可导出的数据", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("story_brain_json", storyBrainJson)
        clipboard.setPrimaryClip(clip)

        var exportMsg = "已复制 JSON 到剪贴板！"

        // 2. 优先尝试写入系统公共下载目录（Legacy模式或模拟器环境下能直接访问）
        var fileSaved = false
        try {
            val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDownloadDir != null && (publicDownloadDir.exists() || publicDownloadDir.mkdirs())) {
                val file = File(publicDownloadDir, "${bookTitle}_story_brain.json")
                file.writeText(storyBrainJson, Charsets.UTF_8)
                exportMsg += "\n已保存到系统下载目录: ${file.absolutePath}"
                fileSaved = true
            }
        } catch (e: Exception) {
            // 写入公共目录失败，静默降级到外部私有目录
        }

        // 3. 降级方案：保存到外部私有下载目录（无需任何权限申请，100%成功）
        if (!fileSaved) {
            try {
                val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null) {
                    val file = File(downloadDir, "${bookTitle}_story_brain.json")
                    file.writeText(storyBrainJson, Charsets.UTF_8)
                    exportMsg += "\n已保存到外部私有目录: ${file.absolutePath}"
                }
            } catch (e: Exception) {
                exportMsg += "\n文件保存失败: ${e.message}"
            }
        }

        Toast.makeText(this, exportMsg, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
