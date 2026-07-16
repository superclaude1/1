package com.storybrain.app.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.R
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.databinding.ActivitySearchBinding
import com.storybrain.app.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全书搜索：搜人物名 / 台词 / 场景。
 * 点结果跳转到对应章节阅读。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repo: BookRepository
    private lateinit var adapter: ResultAdapter
    private var bookId: String = ""
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "全书搜索"

        repo = (application as StoryBrainApp).bookRepository
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (bookId.isEmpty()) { finish(); return }

        adapter = ResultAdapter { hit ->
            // 跳转到对应章节
            startActivity(Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TARGET_INDEX, hit.chapterIndex - 1) // 0-based
            })
        }
        binding.resultRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultRecycler.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.editQuery.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        // 输入防抖：停止输入 500ms 后自动搜索
        binding.editQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                debounceJob?.cancel()
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) {
                    adapter.submit(emptyList(), "")
                    binding.statusText.visibility = android.view.View.GONE
                    binding.emptyText.visibility = android.view.View.GONE
                    return
                }
                debounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
                    doSearch()
                }
            }
        })

        // 预填上次搜索词（按 bookId 隔离，避免跨书污染）
        lastQueries[bookId]?.let { binding.editQuery.setText(it) }
    }

    private fun doSearch() {
        val q = binding.editQuery.text.toString().trim()
        if (q.isEmpty()) return
        lastQueries[bookId] = q
        // 取消上一次未完成的搜索，避免并发竞态导致结果显示错乱
        searchJob?.cancel()
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = "搜索中…"
        binding.emptyText.visibility = android.view.View.GONE
        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { repo.search(bookId, q) }
            adapter.submit(results, q)
            binding.statusText.text = "找到 ${results.size} 条结果"
            binding.emptyText.visibility = if (results.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.emptyText.text = "未找到包含 \"$q\" 的内容"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_TARGET_INDEX = "target_index"
        /** 按 bookId 隔离的上次搜索词，避免跨书污染 */
        private val lastQueries = mutableMapOf<String, String>()
    }
}

/**
 * 搜索结果适配器：展示命中章节标题 + 带上下文的 snippet。
 * snippet 中所有命中关键词用 [ForegroundColorSpan] 高亮（微信红 #FA5151）。
 * 点击结果跳转到对应章节阅读器。
 */
class ResultAdapter(
    private val onClick: (BookRepository.SearchHit) -> Unit
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val items = mutableListOf<BookRepository.SearchHit>()
    private var query: String = ""

    fun submit(newItems: List<BookRepository.SearchHit>, q: String) {
        items.clear()
        items.addAll(newItems)
        query = q
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.chapterTitle.text = "第 ${item.chapterIndex} 章 · ${item.chapterTitle}"
        holder.snippet.text = highlight(item.snippet, query)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    /** 高亮 snippet 中所有命中关键词（大小写不敏感） */
    private fun highlight(text: String, keyword: String): CharSequence {
        if (keyword.isBlank()) return text
        val sb = SpannableStringBuilder(text)
        var start = 0
        while (start < text.length) {
            val pos = text.indexOf(keyword, start, ignoreCase = true)
            if (pos < 0) break
            sb.setSpan(
                ForegroundColorSpan(0xFFFA5151.toInt()),
                pos, pos + keyword.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = pos + keyword.length
        }
        return sb
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val chapterTitle: android.widget.TextView = v.findViewById(R.id.chapterTitle)
        val snippet: android.widget.TextView = v.findViewById(R.id.snippetText)
    }
}
