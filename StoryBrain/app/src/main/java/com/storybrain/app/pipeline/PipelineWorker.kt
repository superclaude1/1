package com.storybrain.app.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.storybrain.app.StoryBrainApp
import com.storybrain.app.data.model.Chapter

/**
 * Step5：后台异步流水线 Worker。
 * AI 以 3~5 秒/章速度自动向前吞噬分析，人类阅读期间后台已把后续章节增量分析跑完 → 零等待。
 *
 * 输入：bookId, aheadCount(预读章数, 即比 reader 当前位置多跑多少章)
 * 输出：通过 progress + 输出数据回传当前进度。
 */
class PipelineWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val aheadCount = inputData.getInt(KEY_AHEAD, 30)

        val app = applicationContext as StoryBrainApp
        val repo = app.bookRepository
        val llm = app.makeLlmClient() ?: return Result.failure(workDataOf(KEY_ERROR to "未配置 LLM API Key"))
        val aiClean = app.settingsManager.loadAiCleanEnabled()

        // 从磁盘重新加载章节索引（rawText 留空，processChapter 时按需加载，命中 textCache）
        val index = repo.loadChapterIndex(bookId)
        if (index.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "无章节索引"))
        val chapters = index.map { (id, title) ->
            Chapter(index = id.removePrefix("ch_").toIntOrNull() ?: 0, id = id, title = title,
                rawText = "")
        }.sortedBy { it.index }

        val brain = repo.loadBrain(bookId)
        var nextChapter = brain?.processedChapterCount ?: 0
        // 是否需要初始化：brain 不存在，或存在但 processedChapterCount==0（初始化中途失败留下的空壳）
        // 注意：不能用 nextChapter < chapterCountForInit() 判断，否则短篇（章数<initN）会反复重新初始化
        val needInit = brain == null || nextChapter == 0
        if (needInit) {
            // 尚未初始化 → 先 Step3
            val orch = PipelineOrchestrator(repo, llm, aiClean, workerListener())
            val initRes = orch.initialize(bookId, chapters)
            if (initRes.isFailure) {
                val msg = initRes.exceptionOrNull()?.message ?: "初始化失败"
                setProgressAsync(workDataOf(KEY_ERROR to msg))
                // 鉴权错等不可恢复错误直接 fail；网络/限流让 WorkManager 稍后重试
                return if (isRetryable(msg)) Result.retry() else Result.failure(workDataOf(KEY_ERROR to msg))
            }
            nextChapter = (initRes.getOrThrow().processedChapterCount).coerceAtLeast(1)
        }

        val orch = PipelineOrchestrator(repo, llm, aiClean, workerListener())
        // 无感预加载：只跑到 已处理 + aheadCount，而非全书。
        // 大书不再一次性占用 Worker 数小时；reader 翻页时会用 ensureAhead 续跑下一段。
        val targetEnd = (nextChapter + aheadCount).coerceAtMost(chapters.size)
        if (nextChapter < targetEnd) {
            val res = orch.runRolling(bookId, chapters, nextChapter + 1, targetEnd)
            if (res.isFailure) {
                val msg = res.exceptionOrNull()?.message ?: "流水线失败"
                setProgressAsync(workDataOf(KEY_ERROR to msg))
                return if (isRetryable(msg)) Result.retry() else Result.failure(workDataOf(KEY_ERROR to msg))
            }
        }
        val partial = targetEnd < chapters.size
        setProgressAsync(workDataOf(KEY_CHAPTER to targetEnd, KEY_TOTAL to chapters.size))
        return Result.success(workDataOf(KEY_DONE to true, KEY_PARTIAL to partial, KEY_CHAPTER to targetEnd, KEY_TOTAL to chapters.size))
    }

    /** 网络/限流/服务端错误可重试；鉴权/参数错不重试 */
    private fun isRetryable(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("http 5") || m.contains("timeout") || m.contains("timed out") ||
            m.contains("429") || m.contains("rate") || m.contains("unable to resolve host") ||
            m.contains("connection") || m.contains("reset")
    }

    private fun workerListener() = object : PipelineListener {
        override fun onStage(stage: String, detail: String) {
            setProgressAsync(workDataOf(KEY_STAGE to stage, KEY_DETAIL to detail))
        }
        override fun onChapterDone(chapterIndex: Int, total: Int) {
            setProgressAsync(workDataOf(KEY_CHAPTER to chapterIndex, KEY_TOTAL to total))
        }
        override fun onError(chapterIndex: Int, message: String) {
            setProgressAsync(workDataOf(KEY_ERROR to message, KEY_CHAPTER to chapterIndex))
        }
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_AHEAD = "ahead"
        const val KEY_CHAPTER = "chapter"
        const val KEY_TOTAL = "total"
        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_ERROR = "error"
        const val KEY_DONE = "done"
        /** true=仅完成预读窗口（仍有未分析章节）；false=全书已跑完 */
        const val KEY_PARTIAL = "partial"

        fun uniqueName(bookId: String) = "pipeline_$bookId"

        /** 首次启动（覆盖已有任务） */
        fun start(context: Context, bookId: String, aheadCount: Int = 30) {
            enqueue(context, bookId, aheadCount, ExistingWorkPolicy.REPLACE)
        }

        /**
         * 续跑预加载。
         * reader 翻页时调用：若 Worker 仍在运行(REPLACE 会取消重排)，则由内部
         * processedChapterCount 判断无需重跑；若已完成(SUCCEEDED)则 REPLACE 重新启动，
         * Worker 内部会从已处理章节后续跑，不丢失进度。
         *
         * 注意：不能用 KEEP，因为已完成(SUCCEEDED/FAILED)的 Work 也被视为"已存在"，
         * KEEP 不会重新启动，导致用户读完已分析部分后无法续跑后续章节。
         */
        fun ensureAhead(context: Context, bookId: String, aheadCount: Int = 30) {
            enqueue(context, bookId, aheadCount, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, bookId: String, aheadCount: Int, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<PipelineWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId, KEY_AHEAD to aheadCount))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(bookId), policy, req)
        }

        fun stop(context: Context, bookId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bookId))
        }
    }
}
