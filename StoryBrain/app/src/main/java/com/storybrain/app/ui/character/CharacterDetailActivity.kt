package com.storybrain.app.ui.character

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.R
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.model.StoryBrain
import com.storybrain.app.databinding.ActivityCharacterDetailBinding
import com.storybrain.app.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 角色详情页：点头像 / 角色行进入。
 * 展示：标准名 + 别称、所在剧情节点的关系链、全书出场章节（点击跳转阅读）。
 */
class CharacterDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterDetailBinding
    private lateinit var repo: BookRepository
    private var bookId: String = ""
    private var charId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repo = (application as StoryBrainApp).bookRepository
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        charId = intent.getStringExtra(EXTRA_CHAR_ID).orEmpty()
        if (bookId.isEmpty() || charId.isEmpty()) { finish(); return }

        title = "角色详情"
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val brain = repo.loadBrain(bookId)
                val entry = brain?.globalRegistry?.characters?.get(charId)
                if (entry == null) null
                else {
                    val names = listOf(entry.name) + entry.aliases
                    val appearances = repo.findCharacterAppearances(bookId, names)
                    Triple(brain, entry, appearances)
                }
            }
            binding.loading.visibility = View.GONE
            if (data == null) {
                binding.emptyText.visibility = View.VISIBLE
            } else {
                binding.btnChat.setOnClickListener {
                    startActivity(Intent(this@CharacterDetailActivity, CharacterChatActivity::class.java).apply {
                        putExtra(CharacterChatActivity.EXTRA_BOOK_ID, bookId)
                        putExtra(CharacterChatActivity.EXTRA_CHAR_ID, charId)
                    })
                }
                render(data.first, data.second.name, data.second.aliases, data.third)
            }
        }
    }

    private val avatarColors = intArrayOf(
        0xFF70C56A.toInt(), 0xFF5AA9E6.toInt(), 0xFFF0A04B.toInt(),
        0xFFE66B6B.toInt(), 0xFF8E7CC3.toInt(), 0xFF4DB6AC.toInt()
    )
    private fun avatarColor(name: String): Int {
        val h = if (name.isEmpty()) 0 else name.hashCode()
        return avatarColors[Math.floorMod(h, avatarColors.size)]
    }

    private fun render(brain: StoryBrain, name: String, aliases: List<String>, appearances: List<BookRepository.SearchHit>) {
        binding.avatarText.text = name.firstOrNull()?.toString() ?: "书"
        binding.avatarText.backgroundTintList = android.content.res.ColorStateList.valueOf(avatarColor(name))
        binding.nameText.text = name
        binding.aliasesText.text = if (aliases.isEmpty()) "（无别称）" else "别称: ${aliases.joinToString("、")}"

        // —— 关系网：遍历所有剧情节点，找出含本角色 ID 的关系链 ——
        binding.relationContainer.removeAllViews()
        val relations = mutableListOf<String>()
        brain.nodes.values.sortedBy { it.id }.forEach { node ->
            node.localNetwork.links.forEach { link ->
                if (link.from == charId || link.to == charId) {
                    val other = if (link.from == charId) link.to else link.from
                    val otherName = brain.globalRegistry.characters[other]?.name ?: other
                    val arrow = if (link.from == charId) "$name -[${link.relation}]-> $otherName"
                                else "$otherName -[${link.relation}]-> $name"
                    relations.add("《${node.title}》  $arrow" +
                        (if (link.context.isNotBlank()) "\n  背景: ${link.context}" else ""))
                }
            }
        }
        if (relations.isEmpty()) {
            binding.relationContainer.addView(hint("(暂无关系记录，等待流水线分析)"))
        } else {
            relations.forEach { 
                addDivider(binding.relationContainer)
                binding.relationContainer.addView(row(it)) 
            }
        }

        // —— 出场章节 ——
        binding.appearanceContainer.removeAllViews()
        binding.appearanceHint.visibility = View.VISIBLE
        binding.appearanceHint.text = "共 ${appearances.size} 章出场"
        if (appearances.isEmpty()) {
            binding.appearanceContainer.addView(hint("(正文未匹配到该角色名)"))
        } else {
            appearances.forEach { hit ->
                addDivider(binding.appearanceContainer)
                val v = row("第 ${hit.chapterIndex} 章 · ${hit.chapterTitle}", hit.snippet)
                v.setOnClickListener {
                    startActivity(Intent(this, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                        putExtra(EXTRA_TARGET_INDEX, hit.chapterIndex - 1) // 0-based
                    })
                }
                binding.appearanceContainer.addView(v)
            }
        }
    }

    private fun addDivider(container: ViewGroup) {
        if (container.childCount > 0) {
            val divider = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(getColor(R.color.wechat_divider))
            }
            container.addView(divider)
        }
    }

    private fun row(title: String, sub: String): View = TextView(this).apply {
        setPadding(48, 32, 48, 32); textSize = 13f
        text = if (sub.isBlank()) title else "$title\n$sub"
        setTextColor(getColor(R.color.wechat_title_text))
        setBackgroundResource(android.R.color.transparent)
    }

    private fun row(text: String): View = row("", text)

    private fun hint(s: String): View = TextView(this).apply {
        setPadding(48, 32, 48, 32); text = s; setTextColor(getColor(R.color.wechat_hint))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAR_ID = "char_id"
        const val EXTRA_TARGET_INDEX = "target_index"
    }
}
