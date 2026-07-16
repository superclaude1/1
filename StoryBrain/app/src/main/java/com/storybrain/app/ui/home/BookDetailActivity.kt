package com.storybrain.app.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.R
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.model.CharacterEntry
import com.storybrain.app.databinding.ActivityBookDetailBinding
import com.storybrain.app.databinding.ItemCharacterBinding
import com.storybrain.app.ui.character.CharacterChatActivity
import com.storybrain.app.ui.character.CharacterDetailActivity
import com.storybrain.app.ui.graph.GraphActivity
import com.storybrain.app.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailBinding
    private lateinit var repo: BookRepository
    private lateinit var settings: com.storybrain.app.data.SettingsManager
    private var bookId: String = ""
    private var bookTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "书籍详情"

        val app = application as StoryBrainApp
        repo = app.bookRepository
        settings = app.settingsManager
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (bookId.isEmpty()) { finish(); return }

        bindButtons()
    }

    override fun onResume() {
        super.onResume()
        loadBookDetail()
    }

    private fun bindButtons() {
        binding.btnRead.setOnClickListener {
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId))
        }
        binding.btnGraph.setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId))
        }
    }

    private fun loadBookDetail() {
        lifecycleScope.launch {
            val (title, chapterCount, readChapter, lastReadTime, characters) = withContext(Dispatchers.IO) {
                val t = repo.bookTitle(bookId)
                val totalCh = repo.loadChapterIndex(bookId).size
                val readCh = settings.loadReadingProgress(bookId)
                val lastRead = settings.loadLastReadTime(bookId)
                val brain = repo.loadBrain(bookId)
                val charMap = brain?.globalRegistry?.characters ?: emptyMap()
                
                val counts = repo.countAllCharacterAppearances(bookId, charMap)
                val charStats = charMap.toList().map { (charId, entry) ->
                    val appearances = counts[charId] ?: 0
                    Triple(charId, entry, appearances)
                }.sortedByDescending { it.third }
                
                Quint(t, totalCh, readCh, lastRead, charStats)
            }

            bookTitle = title
            supportActionBar?.title = title
            binding.tvBookTitle.text = title
            binding.bookAvatar.text = title.firstOrNull()?.toString() ?: "书"
            val colors = intArrayOf(
                0xFF80CBC4.toInt(), // Teal
                0xFF9FA8DA.toInt(), // Slate Blue
                0xFFFFCC80.toInt(), // Warm Orange
                0xFFF48FB1.toInt(), // Rose Pink
                0xFFCE93D8.toInt(), // Lilac
                0xFFA5D6A7.toInt(), // Mint Green
                0xFFB0BEC5.toInt()  // Soft Grey
            )
            val h = title.hashCode()
            val color = colors[Math.floorMod(h, colors.size)]
            binding.bookAvatar.setBackgroundColor(color)
            binding.tvChaptersInfo.text = "共 $chapterCount 章"
            
            if (chapterCount > 0) {
                val readPercent = (readChapter + 1) * 100 / chapterCount
                binding.tvReadProgress.text = "已读 $readPercent% (第 ${readChapter + 1} 章)"
            } else {
                binding.tvReadProgress.text = "未开始阅读"
            }

            if (lastReadTime > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.tvLastReadTime.text = "上次阅读: ${sdf.format(Date(lastReadTime))}"
                binding.tvLastReadTime.visibility = View.VISIBLE
            } else {
                binding.tvLastReadTime.visibility = View.GONE
            }

            if (characters.isEmpty()) {
                binding.tvEmptyCharacters.visibility = View.VISIBLE
                binding.rvCharacters.visibility = View.GONE
            } else {
                binding.tvEmptyCharacters.visibility = View.GONE
                binding.rvCharacters.visibility = View.VISIBLE
                binding.rvCharacters.layoutManager = LinearLayoutManager(this@BookDetailActivity)
                binding.rvCharacters.adapter = CharacterAdapter(characters) { charId, action ->
                    if (action == ACTION_CHAT) {
                        startActivity(Intent(this@BookDetailActivity, CharacterChatActivity::class.java).apply {
                            putExtra(CharacterChatActivity.EXTRA_BOOK_ID, bookId)
                            putExtra(CharacterChatActivity.EXTRA_CHAR_ID, charId)
                        })
                    } else {
                        startActivity(Intent(this@BookDetailActivity, CharacterDetailActivity::class.java).apply {
                            putExtra(CharacterDetailActivity.EXTRA_BOOK_ID, bookId)
                            putExtra(CharacterDetailActivity.EXTRA_CHAR_ID, charId)
                        })
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val ACTION_CHAT = 0
        const val ACTION_DETAIL = 1
    }

    data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private val avatarColors = intArrayOf(
        0xFF70C56A.toInt(), 0xFF5AA9E6.toInt(), 0xFFF0A04B.toInt(),
        0xFFE66B6B.toInt(), 0xFF8E7CC3.toInt(), 0xFF4DB6AC.toInt()
    )
    private fun avatarColor(name: String): Int {
        val h = if (name.isEmpty()) 0 else name.hashCode()
        return avatarColors[Math.floorMod(h, avatarColors.size)]
    }

    inner class CharacterAdapter(
        private val list: List<Triple<String, CharacterEntry, Int>>,
        private val onItemClick: (String, Int) -> Unit
    ) : RecyclerView.Adapter<CharacterAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (charId, entry, appearances) = list[position]
            val b = holder.binding

            b.avatarText.text = entry.name.firstOrNull()?.toString() ?: "角"
            b.avatarBg.backgroundTintList = ColorStateList.valueOf(avatarColor(entry.name))


            b.tvCharName.text = entry.name
            b.tvCharAliases.text = if (entry.aliases.isEmpty()) "出场 $appearances 次" else "别称: ${entry.aliases.joinToString("、")} | 出场 $appearances 次"

            b.btnChat.setOnClickListener { onItemClick(charId, ACTION_CHAT) }
            holder.itemView.setOnClickListener { onItemClick(charId, ACTION_DETAIL) }
        }

        override fun getItemCount(): Int = list.size

        inner class VH(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
