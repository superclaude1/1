package com.storybrain.app.data.local

/**
 * 本地启发式广告/无关内容清洗（零 Token 成本）。
 * 网文 txt 常见广告模式：
 *  - 章节首尾推广语（"本书首发…""手机用户请浏览…""点击加入书友群…"）
 *  - 求票/求收藏/求打赏（"求月票""推荐票""打赏名单""感谢XXX盟主"）
 *  - 作者的话 / 请假条 / 上架感言
 *  - 防盗版水印、乱码提示、章节重复
 *  - 网站 footer/导航 混入文本
 * 启发式只能覆盖常见模式，漏网之鱼交给 LLM 深度清洗。
 */
object AdRemover {

    /** 首/尾段落广告识别关键词（中文，匹配到即删除整段） */
    private val adKeywords = listOf(
        // 网站推广
        "本书首发", "首发于", "起点中文网", "创世中文网", "纵横中文网",
        "手机用户请", "手机站", "wap.", "m..com", "笔趣阁", "看书网",
        "书友群", "qq群", "粉丝群", "加群", "微信群", "公众号",
        "百度搜索", "谷歌搜索", "搜索引擎", "最新章节", "最快更新",
        "无弹窗", "广告", "全文免费", "免费阅读", "请记住本书",
        "域名", "网址", "本站", "请收藏", "加入书架",
        // 求票/打赏
        "求月票", "求推荐票", "求推荐", "推荐票", "月票",
        "打赏", "盟主", "舵主", "堂主", "执事", "弟子", "掌门",
        "感谢", "谢谢大家", "谢谢支持", "谢谢各位",
        // 新书预告（仅含推广性质的，作者日常碎语不删）
        "新书预告",
        // 防盗版/水印
        "防盗版", "盗版", "水印", "文字首发", "正版",
        // 乱码提示
        "章节内容正在手打中", "内容更新中", "加载中", "请稍后再试",
        "error", "404", "not found"
    )

    /** 整行匹配的正则（更精准） */
    private val adLineRegex = listOf(
        Regex("""^[【\[\(]?\s*(本章未完|未完待续|下章预告|本章完|第.*?章\s*完)\s*[】\]\)]?\s*$"""),
        Regex("""^.{0,8}(求月票|求推荐票|求收藏|求打赏|求订阅).{0,8}$"""),
        Regex("""^.{0,6}(书友群|粉丝群|qq群|微信群).{0,6}\d{5,}.*$"""),
        Regex("""^.{0,10}(最新章节|最快更新|无弹窗|广告).{0,10}$"""),
        Regex("""^[-=_*·•…]{5,}$"""), // 分割线
        Regex("""^[\s\p{Punct}]+$""") // 纯符号/空行
    )

    /**
     * 清洗单章文本。
     * @return 清洗后的文本 + 删掉的段落数
     */
    fun cleanChapter(rawText: String): CleanResult {
        val paragraphs = rawText.lines().dropWhile { it.isBlank() }
        if (paragraphs.isEmpty()) return CleanResult(rawText, 0)

        val kept = mutableListOf<String>()
        var removed = 0
        var consecutiveAd = 0

        for (line in paragraphs) {
            val trimmed = line.trim()
            if (isAdLine(trimmed)) {
                removed++
                consecutiveAd++
                continue
            }
            // 正文段，保留
            kept.add(line)
            consecutiveAd = 0
        }

        // 去掉首尾空行
        val result = kept.joinToString("\n").trimEnd() + "\n"
        return CleanResult(result, removed)
    }

    private fun isAdLine(line: String): Boolean {
        if (line.isBlank()) return false // 空行不删，保留排版
        val lower = line.lowercase()
        // 整行正则匹配
        for (r in adLineRegex) {
            if (r.containsMatchIn(line)) return true
        }
        // 关键词匹配（行内关键词密度）
        var hitCount = 0
        for (kw in adKeywords) {
            if (kw in lower) hitCount++
            if (hitCount >= 2) return true // 一行中两个以上关键词才删，避免误伤正文
        }
        // 短行（< 20字）且含一个强关键词也删
        if (line.length < 20 && hitCount >= 1 && isStrongAdKeyword(lower)) return true
        return false
    }

    private fun isStrongAdKeyword(line: String): Boolean {
        val strong = listOf("书友群", "qq群", "求月票", "求推荐票", "最新章节", "无弹窗",
            "广告", "首发", "百度搜索", "笔趣阁", "手机用户请")
        return strong.any { it in line }
    }

    data class CleanResult(val cleanText: String, val removedLines: Int)
}
