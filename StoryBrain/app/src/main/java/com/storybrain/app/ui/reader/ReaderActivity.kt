package com.storybrain.app.ui.reader

import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.storybrain.app.R
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.local.DialogueSeparator
import com.storybrain.app.data.local.TtsManager
import com.storybrain.app.data.model.Chapter
import com.storybrain.app.data.model.GlobalCharacterRegistry
import com.storybrain.app.data.model.ChatItem
import com.storybrain.app.databinding.ActivityReaderBinding
import com.storybrain.app.pipeline.PipelineWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微信风格阅读器：
 *  - 顶部：书名 + 当前章 + 流水线进度
 *  - 中部：RecyclerView 渲染 对话(左)/旁白(中)/章节分隔(居中胶囊)
 *  - 底部：上一章 / 章节选择 / 下一章 / 字号 / 夜间
 *  打开即恢复上次阅读进度，并启动后台预加载流水线（Step5 无感预加载）。
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var repo: com.storybrain.app.data.local.BookRepository
    private lateinit var adapter: ChatAdapter
    private lateinit var settings: com.storybrain.app.data.SettingsManager
    private lateinit var ttsManager: TtsManager

    private var bookId: String = ""
    private var chapters: List<Chapter> = emptyList()
    private var currentIndex = 0
    /** 当前章已分析完成的版本号，用于避免重复刷新 */
    private var currentChapterAnalysisVersion = 0L
    private var lastSeenChapterDone = -1
    private var refreshJob: Job? = null
    private var ensureAheadJob: Job? = null

    // 全文朗读状态管理
    private var isPlayingFullText = false
    private var currentPlayItemIndex = -1
    private var playFullMenuItem: MenuItem? = null
    private var playRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as StoryBrainApp
        repo = app.bookRepository
        settings = app.settingsManager
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (bookId.isEmpty()) { finish(); return }

        ttsManager = TtsManager(this).apply {
            onSpeakCompleteListener = {
                if (isPlayingFullText) {
                    playNextItem()
                }
            }
        }

        adapter = ChatAdapter(
            onAvatarClick = { charId ->
                startActivity(android.content.Intent(this, com.storybrain.app.ui.character.CharacterDetailActivity::class.java).apply {
                    putExtra(com.storybrain.app.ui.character.CharacterDetailActivity.EXTRA_BOOK_ID, bookId)
                    putExtra(com.storybrain.app.ui.character.CharacterDetailActivity.EXTRA_CHAR_ID, charId)
                })
            },
            onBubbleClick = { item ->
                if (isPlayingFullText) {
                    // 全文播放状态下，点击代表跳转到该行继续播放
                    val idx = adapter.currentItems.indexOf(item)
                    if (idx != -1) {
                        currentPlayItemIndex = idx - 1
                        playNextItem()
                    }
                } else {
                    ttsManager.speakCharacter(item.speakerId, item.speakerName, item.content)
                }
            }
        ).apply {
            nightMode = settings.loadNightMode()
            fontScale = settings.loadFontScale()
        }
        binding.chatRecycler.layoutManager = LinearLayoutManager(this)
        binding.chatRecycler.adapter = adapter
        binding.chatRecycler.setOnClickListener { toggleToolbars() }
        applyTheme()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadBook()
        bindControls()
        observePipeline()

        // 启动后台无感预加载（Step5）— 用 KEEP 策略，已有任务在跑则不重复启动
        PipelineWorker.ensureAhead(this, bookId, aheadCount = 30)
    }

    private fun loadBook() {
        lifecycleScope.launch {
            val (chs, title) = withContext(Dispatchers.IO) {
                val index = repo.loadChapterIndex(bookId)
                // 只加载章索引（id+title），rawText 留空，按需懒加载（命中 LruCache）
                val list = index.map { (id, t) ->
                    Chapter(
                        index = id.removePrefix("ch_").toIntOrNull() ?: 0,
                        id = id, title = t,
                        rawText = ""
                    )
                }.sortedBy { it.index }
                list to repo.bookTitle(bookId)
            }
            chapters = chs
            supportActionBar?.title = title
            if (chs.isNotEmpty()) {
                // 搜索跳转优先，否则恢复上次阅读进度
                val target = intent.getIntExtra(
                    com.storybrain.app.ui.search.SearchActivity.EXTRA_TARGET_INDEX, -1
                )
                val start = if (target in chs.indices) target
                            else settings.loadReadingProgress(bookId).coerceIn(0, chs.size - 1)
                showChapter(start)
            }
        }
    }

    private fun showChapter(index: Int) {
        if (index !in chapters.indices) return
        currentIndex = index
        currentChapterAnalysisVersion = System.nanoTime() // 重置版本，强制刷新
        val ch = chapters[index]
        binding.chapterLabel.text = "第 ${index + 1} / ${chapters.size} 章 · ${ch.title}"
        binding.readProgress.progress = ((index + 1).toFloat() / chapters.size * 100).toInt()
        settings.saveReadingProgress(bookId, index)
        settings.saveLastReadTime(bookId, System.currentTimeMillis())
        binding.pipelineStatus.text = "加载中…"

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    // 按需懒加载单章正文（命中 LruCache，避免全书常驻内存）
                    val loaded = if (ch.rawText.isEmpty()) ch.copy(rawText = repo.loadChapterText(bookId, ch.id))
                                 else ch
                    val filled = DialogueSeparator.fill(loaded)
                    val analysis = repo.loadChapterAnalysis(bookId, ch.id)
                    val brain = repo.loadBrain(bookId)
                    val registry = brain?.globalRegistry ?: GlobalCharacterRegistry()
                    ChatItemBuilder.build(
                        chapter = filled,
                        dialogueJson = analysis?.first,
                        narrationText = analysis?.second,
                        registry = registry
                    )
                }
                adapter.submit(items) {
                    binding.chatRecycler.scrollToPosition(0)
                    binding.btnPrev.isEnabled = index > 0
                    binding.btnNext.isEnabled = index < chapters.size - 1
                    if (isPlayingFullText) {
                        playNextItem()
                    }
                }
            } finally {
                // 无论正常完成还是被 cancel（快速翻页），都清空"加载中…"，
                // 避免 pipeline 无新进度回调时残留。observePipeline 后续回调会重设。
                binding.pipelineStatus.text = ""
            }
        }
        ensureAhead()
    }

    /**
     * 无感预加载续跑：检查记忆盘已处理章数是否覆盖当前阅读位置 + aheadCount，
     * 不足则用 KEEP 策略触发 Worker 续跑（已有任务在跑则不重复启动）。
     * 有未完成的检查时先取消上一个，避免快速翻页堆积多个协程。
     */
    private fun ensureAhead() {
        val aheadCount = 30
        val need = (currentIndex + 1 + aheadCount).coerceAtMost(chapters.size)
        ensureAheadJob?.cancel()
        ensureAheadJob = lifecycleScope.launch {
            val (processed, isRunning) = withContext(Dispatchers.IO) {
                val p = repo.loadBrain(bookId)?.processedChapterCount ?: 0
                val wm = androidx.work.WorkManager.getInstance(this@ReaderActivity)
                val works = wm.getWorkInfosForUniqueWork(PipelineWorker.uniqueName(bookId)).get()
                p to works.any { it.state == androidx.work.WorkInfo.State.RUNNING }
            }
            if (processed < need && !isRunning) {
                PipelineWorker.ensureAhead(this@ReaderActivity, bookId, aheadCount)
            }
        }
    }

    /**
     * 仅当当前章节“尚未分析”而后台刚好把它分析完时，才刷新一次。
     * 修复原 bug：每次进度回调都重复刷新当前章，造成卡顿与列表跳动。
     */
    private fun maybeRefreshCurrent() {
        if (lastSeenChapterDone < currentIndex + 1) return
        // 用版本号避免对同一状态多次刷新
        val now = System.nanoTime()
        if (now - currentChapterAnalysisVersion < 1_000_000_000L) return // 1s 内不重复
        showChapter(currentIndex)
    }

    private fun bindControls() {
        binding.btnPrev.setOnClickListener { stopPlayingFullText(); showChapter(currentIndex - 1) }
        binding.btnNext.setOnClickListener { stopPlayingFullText(); showChapter(currentIndex + 1) }
        binding.btnChapter.setOnClickListener {
            ChapterPickerDialog(chapters, currentIndex) {
                stopPlayingFullText()
                showChapter(it)
            }
                .show(supportFragmentManager, "picker")
        }
        binding.btnFontDown.setOnClickListener {
            val s = (settings.loadFontScale() - 0.1f).coerceAtLeast(0.8f)
            settings.saveFontScale(s); adapter.fontScale = s
        }
        binding.btnFontUp.setOnClickListener {
            val s = (settings.loadFontScale() + 0.1f).coerceAtMost(1.6f)
            settings.saveFontScale(s); adapter.fontScale = s
        }
        binding.btnNight.setOnClickListener {
            val on = !settings.loadNightMode()
            settings.saveNightMode(on)
            adapter.nightMode = on
            applyTheme()
        }
    }

    private fun applyTheme() {
        val t = adapter.themeColors()
        binding.rootView.setBackgroundResource(t.bg)
        binding.toolbar.setBackgroundResource(t.titleBar)
        binding.bottomBar.setBackgroundResource(t.titleBar)
        binding.bottomDivider.setBackgroundResource(t.divider)
        binding.chapterLabel.setTextColor(getColor(t.hint))
        binding.btnNight.text = if (settings.loadNightMode()) "白天" else "夜间"
    }

    private var barsVisible = true

    /** 点击正文区域切换工具栏显隐（沉浸式阅读） */
    private fun toggleToolbars() {
        barsVisible = !barsVisible
        val anim = android.view.animation.AlphaAnimation(
            if (barsVisible) 0f else 1f,
            if (barsVisible) 1f else 0f
        ).apply { duration = 200 }
        if (barsVisible) {
            binding.toolbar.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            binding.bottomDivider.visibility = View.VISIBLE
        } else {
            binding.toolbar.startAnimation(anim)
            binding.bottomBar.startAnimation(anim)
            binding.bottomDivider.startAnimation(anim)
            binding.toolbar.postDelayed({ binding.toolbar.visibility = View.INVISIBLE }, 200)
            binding.bottomBar.postDelayed({ binding.bottomBar.visibility = View.INVISIBLE }, 200)
            binding.bottomDivider.postDelayed({ binding.bottomDivider.visibility = View.INVISIBLE }, 200)
        }
    }

    /** 观察后台流水线进度，刷新顶部状态条 */
    private fun observePipeline() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(PipelineWorker.uniqueName(bookId))
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                val stage = info.progress.getString(PipelineWorker.KEY_STAGE)
                val ch = info.progress.getInt(PipelineWorker.KEY_CHAPTER, -1)
                val total = info.progress.getInt(PipelineWorker.KEY_TOTAL, chapters.size)
                val err = info.progress.getString(PipelineWorker.KEY_ERROR)
                binding.pipelineStatus.text = when {
                    err != null -> "流水线异常: ${err.take(40)}（可在设置中检查后重试）"
                    info.state == WorkInfo.State.SUCCEEDED -> {
                        val partial = info.outputData.getBoolean(PipelineWorker.KEY_PARTIAL, false)
                        val doneCh = info.outputData.getInt(PipelineWorker.KEY_CHAPTER, ch)
                        if (partial) "已预加载至第 $doneCh / $total 章（随阅读自动续跑）"
                        else "全部分析完成 ✓"
                    }
                    ch > 0 -> "后台预加载中… 已到第 $ch / $total 章"
                    stage != null -> stage
                    info.state == WorkInfo.State.RUNNING -> "后台分析中…"
                    else -> ""
                }
                val actualCh = if (info.state == WorkInfo.State.SUCCEEDED) {
                    info.outputData.getInt(PipelineWorker.KEY_CHAPTER, ch)
                } else {
                    ch
                }
                if (actualCh > lastSeenChapterDone) {
                    lastSeenChapterDone = actualCh
                    maybeRefreshCurrent()
                }
            }
    }

    /** 音量键翻页（阅读器刚需） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (currentIndex > 0) {
                        stopPlayingFullText()
                        showChapter(currentIndex - 1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (currentIndex < chapters.size - 1) {
                        stopPlayingFullText()
                        showChapter(currentIndex + 1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        playFullMenuItem = menu.findItem(R.id.action_play_full)
        updatePlayFullMenuTitle()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_play_full -> {
                if (isPlayingFullText) {
                    stopPlayingFullText()
                } else {
                    startPlayingFullText()
                }
                true
            }
            R.id.action_graph -> {
                startActivity(android.content.Intent(this,
                    com.storybrain.app.ui.graph.GraphActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId))
                true
            }
            R.id.action_search -> {
                startActivity(android.content.Intent(this,
                    com.storybrain.app.ui.search.SearchActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId))
                true
            }
            R.id.action_settings -> {
                startActivity(android.content.Intent(this,
                    com.storybrain.app.ui.settings.SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlayingFullText()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }

    // --- 全文播放逻辑 ---

    private fun startPlayingFullText() {
        isPlayingFullText = true
        updatePlayFullMenuTitle()
        playNextItem()
    }

    private fun stopPlayingFullText() {
        isPlayingFullText = false
        currentPlayItemIndex = -1
        if (playRunnable != null) {
            binding.chatRecycler.removeCallbacks(playRunnable)
            playRunnable = null
        }
        ttsManager.stop()
        updatePlayFullMenuTitle()
    }

    private fun updatePlayFullMenuTitle() {
        playFullMenuItem?.title = if (isPlayingFullText) "停止播放" else "播放全文"
    }

    private fun playNextItem() {
        if (!isPlayingFullText) return
        val items = adapter.currentItems

        var nextIndex = currentPlayItemIndex + 1
        while (nextIndex in items.indices) {
            val item = items[nextIndex]
            if (item.type == ChatItem.TYPE_DIALOGUE || item.type == ChatItem.TYPE_NARRATION) {
                break
            }
            nextIndex++
        }

        if (nextIndex in items.indices) {
            currentPlayItemIndex = nextIndex
            val item = items[nextIndex]
            
            // 滚动到当前朗读行
            binding.chatRecycler.smoothScrollToPosition(currentPlayItemIndex)
            
            // 取消之前挂起的延迟播放，并挂起新的延时任务
            if (playRunnable != null) {
                binding.chatRecycler.removeCallbacks(playRunnable)
            }
            
            val intervalMs = settings.loadTtsSpeechInterval().toLong()
            playRunnable = Runnable {
                if (!isPlayingFullText || currentPlayItemIndex != nextIndex) return@Runnable
                // 根据类型朗读
                if (item.type == ChatItem.TYPE_DIALOGUE) {
                    ttsManager.speakCharacter(item.speakerId, item.speakerName, item.content)
                } else {
                    ttsManager.speak(item.content, "demo-5") // 旁白固定使用 demo-5 男播音员
                }
            }
            binding.chatRecycler.postDelayed(playRunnable, intervalMs)
        } else {
            // 当前章结束，自动开启下一章
            if (currentIndex < chapters.size - 1) {
                currentPlayItemIndex = -1 // 重置新章的播放指针
                showChapter(currentIndex + 1)
            } else {
                // 全书播放结束
                stopPlayingFullText()
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
