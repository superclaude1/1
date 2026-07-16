package com.storybrain.app.data.local

import android.content.Context
import android.util.LruCache
import com.storybrain.app.data.model.AppJson
import com.storybrain.app.data.model.Chapter
import com.storybrain.app.data.model.StoryBrain
import com.storybrain.app.data.model.ChatMessage
import com.storybrain.app.data.model.CharacterEntry
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 本地持久化：
 * - chapters/ 下存 ch_0001.txt … ch_NNNN.txt（章节切片）
 * - chapters/ch_index.json 存章节元信息
 * - story_brain.json 存“记忆盘”
 * 每本书一个独立目录，按 bookId 隔离。
 *
 * 内存缓存：章节正文 LruCache（翻页热点）+ 记忆盘单实例缓存（避免每章都读盘）。
 */
class BookRepository(private val context: Context) {

    /** 书库根目录（所有书按 bookId 分子目录存放）。internal 供 WebDavBackupService 打包整库 */
    internal val rootDir: File by lazy {
        File(context.filesDir, "books").apply { mkdirs() }
    }

    /** 章节正文缓存：约 32 章 × 数十 KB，覆盖预读窗口，显著减少翻页 IO */
    private val textCache = LruCache<String, String>(32)
    /** 记忆盘缓存：仅缓存最近一本书，写回时同步更新。
     *  @Volatile 保证 Worker 线程写、Reader 线程读的可见性（Pair 本身不可变，引用可见即安全） */
    @Volatile
    private var brainCache: Pair<String, StoryBrain>? = null

    fun books(): List<File> = rootDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

    fun bookDir(bookId: String): File = File(rootDir, bookId).apply { mkdirs() }

    fun chaptersDir(bookId: String): File = File(bookDir(bookId), "chapters").apply { mkdirs() }

    /** 把整书切分后逐章写入磁盘（模拟物理切片 ch_0001.txt …）
     *  章节文件先写到临时目录，全部成功后再原子替换，防止导入中断留下半写状态。
     *  替换顺序：先把新章节迁入目标目录，全部成功后才删旧目录——
     *  避免"先删旧再迁新"时 rename 失败导致数据丢失（跨挂载点场景）。 */
    fun saveChapters(bookId: String, chapters: List<Chapter>) {
        val dir = chaptersDir(bookId)
        val tmpDir = File(bookDir(bookId), "chapters_tmp").apply { mkdirs() }
        // 先写临时目录
        chapters.forEach { ch ->
            File(tmpDir, ch.fileName).writeText(ch.rawText, Charsets.UTF_8)
        }
        // 写索引到临时文件
        val indexTmp = File(bookDir(bookId), "ch_index.json.tmp")
        indexTmp.writeText(AppJson.encodeToString(chapters.map { it.id to it.title }), Charsets.UTF_8)
        // 全部成功 → 替换。先把旧目录改名（而非删除），再迁入新章节，
        // 迁移全部成功后再删旧目录，保证中途失败时数据不丢。
        val oldBackup = File(bookDir(bookId), "chapters_old")
        if (oldBackup.exists()) oldBackup.deleteRecursively()
        if (dir.exists() && !dir.renameTo(oldBackup)) {
            // rename 失败（极少见），回退到直接删旧目录
            dir.deleteRecursively()
        }
        dir.mkdirs()
        var migrateOk = true
        tmpDir.listFiles()?.forEach { f ->
            if (!f.renameTo(File(dir, f.name))) migrateOk = false
        }
        if (migrateOk) {
            tmpDir.delete()
            // 迁移成功 → 写索引 + 删旧备份
            val indexFile = File(bookDir(bookId), "ch_index.json")
            if (indexFile.exists()) indexFile.delete()
            if (!indexTmp.renameTo(indexFile)) {
                indexFile.writeText(indexTmp.readText(), Charsets.UTF_8)
                indexTmp.delete()
            }
            oldBackup.deleteRecursively()
        } else {
            // 迁移失败 → 回滚：删半迁的 dir，恢复 oldBackup
            dir.deleteRecursively()
            if (oldBackup.exists()) oldBackup.renameTo(dir)
            tmpDir.deleteRecursively()
            error("章节写入失败（迁移中断），已回滚到旧版本")
        }
        // 章节被替换：清空正文缓存，记忆盘也作废
        textCache.evictAll()
        if (brainCache?.first == bookId) brainCache = null
    }

    /** 读取单章正文（优先走内存缓存） */
    fun loadChapterText(bookId: String, chapterId: String): String {
        val key = "$bookId/$chapterId"
        textCache.get(key)?.let { return it }
        val text = File(chaptersDir(bookId), "$chapterId.txt").readText(Charsets.UTF_8)
        textCache.put(key, text)
        return text
    }

    /** 读取章节索引；损坏时返回空列表并删除损坏文件（避免每次启动都报错） */
    fun loadChapterIndex(bookId: String): List<Pair<String, String>> {
        val f = File(bookDir(bookId), "ch_index.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<List<Pair<String, String>>>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            // 索引损坏：删除损坏文件，返回空列表，避免后续每次都抛异常
            f.delete()
            emptyList()
        }
    }

    /** 读取“记忆盘”（优先走内存缓存） */
    fun loadBrain(bookId: String): StoryBrain? {
        brainCache?.let { if (it.first == bookId) return it.second }
        val f = File(bookDir(bookId), "story_brain.json")
        if (!f.exists()) return null
        val brain = runCatching { AppJson.decodeFromString<StoryBrain>(f.readText(Charsets.UTF_8)) }.getOrNull()
        if (brain != null) brainCache = bookId to brain
        return brain
    }

    /** 覆盖写回“记忆盘”（Step4 每章覆盖写回，作为下一章输入源）
     *  原子写入：先写临时文件再 rename，防止写盘中断导致文件损坏 */
    fun saveBrain(bookId: String, brain: StoryBrain) {
        atomicWrite(File(bookDir(bookId), "story_brain.json"), AppJson.encodeToString(brain))
        brainCache = bookId to brain
    }

    /** 存档本章对话/旁白分离结果（同样原子写入，保证一致性） */
    fun saveChapterAnalysis(bookId: String, chapterId: String, dialogueJson: String, narration: String) {
        val dir = File(bookDir(bookId), "analysis").apply { mkdirs() }
        atomicWrite(File(dir, "${chapterId}_dialogue.json"), dialogueJson)
        atomicWrite(File(dir, "${chapterId}_narration.txt"), narration)
    }

    /**
     * 原子写入：先写 .tmp 再 rename 到目标，防止写盘中断导致文件损坏。
     * rename 失败（某些设备/文件系统）时回退到直接写 + 删 tmp。
     */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun loadChapterAnalysis(bookId: String, chapterId: String): Pair<String, String>? {
        val d = File(bookDir(bookId), "analysis/${chapterId}_dialogue.json")
        val n = File(bookDir(bookId), "analysis/${chapterId}_narration.txt")
        if (!d.exists() && !n.exists()) return null
        return (if (d.exists()) d.readText() else "") to (if (n.exists()) n.readText() else "")
    }

    /**
     * 全书搜索：在每章正文中查找关键词，返回命中片段。
     * 直接读盘不走 textCache，避免全书扫描冲垮翻页热缓存。
     * @param query 关键词（大小写不敏感）
     * @param maxResults 最多返回多少条命中
     * @return 列表：chapterId, chapterIndex, chapterTitle, snippet(带上下文)
     */
    fun search(bookId: String, query: String, maxResults: Int = 50): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val index = loadChapterIndex(bookId)
        val results = mutableListOf<SearchHit>()
        for ((chapterId, chapterTitle) in index) {
            val text = runCatching { readChapterTextDirect(bookId, chapterId) }.getOrNull() ?: continue
            var start = 0
            while (start < text.length) {
                val pos = text.indexOf(q, start, ignoreCase = true)
                if (pos < 0) break
                val snippetStart = (pos - 30).coerceAtLeast(0)
                val snippetEnd = (pos + q.length + 30).coerceAtMost(text.length)
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < text.length) "…" else ""
                val snippet = prefix + text.substring(snippetStart, snippetEnd) + suffix
                val chapterIndex = chapterId.removePrefix("ch_").toIntOrNull() ?: 0
                results.add(SearchHit(chapterId, chapterIndex, chapterTitle, snippet, pos))
                if (results.size >= maxResults) return results
                start = pos + q.length
            }
        }
        return results
    }

    /**
     * 查找角色出场章节：用 name + aliases 在每章正文中匹配，
     * 每章返回首个命中（取所有名字中最早出现的位置），用于角色详情页的"出场章节"列表。
     * 直接读盘不走 textCache，避免全书扫描冲垮翻页热缓存。
     */
    fun findCharacterAppearances(bookId: String, names: List<String>): List<SearchHit> {
        val queries = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (queries.isEmpty()) return emptyList()
        val index = loadChapterIndex(bookId)
        val results = mutableListOf<SearchHit>()
        for ((chapterId, chapterTitle) in index) {
            val text = runCatching { readChapterTextDirect(bookId, chapterId) }.getOrNull() ?: continue
            var bestPos = -1
            var bestLen = 0
            for (q in queries) {
                val pos = text.indexOf(q, ignoreCase = true)
                if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                    bestPos = pos; bestLen = q.length
                }
            }
            if (bestPos >= 0) {
                val snippetStart = (bestPos - 30).coerceAtLeast(0)
                val snippetEnd = (bestPos + bestLen + 30).coerceAtMost(text.length)
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < text.length) "…" else ""
                val snippet = prefix + text.substring(snippetStart, snippetEnd) + suffix
                val chapterIndex = chapterId.removePrefix("ch_").toIntOrNull() ?: 0
                results.add(SearchHit(chapterId, chapterIndex, chapterTitle, snippet, bestPos))
            }
        }
        return results
    }

    /**
     * 单次读取所有章节，统计所有角色的出场次数（优化原有的重复整书读盘瓶颈）。
     * 时间复杂度降低到 O(M)（M为章节数），解决书籍详情页角色列表卡死问题。
     */
    fun countAllCharacterAppearances(bookId: String, characters: Map<String, CharacterEntry>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        if (characters.isEmpty()) return result
        
        // 预处理角色的查询词列表
        val charQueries = characters.map { (charId, entry) ->
            charId to (listOf(entry.name) + entry.aliases).map { it.trim() }.filter { it.isNotEmpty() }
        }
        
        val index = loadChapterIndex(bookId)
        for ((chapterId, _) in index) {
            val text = runCatching { readChapterTextDirect(bookId, chapterId) }.getOrNull() ?: continue
            for ((charId, queries) in charQueries) {
                if (queries.isEmpty()) continue
                var matched = false
                for (q in queries) {
                    if (text.contains(q, ignoreCase = true)) {
                        matched = true
                        break
                    }
                }
                if (matched) {
                    result[charId] = (result[charId] ?: 0) + 1
                }
            }
        }
        return result
    }

    /** 直接读盘，不经 textCache（仅供搜索/扫描用，避免冲垮翻页热缓存）。
     *  限制单章读取上限 2MB，防止异常大文件导致 OOM（搜索/扫描本就只取片段）。 */
    private fun readChapterTextDirect(bookId: String, chapterId: String): String {
        val f = File(chaptersDir(bookId), "$chapterId.txt")
        val maxBytes = 2L * 1024 * 1024
        if (f.length() <= maxBytes) return f.readText(Charsets.UTF_8)
        // 超大文件只读前 2MB 做搜索（网文章节正常 < 50KB，2MB 已是极端）
        val buf = ByteArray(maxBytes.toInt())
        f.inputStream().use { input ->
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }
    }

    data class SearchHit(
        val chapterId: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val snippet: String,
        val position: Int
    )

    fun bookTitle(bookId: String): String =
        File(bookDir(bookId), "title.txt").let { if (it.exists()) it.readText() else bookId }

    fun setBookTitle(bookId: String, title: String) {
        File(bookDir(bookId), "title.txt").writeText(title, Charsets.UTF_8)
    }

    /** 根据书名查找已存在的 bookId，用于去重检测 */
    fun findBookByTitle(title: String): String? {
        return books().firstOrNull { dir ->
            File(dir, "title.txt").let { it.exists() && it.readText() == title }
        }?.name
    }

    fun createBookId(displayName: String): String {
        val safe = displayName.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5]"), "").take(20)
        return (safe.ifEmpty { "book" }) + "_" + System.currentTimeMillis()
    }

    /** 删除一本书及其所有数据（章节/分析/记忆盘） */
    fun deleteBook(bookId: String) {
        if (brainCache?.first == bookId) brainCache = null
        bookDir(bookId).deleteRecursively()
        // 清理该书相关的正文缓存项
        // LruCache 没有按前缀删除的 API，evictAll 最简单稳妥
        textCache.evictAll()
    }

    /**
     * 清空所有内存缓存（恢复备份后调用）。
     * 恢复操作直接覆盖磁盘文件，但 brainCache / textCache 仍持有旧数据，
     * 不清空会导致 Reader 读到恢复前的旧记忆盘与旧正文。
     */
    fun clearCaches() {
        brainCache = null
        textCache.evictAll()
    }

    fun loadCharacterChat(bookId: String, charId: String): List<ChatMessage> {
        val file = File(bookDir(bookId), "chat_$charId.json")
        if (!file.exists()) return emptyList()
        return try {
            AppJson.decodeFromString<List<ChatMessage>>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCharacterChat(bookId: String, charId: String, messages: List<ChatMessage>) {
        val file = File(bookDir(bookId), "chat_$charId.json")
        try {
            file.writeText(AppJson.encodeToString(messages), Charsets.UTF_8)
        } catch (e: Exception) {
            // ignore
        }
    }
}
