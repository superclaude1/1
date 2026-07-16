package com.storybrain.app.ui.character

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.R
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.local.TtsManager
import com.storybrain.app.data.model.ChatMessage
import com.storybrain.app.databinding.ActivityCharacterChatBinding
import com.storybrain.app.databinding.ItemCharacterChatMessageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterChatBinding
    private lateinit var repo: BookRepository
    private lateinit var adapter: MessageAdapter
    private var bookId: String = ""
    private var charId: String = ""
    
    private var bookTitle: String = ""
    private var charName: String = ""
    private var charAliases: String = ""
    private var systemPrompt: String = ""
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var ttsManager: TtsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val app = application as StoryBrainApp
        repo = app.bookRepository
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        charId = intent.getStringExtra(EXTRA_CHAR_ID).orEmpty()
        if (bookId.isEmpty() || charId.isEmpty()) { finish(); return }

        adapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener { sendMessage() }

        ttsManager = TtsManager(this)

        loadCharacterAndHistory()
    }

    private fun loadCharacterAndHistory() {
        lifecycleScope.launch {
            val (title, name, aliases, prompt, history) = withContext(Dispatchers.IO) {
                val t = repo.bookTitle(bookId)
                val brain = repo.loadBrain(bookId)
                val character = brain?.globalRegistry?.characters?.get(charId)
                val nameStr = character?.name ?: charId
                val aliasesStr = character?.aliases?.joinToString("、") ?: ""

                // 提取角色人际关系设定
                val relationText = StringBuilder()
                brain?.nodes?.values?.forEach { node ->
                    node.localNetwork.links.forEach { link ->
                        if (link.from == charId || link.to == charId) {
                            val other = if (link.from == charId) link.to else link.from
                            val otherName = brain.globalRegistry.characters[other]?.name ?: other
                            val r = if (link.from == charId) "${nameStr} 与 ${otherName} 的关系是：${link.relation}"
                                    else "${otherName} 与 ${nameStr} 的关系是：${link.relation}"
                            relationText.append("- 《").append(node.title).append("》期间，").append(r)
                            if (link.context.isNotBlank()) {
                                relationText.append("，背景情况：").append(link.context)
                            }
                            relationText.append("\n")
                        }
                    }
                }

                val narrationBuilder = StringBuilder()
                brain?.nodes?.values?.forEach { node ->
                    if (node.summary.isNotBlank()) {
                        narrationBuilder.append("- 剧情单元【").append(node.title).append("】摘要：").append(node.summary).append("\n")
                    }
                    if (node.narrationAnalysis.isNotBlank()) {
                        narrationBuilder.append("- 剧情单元【").append(node.title).append("】旁白环境：").append(node.narrationAnalysis).append("\n")
                    }
                }
                val narrationText = narrationBuilder.toString()
                val sysPrompt = """
                    你是小说《$t》中的重要角色【$nameStr】${if (aliasesStr.isNotBlank()) "（别称/曾用名：$aliasesStr）" else ""}。
                    下面是关于你的性格背景、人际关系和所在故事环境的设定，请完全遵循以下设定进行角色扮演：
                    
                    【人物关系背景】：
                    ${if (relationText.isNotEmpty()) relationText.toString() else "（暂无具体人际关系设定，请根据原著自由发挥）"}
                    
                    【故事背景总览】：
                    $narrationText
                    
                    【扮演要求】：
                    1. 读者（我）正在与你对话。请完全沉浸在《$t》的世界观中，用符合【$nameStr】身份、性格、说话口吻和语气进行回复。
                    2. 保持回答简短、生动、口语化（强烈建议控制在 2-4 句话以内），表现得像一个正在微信聊天中与读者交谈的真实人物。
                    3. 绝对不要透露你是 AI 或大语言模型的事实，不要解释你的身份设定。如果被问及不属于故事世界的现代事物，请表现出困惑或符合角色身份的幽默反应。
                """.trimIndent()

                val hist = repo.loadCharacterChat(bookId, charId)
                Quint(t, nameStr, aliasesStr, sysPrompt, hist)
            }

            bookTitle = title
            charName = name
            charAliases = aliases
            systemPrompt = prompt
            
            supportActionBar?.title = "与 $charName 聊天中"
            messages.clear()
            messages.addAll(history)
            
            if (messages.isEmpty()) {
                // 首发欢迎语
                messages.add(ChatMessage(isUser = false, content = "你好，我是 $charName。找我有什么事吗？"))
            }
            adapter.notifyDataSetChanged()
            binding.rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.setText("")

        // 1. 添加用户消息
        val userMsg = ChatMessage(isUser = true, content = text)
        messages.add(userMsg)
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        // 2. 调用 LLM 异步生成回复
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            val responseResult = withContext(Dispatchers.IO) {
                runCatching {
                    val app = application as StoryBrainApp
                    val client = app.makeLlmClient() ?: error("未配置 LLM API，请去设置页进行配置")
                    
                    // 构造最近 10 条消息作为上下文
                    val history = messages.dropLast(1).takeLast(10).joinToString("\n") { msg ->
                        if (msg.isUser) "读者: ${msg.content}" else "$charName: ${msg.content}"
                    }
                    val userContent = if (history.isNotBlank()) {
                        "$history\n读者: $text\n$charName:"
                    } else {
                        "读者: $text\n$charName:"
                    }

                    val res = client.chatText(systemPrompt, userContent).getOrThrow()
                    res.trim().removePrefix("$charName:").removePrefix("“").removeSuffix("”").trim()
                }
            }

            binding.btnSend.isEnabled = true
            if (responseResult.isSuccess) {
                val reply = responseResult.getOrThrow()
                val replyMsg = ChatMessage(isUser = false, content = reply)
                messages.add(replyMsg)
                adapter.notifyItemInserted(messages.size - 1)
                binding.rvMessages.scrollToPosition(messages.size - 1)
                
                ttsManager.speakCharacter(charId, charName, reply)

                // 异步保存聊天记录
                withContext(Dispatchers.IO) {
                    repo.saveCharacterChat(bookId, charId, messages)
                }
            } else {
                Toast.makeText(this@CharacterChatActivity, "Ta 好像在忙，回复失败: ${responseResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onPause() {
        super.onPause()
        ttsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAR_ID = "char_id"
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

    inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemCharacterChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = messages[position]
            val b = holder.binding

            if (msg.isUser) {
                b.layoutLeft.visibility = View.GONE
                b.layoutRight.visibility = View.VISIBLE
                b.tvMessageRight.text = msg.content
                b.tvMessageRight.setOnClickListener {
                    ttsManager.speakCharacter(null, "我", msg.content)
                }
            } else {
                b.layoutLeft.visibility = View.VISIBLE
                b.layoutRight.visibility = View.GONE
                b.tvMessageLeft.text = msg.content
                b.avatarTextLeft.text = charName.firstOrNull()?.toString() ?: "角"
                b.avatarBgLeft.backgroundTintList = ColorStateList.valueOf(avatarColor(charName))
                b.tvMessageLeft.setOnClickListener {
                    ttsManager.speakCharacter(charId, charName, msg.content)
                }
            }
        }

        override fun getItemCount(): Int = messages.size

        inner class VH(val binding: ItemCharacterChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
