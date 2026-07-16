package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/**
 * 精细化分离后的一句对话。
 * speakerId 为 null 表示未能识别说话人（按旁白或“未知”处理）。
 */
@Serializable
data class DialogueLine(
    val speakerId: String? = null,
    val speakerName: String = "未知",
    val content: String = "",
    val chapterId: String = ""
)
