package com.storybrain.app.data.local

import com.storybrain.app.data.model.Chapter

/**
 * 章节精准切片 (Step2 第一项)
 * 正则匹配“第X章 / 第一章 / 第123回 / 楔子 / 序章”，整书自动切分为独立章节。
 * 纯本地、零 Token 成本。
 */
object ChapterSplitter {

    private val CHAPTER_REGEX = Regex(
        """^\s*(?:第\s*[零一二三四五六七八九十百千万0-9]+\s*[章回卷节]\s*[^\n]*|楔\s*子[^\n]*|序\s*章[^\n]*|前\s*言[^\n]*|引\s*子[^\n]*)""",
        RegexOption.MULTILINE
    )

    private val CN_NUM = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 中文数字转 int，失败回退到正则索引序号 */
    private fun parseIndex(title: String): Int {
        val m = Regex("""[零一二三四五六七八九十百千万0-9]+""").find(title) ?: return -1
        val s = m.value
        s.toIntOrNull()?.let { return it }
        // 中文数字解析：累加 section，支持 二百五十/三千四百/一万二千 等
        var total = 0
        var section = 0
        var number = 0
        for (c in s) {
            when (c) {
                in CN_NUM -> number = CN_NUM.getValue(c)
                '十' -> { section += (if (number == 0) 1 else number) * 10; number = 0 }
                '百' -> { section += (if (number == 0) 1 else number) * 100; number = 0 }
                '千' -> { section += (if (number == 0) 1 else number) * 1000; number = 0 }
                '万' -> { total += (section + (if (number == 0) 0 else number)) * 10000; section = 0; number = 0 }
            }
        }
        total += section + number
        return if (total > 0) total else -1
    }

    /**
     * 将整书文本切分为章节列表。
     * @param fullText 原著全文
     * @return 章节列表（1-based 序号）
     */
    fun split(fullText: String): List<Chapter> {
        val matches = CHAPTER_REGEX.findAll(fullText).toList()
        if (matches.isEmpty()) {
            // 没有章节标记 → 整体作为一章
            return listOf(
                Chapter(index = 1, id = "ch_0001", title = "正文", rawText = fullText.trim())
            )
        }

        val chapters = mutableListOf<Chapter>()
        // 章节标题之前若有内容，作为“序言”章
        if (matches.first().range.first > 0) {
            val preface = fullText.substring(0, matches.first().range.first).trim()
            if (preface.isNotEmpty()) {
                chapters.add(Chapter(index = 1, id = "ch_0001", title = "序言", rawText = preface))
            }
        }

        matches.forEachIndexed { i, m ->
            val titleLine = m.value.trim()
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else fullText.length
            val body = fullText.substring(start, end).trim()
            val parsed = parseIndex(titleLine)
            val idx = if (parsed > 0) parsed else chapters.size + 1
            chapters.add(
                Chapter(
                    index = idx,
                    id = "ch_%04d".format(idx),
                    title = titleLine,
                    rawText = body
                )
            )
        }

        // 重排连续序号，避免中文解析跳号
        return chapters.sortedBy { it.index }.mapIndexed { i, ch ->
            ch.copy(index = i + 1, id = "ch_%04d".format(i + 1))
        }
    }
}
