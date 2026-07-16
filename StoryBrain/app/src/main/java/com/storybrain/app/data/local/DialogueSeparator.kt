package com.storybrain.app.data.local

import com.storybrain.app.data.model.Chapter
import com.storybrain.app.data.model.DialogueLine

/**
 * 符号规则初分 (Step2 第二项)
 * 利用双引号（“…”/"/"/「」）自动打上 [对话] 标签；引号外打 [旁白] 标签。
 * 这一步仅做初步分离，LLM 在 Step4 会做“精细化分离”修正（补说话人等）。
 */
object DialogueSeparator {

    private val QUOTE_PAIRS = listOf(
        '"' to '"',
        '「' to '」',
        '『' to '』',
        '“' to '”'
    )

    data class Segment(val text: String, val isDialogue: Boolean)

    /**
     * 把一章正文按引号切成 对话/旁白 片段。
     */
    fun segment(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        val sb = StringBuilder()
        var i = 0
        var inQuote = false
        var quoteClose: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (!inQuote) {
                val open = QUOTE_PAIRS.firstOrNull { it.first == c }
                if (open != null) {
                    if (sb.isNotBlank()) {
                        result.add(Segment(sb.toString(), false)); sb.clear()
                    }
                    sb.append(c)
                    inQuote = true
                    quoteClose = open.second
                } else {
                    sb.append(c)
                }
            } else {
                sb.append(c)
                if (c == quoteClose) {
                    result.add(Segment(sb.toString(), true)); sb.clear()
                    inQuote = false; quoteClose = null
                }
            }
            i++
        }
        if (sb.isNotBlank()) result.add(Segment(sb.toString(), inQuote))
        return result
    }

    /**
     * 初步分离：返回 dialogues（说话人未知）与 narrationBlocks。
     */
    fun separate(chapter: Chapter): Pair<List<DialogueLine>, List<String>> {
        val segs = segment(chapter.rawText)
        val dialogues = mutableListOf<DialogueLine>()
        val narrations = mutableListOf<String>()
        segs.forEach { seg ->
            val cleaned = seg.text.trim()
            if (cleaned.isEmpty()) return@forEach
            if (seg.isDialogue) {
                val content = stripQuotes(cleaned)
                if (content.isNotBlank()) {
                    dialogues.add(
                        DialogueLine(
                            speakerId = null,
                            speakerName = "未知",
                            content = content,
                            chapterId = chapter.id
                        )
                    )
                }
            } else {
                narrations.add(cleaned)
            }
        }
        return dialogues to narrations
    }

    private fun stripQuotes(s: String): String {
        if (s.length < 2) return s
        val first = s.first()
        val last = s.last()
        val pair = QUOTE_PAIRS.firstOrNull { it.first == first && it.second == last }
        return if (pair != null) s.substring(1, s.length - 1).trim() else s
    }

    /** 就地填充 chapter 的 dialogues / narrationBlocks */
    fun fill(chapter: Chapter): Chapter {
        val (d, n) = separate(chapter)
        return chapter.copy(dialogues = d, narrationBlocks = n)
    }
}
