package com.storybrain.app.pipeline

import com.storybrain.app.data.local.BookRepository
import com.storybrain.app.data.local.DialogueSeparator
import com.storybrain.app.data.model.*
import com.storybrain.app.data.remote.LlmClient
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString

/** 流水线进度回调（UI / Worker 都可订阅） */
interface PipelineListener {
    fun onStage(stage: String, detail: String = "")
    fun onChapterDone(chapterIndex: Int, total: Int)
    fun onError(chapterIndex: Int, message: String)
}

/**
 * 流水线编排器 —— 承担 Step3 / Step4 的核心逻辑：
 *  Step3 initialize()：前 15 章合并投喂 → 生成根节点 + 全局角色索引 → 第一版 story_brain.json
 *  Step4 processChapter()：单章滚动增量更新 → 覆盖写回
 *  Step5 由 PipelineWorker 在后台异步串行调用 processChapter() 实现无感预加载。
 */
class PipelineOrchestrator(
    private val repo: BookRepository,
    private val llm: LlmClient,
    private val aiCleanEnabled: Boolean = false,
    private val listener: PipelineListener? = null
) {

    /** Step3：初始化“记忆盘” */
    suspend fun initialize(bookId: String, chapters: List<Chapter>): Result<StoryBrain> {
        val initN = PromptBuilder.chapterCountForInit().coerceAtMost(chapters.size)
        listener?.onStage("初始化故事大脑", "合并前 $initN 章")
        // 按需加载前 initN 章正文（rawText 可能为空）
        val firstChunkRaw = chapters.take(initN).map { ch ->
            if (ch.rawText.isEmpty()) ch.copy(rawText = repo.loadChapterText(bookId, ch.id))
            else ch
        }
        val firstChunk = if (aiCleanEnabled) {
            aiCleanChapters(bookId, firstChunkRaw, total = chapters.size)
        } else firstChunkRaw

        // 本地先做对话/旁白初分，给模型带标签的文本（结果缓存复用，避免下方存档时重复计算）
        val separated = firstChunk.map { ch -> ch to DialogueSeparator.separate(ch) }
        val merged = buildString {
            separated.forEach { (ch, segs) ->
                append("=== ${ch.id} ${ch.title} ===\n")
                segs.first.forEach { d -> append("[对话] ${d.content}\n") }
                segs.second.forEach { n -> append("[旁白] $n\n") }
                append("\n")
            }
        }.take(llm.config.maxContextChars)

        val brain0 = StoryBrain(
            bookTitle = repo.bookTitle(bookId),
            totalChapters = chapters.size
        )

        val system = PromptBuilder.buildInitSystem()
        val user = PromptBuilder.buildInitUser(brain0.bookTitle, merged)
        val delta = callWithRetry(
            system = system, user = user,
            chapterIndex = 0,
            onError = { msg -> listener?.onError(0, msg) }
        ) ?: return Result.failure(IllegalStateException("初始化失败（重试耗尽）"))

        // 合并：把根节点写进记忆盘
        var brain = brain0
        brain = DeltaMerger.merge(brain, delta, firstChunk.last())
        brain = brain.copy(rootNodeId = "node_root", processedChapterCount = initN)
        repo.saveBrain(bookId, brain)

        // 单独存档每章对话/旁白（优先用 LLM 精细化结果，否则用本地初分结果）
        val dialoguesByChapter = delta.dialogues.groupBy { it.chapterId }
        separated.forEach { (ch, segs) ->
            val finalDialogues = dialoguesByChapter[ch.id] ?: segs.first
            repo.saveChapterAnalysis(
                bookId, ch.id,
                AppJson.encodeToString(finalDialogues),
                segs.second.joinToString("\n\n")
            )
            listener?.onChapterDone(ch.index, chapters.size)
        }
        listener?.onStage("初始化完成", "已生成根节点与 ${brain.globalRegistry.characters.size} 个角色")
        return Result.success(brain)
    }

    /** Step4：单章滚动增量更新 */
    suspend fun processChapter(bookId: String, chapter: Chapter): Result<StoryBrain> {
        val brain = repo.loadBrain(bookId)
            ?: return Result.failure(IllegalStateException("记忆盘不存在，请先初始化"))

        listener?.onStage("分析第 ${chapter.index} 章", chapter.title)

        // 按需加载单章正文（rawText 可能为空，Worker 不再预加载全书）
        val loaded = if (chapter.rawText.isEmpty()) {
            chapter.copy(rawText = repo.loadChapterText(bookId, chapter.id))
        } else chapter

        val chapterForAnalysis = if (aiCleanEnabled) {
            aiCleanSingle(loaded) ?: loaded
        } else loaded
        val chapterText = chapterForAnalysis.rawText.take(llm.config.maxContextChars)
        val compact = brain.compactContextView() // 折叠陈旧节点

        val system = PromptBuilder.buildIncrementalSystem()
        val user = PromptBuilder.buildIncrementalUser(compact, chapter.id, chapter.title, chapterText)
        val delta = callWithRetry(
            system = system, user = user,
            chapterIndex = chapter.index,
            onError = { msg -> listener?.onError(chapter.index, msg) }
        ) ?: return Result.failure(IllegalStateException("第 ${chapter.index} 章失败（重试耗尽）"))

        val updated = DeltaMerger.merge(brain, delta, chapterForAnalysis)
        repo.saveBrain(bookId, updated) // 覆盖写回，作为下一章输入源

        // 单独存档本章对话/旁白分离结果
        repo.saveChapterAnalysis(
            bookId, chapter.id,
            AppJson.encodeToString(delta.dialogues),
            delta.narrationAnalysis
        )

        listener?.onChapterDone(chapter.index, brain.totalChapters)
        return Result.success(updated)
    }

    /** Step4 串行循环（由 Worker 调用），可指定起止章 */
    suspend fun runRolling(
        bookId: String,
        chapters: List<Chapter>,
        fromIndex: Int,
        toIndex: Int
    ): Result<StoryBrain> {
        var latest = repo.loadBrain(bookId) ?: return Result.failure(IllegalStateException("记忆盘不存在"))
        // 越界保护：fromIndex 必须 >= 1，toIndex 不超过章节数
        val start = fromIndex.coerceIn(1, chapters.size)
        val end = toIndex.coerceIn(start, chapters.size)
        for (i in start..end) {
            val ch = chapters[i - 1]
            val res = processChapter(bookId, ch)
            if (res.isFailure) return res
            latest = res.getOrThrow()
        }
        return Result.success(latest)
    }

    // —— AI 深度去广告 ——

    /** 单章 AI 清洗，失败返回 null（降级使用原文本） */
    private suspend fun aiCleanSingle(chapter: Chapter): Chapter? {
        val system = PromptBuilder.buildCleanSystem()
        val user = PromptBuilder.buildCleanUser(chapter.title, chapter.rawText)
        val raw = llm.chatJson(system, user).getOrElse {
            listener?.onError(chapter.index, "AI 清洗失败（忽略）: ${it.message}")
            return null
        }
        // 解析 {cleanText, removedCount}
        val cleanText = runCatching { extractCleanText(raw) }.getOrElse {
            listener?.onError(chapter.index, "AI 清洗 JSON 解析失败（忽略）: ${it.message}")
            return null
        }
        return chapter.copy(rawText = cleanText)
    }

    /** 批量 AI 清洗（初始化阶段用） */
    private suspend fun aiCleanChapters(bookId: String, chapters: List<Chapter>, total: Int): List<Chapter> {
        listener?.onStage("AI 深度去广告", "正在清洗 ${chapters.size} 章…")
        return chapters.mapIndexed { idx, ch ->
            listener?.onStage("AI 清洗", "第 ${ch.index} / $total 章")
            aiCleanSingle(ch) ?: ch
        }
    }

    /** 从 AI 清洗返回的 JSON 中提取 cleanText 字段 */
    private fun extractCleanText(rawJson: String): String {
        val stripped = JsonRecovery.stripFencesAndNoise(rawJson)
        val sub = stripped.let {
            val s = it.indexOf('{'); val e = it.lastIndexOf('}')
            if (s < 0 || e <= s) it else it.substring(s, e + 1)
        }
        // 手动解析 cleanText 字段：找 "cleanText":"..." 或 "cleanText": "..."
        val regex = Regex(""""cleanText"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val match = regex.find(sub) ?: throw IllegalArgumentException("找不到 cleanText 字段")
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    /**
     * 带退避重试的 LLM 调用 + JSON 自愈解析。
     * - 网络/HTTP 错误：最多重试 3 次，指数退避 (2s / 4s / 8s)
     * - JSON 解析错误：用 JsonRecovery 抢救；仍失败则重试 1 次（提示模型只输出 JSON）
     * 返回 null 表示重试耗尽。
     */
    private suspend fun callWithRetry(
        system: String,
        user: String,
        chapterIndex: Int,
        onError: (String) -> Unit
    ): LlmDeltaResponse? {
        val maxAttempts = 3
        var lastErr: Throwable? = null
        // JSON 解析失败时，重试追加「只输出 JSON」强提示
        var currentUser = user
        for (attempt in 1..maxAttempts) {
            val raw = llm.chatJson(system, currentUser)
            if (raw.isFailure) {
                lastErr = raw.exceptionOrNull()
                val msg = lastErr?.message.orEmpty()
                // 5xx / 超时 / 限流 才重试；4xx（鉴权错）直接放弃
                val m = msg.lowercase()
                val retryable = m.contains("http 5") || m.contains("timeout") ||
                    m.contains("timed out") || m.contains("429") || m.contains("rate") ||
                    m.contains("connection") || m.contains("reset") || m.contains("unable to resolve host")
                if (!retryable || attempt == maxAttempts) {
                    onError("第 $chapterIndex 章调用失败: $msg")
                    return null
                }
                val backoff = (1L shl attempt) * 1000L // attempt=1→2s, attempt=2→4s
                onError("第 $chapterIndex 章调用失败，${backoff / 1000}s 后重试($attempt/$maxAttempts): $msg")
                delay(backoff)
                continue
            }
            // JSON 解析（带自愈）
            val parsed = runCatching { JsonRecovery.parseDelta(raw.getOrThrow()) }
            if (parsed.isSuccess) return parsed.getOrThrow()
            lastErr = parsed.exceptionOrNull()
            if (attempt == maxAttempts) {
                onError("第 $chapterIndex 章 JSON 解析失败: ${lastErr?.message}")
                return null
            }
            onError("第 $chapterIndex 章 JSON 解析失败，重试($attempt/$maxAttempts)")
            // 重试时追加强提示，引导模型只输出纯 JSON
            if (attempt == 1) {
                currentUser = user + "\n\n【重要】请只输出合法 JSON，不要任何解释或代码块标记。"
            }
            delay(2000L)
        }
        return null
    }
}
