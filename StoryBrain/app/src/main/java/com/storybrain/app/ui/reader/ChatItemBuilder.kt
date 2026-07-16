package com.storybrain.app.ui.reader

import com.storybrain.app.data.model.AppJson
import com.storybrain.app.data.model.ChatItem
import com.storybrain.app.data.model.Chapter
import com.storybrain.app.data.model.DialogueLine
import com.storybrain.app.data.model.GlobalCharacterRegistry

/**
 * 把一章的【对话分离结果】+【旁白】组装成微信聊天式列表项：
 *  - 章节分隔（居中）
 *  - 对话：角色头像在左 + 气泡（TYPE_DIALOGUE）
 *  - 旁白：居中灰字（TYPE_NARRATION）
 */
object ChatItemBuilder {

    /**
     * @param dialogueJson 已分离并存档的对话 JSON（可能为空 → 回退到本地初分）
     * @param narrationText 旁白分析文本（可能为空 → 回退到本地初分）
     * @param registry 全局角色索引（用于补头像 seed / 标准名）
     */
    fun build(
        chapter: Chapter,
        dialogueJson: String?,
        narrationText: String?,
        registry: GlobalCharacterRegistry
    ): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        items.add(ChatItem(type = ChatItem.TYPE_CHAPTER_DIVIDER, chapterTitle = chapter.title))

        // 如果存在 LLM 旁白环境总述，作为特殊的置顶卡片展示
        if (!narrationText.isNullOrBlank()) {
            items.add(ChatItem(type = ChatItem.TYPE_NARRATION, content = "【场景与氛围分析】\n$narrationText"))
        }

        // 优先使用 LLM 精细化分离结果，否则回退到本地符号初分
        val refinedDialogues: List<DialogueLine> = if (!dialogueJson.isNullOrBlank()) {
            runCatching { AppJson.decodeFromString<List<DialogueLine>>(dialogueJson) }.getOrDefault(chapter.dialogues)
        } else {
            chapter.dialogues
        }

        // 获取原文章节的段落与对话交错列表，以保持原文顺序
        val segments = com.storybrain.app.data.local.DialogueSeparator.segment(chapter.rawText)
        var dialogueIndex = 0

        segments.forEach { seg ->
            val cleaned = seg.text.trim()
            if (cleaned.isEmpty()) return@forEach

            if (seg.isDialogue) {
                if (dialogueIndex < refinedDialogues.size) {
                    val d = refinedDialogues[dialogueIndex++]
                    val resolvedName = registry.characters[d.speakerId]?.name ?: d.speakerName
                    val seed = registry.characters[d.speakerId]?.avatarSeed
                        ?: resolvedName.firstOrNull()?.toString() ?: "角"
                    items.add(
                        ChatItem(
                            type = ChatItem.TYPE_DIALOGUE,
                            speakerName = resolvedName,
                            speakerId = d.speakerId,
                            avatarSeed = seed,
                            content = d.content
                        )
                    )
                } else {
                    // LLM 对话匹配用尽，回退到原文字段剥离引号后的结果
                    val content = stripQuotes(cleaned)
                    items.add(
                        ChatItem(
                            type = ChatItem.TYPE_DIALOGUE,
                            speakerName = "未知",
                            speakerId = null,
                            avatarSeed = "未",
                            content = content
                        )
                    )
                }
            } else {
                items.add(ChatItem(type = ChatItem.TYPE_NARRATION, content = cleaned))
            }
        }
        return items
    }

    private val QUOTE_CHARS = setOf('"', '「', '」', '『', '』', '“', '”')
    private fun stripQuotes(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && s[start] in QUOTE_CHARS) start++
        while (start < end && s[end - 1] in QUOTE_CHARS) end--
        return if (start < end) s.substring(start, end).trim() else s
    }
}
