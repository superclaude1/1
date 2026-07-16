package com.storybrain.app.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.local.ChapterSplitter
import com.storybrain.app.databinding.ActivityImportBinding
import com.storybrain.app.pipeline.PipelineWorker
import com.storybrain.app.ui.reader.ReaderActivity
import android.provider.OpenableColumns
import com.storybrain.app.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架主页：展示所有已导入书籍，按最近阅读排序。
 *  - 卡片式布局：书名首字色块 + 阅读进度 + 分析进度条
 *  - FAB 按钮导入 txt → 本地章节切片 → 入库 → 进入阅读器并启动后台流水线
 *  - 也接收外部"用本应用打开 txt"的 VIEW intent
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var repo: BookRepository
    private lateinit var bookAdapter: BookListAdapter

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { importFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = (application as StoryBrainApp).bookRepository
        val app = application as StoryBrainApp

        bookAdapter = BookListAdapter(
            onClick = { openReader(it) },
            onLongClick = { confirmDelete(it) }
        )
        binding.bookListRecycler.layoutManager = LinearLayoutManager(this)
        binding.bookListRecycler.adapter = bookAdapter

        binding.btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("text/plain", "application/octet-stream"))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 外部直接打开 txt
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { importFromUri(it) }

        refreshBookList()
    }

    override fun onResume() {
        super.onResume()
        // 从阅读器返回时刷新书架（阅读进度/分析进度可能已变）
        refreshBookList()
    }

    private fun importFromUri(uri: Uri) {
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = "正在读取文件…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                    val text = com.storybrain.app.data.local.EncodingDetector.decode(bytes)
                    require(com.storybrain.app.data.local.EncodingDetector.looksLikeChinese(text) || text.length > 100) {
                        "解码后内容异常，可能不是文本文件"
                    }
                    val displayName = getFileName(uri)
                    // 去重检测：同一文件名不重复导入
                    val existing = repo.findBookByTitle(displayName)
                    if (existing != null) {
                        return@runCatching existing to -1 // -1 标记为已存在
                    }
                    val bookId = repo.createBookId(displayName)
                    repo.setBookTitle(bookId, displayName)
                    val chapters = ChapterSplitter.split(text)
                    require(chapters.isNotEmpty()) { "未识别到章节，请检查文件格式" }
                    // 本地启发式去广告（零成本，始终启用）
                    val cleaned = chapters.map { ch ->
                        val res = com.storybrain.app.data.local.AdRemover.cleanChapter(ch.rawText)
                        ch.copy(rawText = res.cleanText)
                    }
                    repo.saveChapters(bookId, cleaned)
                    bookId to cleaned.size
                }
            }
            result.onSuccess { (bookId, n) ->
                if (n == -1) {
                    // 已存在同名书，直接询问是否打开
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("已存在同名书籍")
                        .setMessage("书架中已有同名书籍，是否直接打开？")
                        .setPositiveButton("打开") { _, _ -> openReader(bookId) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    binding.statusText.text = "导入完成：共 $n 章"
                    val app = application as StoryBrainApp
                    if (app.settingsManager.hasApiKey()) {
                        PipelineWorker.ensureAhead(this@ImportActivity, bookId)
                        openReader(bookId)
                    } else {
                        AlertDialog.Builder(this@ImportActivity)
                            .setTitle("未配置 LLM")
                            .setMessage("已导入 $n 章，本地已可阅读对话/旁白初分视图。\n要获得「故事大脑」增量分析，请先配置 DeepSeek/Gemini API Key。\n是否现在去配置？")
                            .setPositiveButton("去配置") { _, _ ->
                                openReader(bookId)
                                startActivity(Intent(this@ImportActivity, SettingsActivity::class.java))
                            }
                            .setNegativeButton("先阅读") { _, _ -> openReader(bookId) }
                            .show()
                    }
                }
                refreshBookList()
            }.onFailure { e ->
                binding.statusText.visibility = android.view.View.VISIBLE
                binding.statusText.text = "导入失败: ${e.message}"
            }
        }
    }

    private fun openReader(bookId: String) {
        startActivity(Intent(this, BookDetailActivity::class.java).putExtra(BookDetailActivity.EXTRA_BOOK_ID, bookId))
    }

    private fun confirmDelete(bookId: String) {
        val title = repo.bookTitle(bookId)
        AlertDialog.Builder(this)
            .setTitle("删除书籍")
            .setMessage("确认删除《$title》及其所有分析数据？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                PipelineWorker.stop(this, bookId)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteBook(bookId) }
                    refreshBookList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (result == null) {
            val path = uri.path
            if (path != null) {
                val cut = path.lastIndexOf('/')
                result = if (cut != -1) path.substring(cut + 1) else path
            }
        }
        val name = result ?: "导入小说"
        // 解码 URL 编码的字符以防乱码（例如 %E4%B8%89%E5%9C%8B%E6%BC%94%E7%BE%A9）
        val decoded = try {
            java.net.URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
        }
        // 去除 .txt 后缀（不区分大小写）
        return decoded.replace(Regex("(?i)\\.txt$"), "")
    }

    private fun refreshBookList() {
        lifecycleScope.launch {
            val app = application as StoryBrainApp
            val settings = app.settingsManager
            val entries = withContext(Dispatchers.IO) {
                repo.books().map { dir ->
                    val bookId = dir.name
                    val title = repo.bookTitle(bookId)
                    val chapterCount = repo.loadChapterIndex(bookId).size
                    val readChapter = settings.loadReadingProgress(bookId)
                    val analyzedChapter = repo.loadBrain(bookId)?.processedChapterCount ?: 0
                    val lastReadTime = settings.loadLastReadTime(bookId)
                    BookShelfItem(bookId, title, chapterCount, readChapter, analyzedChapter, lastReadTime)
                }.sortedByDescending { it.lastReadTime } // 最近阅读排前面
            }
            bookAdapter.submit(entries)
            // 渲染顶部统计仪表盘
            val totalBooks = entries.size
            val totalChapters = entries.sumOf { it.chapters }
            val totalAnalyzed = entries.sumOf { it.analyzedChapter }
            if (totalBooks > 0) {
                binding.statsCard.visibility = android.view.View.VISIBLE
                binding.tvTotalBooks.text = totalBooks.toString()
                binding.tvTotalChapters.text = totalChapters.toString()
                binding.tvTotalAnalyzed.text = totalAnalyzed.toString()
            } else {
                binding.statsCard.visibility = android.view.View.GONE
            }
            // 空书架提示
            binding.emptyView.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.bookListRecycler.visibility = if (entries.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }
}
