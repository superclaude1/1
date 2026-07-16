package com.storybrain.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.R
import com.storybrain.app.databinding.ItemBookBinding

/**
 * 书架列表项数据
 * @param bookId    书 ID
 * @param title     书名
 * @param chapters  总章数
 * @param readChapter  已读到第几章（0-based）
 * @param analyzedChapter 已分析到第几章（0 表示未开始）
 * @param lastReadTime  最近阅读时间戳（ms），0=从未阅读
 */
data class BookShelfItem(
    val bookId: String,
    val title: String,
    val chapters: Int,
    val readChapter: Int,
    val analyzedChapter: Int,
    val lastReadTime: Long
)

/**
 * 书架列表适配器（[ImportActivity] 用）。
 * 卡片式布局：左侧首字色块 + 右侧书名/章节信息/阅读进度/分析进度条。
 * 单击打开阅读器，长按触发删除确认。按最近阅读时间降序排列。
 */
class BookListAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<BookListAdapter.VH>() {

    private val items = mutableListOf<BookShelfItem>()

    fun submit(newItems: List<BookShelfItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        // Title initials as cover
        b.bookAvatar.text = item.title.firstOrNull()?.toString() ?: "书"
        val colors = intArrayOf(
            0xFF80CBC4.toInt(), // Teal
            0xFF9FA8DA.toInt(), // Slate Blue
            0xFFFFCC80.toInt(), // Warm Orange
            0xFFF48FB1.toInt(), // Rose Pink
            0xFFCE93D8.toInt(), // Lilac
            0xFFA5D6A7.toInt(), // Mint Green
            0xFFB0BEC5.toInt()  // Soft Grey
        )
        val h = item.title.hashCode()
        val color = colors[Math.floorMod(h, colors.size)]
        b.bookAvatar.setBackgroundColor(color)

        // 书名
        b.bookTitle.text = item.title

        // 章节数
        b.bookCount.text = "${item.chapters} 章"

        // 阅读进度
        if (item.lastReadTime > 0 && item.chapters > 0) {
            val readPercent = (item.readChapter + 1) * 100 / item.chapters
            b.bookReadInfo.text = "已读 $readPercent%"
        } else {
            b.bookReadInfo.text = "未阅读"
        }

        // 分析进度条 + 状态文字
        if (item.chapters > 0) {
            val progress = item.analyzedChapter * 100 / item.chapters
            b.analysisProgress.progress = progress
            b.analysisStatus.text = if (item.analyzedChapter == 0) {
                "未分析"
            } else if (item.analyzedChapter >= item.chapters) {
                "分析完成"
            } else {
                "已分析 ${item.analyzedChapter}/${item.chapters} 章"
            }
        } else {
            b.analysisProgress.progress = 0
            b.analysisStatus.text = ""
        }

        holder.itemView.setOnClickListener { onClick(item.bookId) }
        holder.itemView.setOnLongClickListener { onLongClick(item.bookId); true }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val binding = ItemBookBinding.bind(v)
    }
}
