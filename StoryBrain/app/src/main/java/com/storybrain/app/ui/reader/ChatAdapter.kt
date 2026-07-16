package com.storybrain.app.ui.reader

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.R
import com.storybrain.app.data.model.ChatItem
import com.storybrain.app.databinding.ItemChatDialogueBinding
import com.storybrain.app.databinding.ItemChatDividerBinding
import com.storybrain.app.databinding.ItemChatNarrationBinding

/**
 * 微信风格聊天适配器：
 *  - 对话：角色头像在左 + 左侧气泡（仿微信对方消息）
 *  - 旁白：居中灰字（仿微信系统提示）
 *  - 章节分隔：居中胶囊标签（仿微信日期分隔）
 *  支持：夜间模式主题切换 + 字号缩放 + AsyncListDiffer 后台线程 diff。
 */
class ChatAdapter(
    private val onAvatarClick: (String) -> Unit,
    private val onBubbleClick: (ChatItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val diffCallback = object : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(o: ChatItem, n: ChatItem): Boolean =
            o.type == n.type && o.chapterTitle == n.chapterTitle && o.content == n.content
        override fun areContentsTheSame(o: ChatItem, n: ChatItem): Boolean = o == n
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    val currentItems: List<ChatItem>
        get() = differ.currentList


    // 头像底色调色板（按名字哈希取色）
    private val avatarColors = intArrayOf(
        0xFF70C56A.toInt(), 0xFF5AA9E6.toInt(), 0xFFF0A04B.toInt(),
        0xFFE66B6B.toInt(), 0xFF8E7CC3.toInt(), 0xFF4DB6AC.toInt()
    )

    /** 当前主题：白天 / 夜间 */
    var nightMode: Boolean = false
        set(value) { field = value; cachedColors = computeColors(); notifyDataSetChanged() }

    /** 字号缩放倍数 */
    var fontScale: Float = 1.0f
        set(value) { field = value.coerceIn(0.8f, 1.6f); notifyDataSetChanged() }

    /** 缓存主题色，避免每次 bind 都 new 对象 */
    private var cachedColors: ThemeColors = computeColors()

    /** 提交新列表（DiffUtil 在后台线程计算，避免阻塞主线程） */
    fun submit(newItems: List<ChatItem>, onCommit: Runnable? = null) {
        differ.submitList(newItems, onCommit)
    }


    override fun getItemViewType(position: Int): Int = differ.currentList[position].type
    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            ChatItem.TYPE_CHAPTER_DIVIDER -> DividerVH(ItemChatDividerBinding.inflate(inf, parent, false))
            ChatItem.TYPE_DIALOGUE -> DialogueVH(ItemChatDialogueBinding.inflate(inf, parent, false))
            else -> NarrationVH(ItemChatNarrationBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = differ.currentList[position]
        when (holder) {
            is DividerVH -> holder.bind(item)
            is DialogueVH -> holder.bind(item, avatarColor(item.speakerName))
            is NarrationVH -> holder.bind(item)
        }
    }

    private fun avatarColor(name: String): Int {
        val h = if (name.isEmpty()) 0 else name.hashCode()
        return avatarColors[Math.floorMod(h, avatarColors.size)]
    }

    /** 应用主题色到根布局（由 Activity 调用一次即可，这里提供工具方法） */
    fun themeColors(): ThemeColors = cachedColors

    private fun computeColors(): ThemeColors = if (nightMode) {
        ThemeColors(
            bg = R.color.night_bg,
            titleBar = R.color.night_title_bar,
            bubbleText = R.color.night_bubble_text,
            narration = R.color.night_narration,
            hint = R.color.night_hint,
            divider = R.color.night_divider,
            bubbleRes = R.drawable.bg_bubble_left_night,
            narrationBg = 0xFF2C2C2C.toInt()
        )
    } else {
        ThemeColors(
            bg = R.color.wechat_bg,
            titleBar = R.color.wechat_title_bar,
            bubbleText = R.color.wechat_bubble_text,
            narration = R.color.wechat_narration,
            hint = R.color.wechat_hint,
            divider = R.color.wechat_divider,
            bubbleRes = R.drawable.bg_bubble_left,
            narrationBg = 0xFFEAEAEA.toInt()
        )
    }

    data class ThemeColors(
        val bg: Int, val titleBar: Int, val bubbleText: Int,
        val narration: Int, val hint: Int, val divider: Int,
        val bubbleRes: Int, val narrationBg: Int
    )

    inner class DividerVH(val b: ItemChatDividerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatItem) {
            b.dividerText.text = item.chapterTitle
            b.dividerText.textSize = 12f * fontScale
        }
    }

    inner class DialogueVH(val b: ItemChatDialogueBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatItem, color: Int) {
            val t = themeColors()
            b.avatarText.text = item.avatarSeed
            b.avatarBg.backgroundTintList = ColorStateList.valueOf(color)
            b.speakerName.text = item.speakerName
            b.dialogueText.text = item.content
            b.speakerName.textSize = 11f * fontScale
            b.dialogueText.textSize = 15f * fontScale
            b.dialogueText.setBackgroundResource(t.bubbleRes)
            b.dialogueText.setTextColor(itemView.context.getColor(t.bubbleText))
            b.speakerName.setTextColor(itemView.context.getColor(t.hint))

            b.avatarText.setOnClickListener {
                item.speakerId?.let { id -> if (id.isNotBlank()) onAvatarClick(id) }
            }
            b.speakerName.setOnClickListener {
                item.speakerId?.let { id -> if (id.isNotBlank()) onAvatarClick(id) }
            }
            b.dialogueText.setOnClickListener {
                onBubbleClick(item)
            }
        }
    }

    inner class NarrationVH(val b: ItemChatNarrationBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatItem) {
            val t = themeColors()
            b.narrationText.text = item.content
            b.narrationText.textSize = 13f * fontScale
            b.narrationText.setTextColor(itemView.context.getColor(t.narration))
            b.narrationText.setBackgroundColor(t.narrationBg)
            b.narrationText.setOnClickListener {
                onBubbleClick(item)
            }
        }
    }
}
