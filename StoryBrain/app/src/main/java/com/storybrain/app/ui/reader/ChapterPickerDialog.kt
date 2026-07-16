package com.storybrain.app.ui.reader

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.storybrain.app.data.model.Chapter

/** 章节选择对话框（用 AlertDialog + 内嵌 RecyclerView） */
class ChapterPickerDialog(
    private val chapters: List<Chapter>,
    private val current: Int,
    private val onPick: (Int) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val ctx = requireContext()
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = ChapterListAdapter(chapters, current) { idx ->
                onPick(idx); dismiss()
            }
            // 固定大小优化：章节列表项高度固定，开启后 RecyclerView 不再每次测量
            setHasFixedSize(true)
        }
        return AlertDialog.Builder(ctx)
            .setTitle("选择章节")
            .setView(rv)
            .setNegativeButton("取消", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // window 在 show 之前为 null，必须在 onStart 中设置尺寸
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
    }
}
