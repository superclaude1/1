package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/** 章节切片结果：对应 ch_0001.txt … ch_NNNN.txt */
@Serializable
data class Chapter(
    val index: Int,          // 1-based
    val id: String,          // ch_0001
    val title: String,       // 第一章 xxxx
    val rawText: String,     // 该章正文
    val dialogues: List<DialogueLine> = emptyList(),
    val narrationBlocks: List<String> = emptyList()
) {
    val fileName: String get() = "$id.txt"
}
