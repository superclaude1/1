package com.storybrain.app.ui.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.data.model.Chapter

/**
 * 章节选择列表适配器（用于 [ChapterPickerDialog] 内嵌的 RecyclerView）。
 * 当前行高亮半透明背景；点击触发 [onPick] 回调（position 为 0-based）。
 */
class ChapterListAdapter(
    private val chapters: List<Chapter>,
    private val current: Int,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<ChapterListAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val dp = parent.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val tv = TextView(parent.context).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 15f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return VH(tv)
    }

    override fun getItemCount() = chapters.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = chapters[position]
        holder.text.text = "${position + 1}. ${ch.title}"
        if (position == current) {
            holder.text.setBackgroundColor(0x33000000)
        } else {
            holder.text.setBackgroundColor(0x00000000)
        }
        holder.text.setOnClickListener { onPick(position) }
    }
}
