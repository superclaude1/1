package com.storybrain.app.data.model

/** 阅读器列表项：对话 / 旁白 / 章节分隔 三种视图类型 */
data class ChatItem(
    val type: Int,
    val speakerName: String = "",
    val speakerId: String? = null,
    val avatarSeed: String = "书",
    val content: String = "",
    val chapterTitle: String = ""
) {
    companion object {
        const val TYPE_CHAPTER_DIVIDER = 0
        const val TYPE_DIALOGUE = 1
        const val TYPE_NARRATION = 2
    }
}
