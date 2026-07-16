package com.storybrain.app.pipeline

import com.storybrain.app.data.model.StoryBrain
import com.storybrain.app.data.model.PlotNode
import com.storybrain.app.data.model.LocalCharacterNetwork
import com.storybrain.app.data.model.GlobalCharacterRegistry

/**
 * Prompt 构造器：
 * - 初始化 prompt（前 15 章合并投喂 → 生成根节点 + 全局角色索引）
 * - 增量 prompt（单章 3000 字 + 上一状态折叠视图 → Delta 增量改动）
 *
 * 约束模型只输出严格 JSON，schema 见 LlmDeltaResponse。
 */
object PromptBuilder {

    private const val INIT_CHAPTER_COUNT = 15

    fun chapterCountForInit(): Int = INIT_CHAPTER_COUNT

    private val SCHEMA_DESC = """
你是一个小说“故事大脑”维护引擎。你必须且只能输出一个 JSON 对象，结构如下（字段名严格一致）：

{
  "routing": { "action": "continue|branch|merge", "targetNodeId": "node_xxx", "newParentIds": ["node_xxx"] },
  "registryUpdates": { "char_xxx": { "name": "标准名", "aliases": ["别称1"], "avatarSeed": "首字" } },
  "nodeOps": [
    { "op": "create|update|activate|suspend|complete", "nodeId": "node_xxx",
      "title": "剧情单元标题", "summary": "摘要", "chapterRange": "ch_0001-ch_0015",
      "activeCharacters": ["char_xxx"],
      "links": [ { "from": "char_xxx", "to": "char_xxx", "relation": "生死之交", "context": "" } ] }
  ],
  "dialogues": [ { "speakerId": "char_xxx 或 null", "speakerName": "可识别的说话人名(未知填'未知')", "content": "台词原文", "chapterId": "ch_xxxx" } ],
  "narrationAnalysis": "本章纯旁白环境描写分析(背景/氛围/时间空间/伏笔)"
}

规则：
1. routing.action：本章延续当前节点用 continue；开启新支线用 branch(需在 nodeOps 里 create 新节点, newParentIds 给父节点)；多线合并用 merge。
2. registryUpdates 只包含本章新出场或别称有变化的角色，不要重复已有角色。
3. nodeOps 只给本章需要 create/update/activate/suspend/complete 的节点，增量改动。
4. dialogues 是本章【纯对话结构】，每句一条，speakerName 尽量从上下文(前文“某某道/说/笑道”)识别说话人，识别不出填"未知"且 speakerId 给 null。
5. narrationAnalysis 是本章【纯旁白环境描写】，不含任何对话。
6. 不要输出任何解释文字，只输出 JSON 对象本身。
""".trimIndent()

    /** Step3：初始化 prompt（前 15 章合并投喂） */
    fun buildInitSystem(): String = """
$SCHEMA_DESC

【当前任务：故事大脑初始化】
请基于开篇约前 ${INIT_CHAPTER_COUNT} 章文本：
- 创建全局角色索引（所有出场人物，去重，归并别称）；
- 识别故事开篇背景，创建 Plot Graph 的根节点（Root Node, id 用 "node_root"）；
- routing.action 用 "branch"，targetNodeId 用 "node_root"，newParentIds 为 []；
- nodeOps 里 create "node_root" 并填 title/summary/activeCharacters/links；
- dialogues 汇总开篇关键对话，narrationAnalysis 给开篇环境总述。
""".trimIndent()

    fun buildInitUser(bookTitle: String, mergedText: String): String = """
书名：$bookTitle
以下是开篇前 $INIT_CHAPTER_COUNT 章合并文本（已做章节切片与对话/旁白初分标签）：

$mergedText
""".trimIndent()

    /** Step4：增量 prompt（单章） */
    fun buildIncrementalSystem(): String = """
$SCHEMA_DESC

【当前任务：单章滚动增量更新】
下面给出【上一状态折叠后的记忆盘上下文】(仅全局索引 + 当前活跃/挂起节点, 已完成陈旧节点已折叠) 与【当前这一章正文】。
请执行数据管理(CRUD)指令：
1. 路由判定(树网分合)：判断本章是延续当前节点(continue)、新开子支线(branch)、还是多线合并(merge)。
2. 增量修改(Update)：针对本章新出场人物或关系改变，仅修改当前活跃剧情节点内部的局部人物网，旧节点历史关系不动。
3. 精细化分离：修正并输出本章最终的【纯对话结构 dialogues】与【纯旁白环境描写 narrationAnalysis】。
只输出 JSON 对象。
""".trimIndent()

    fun buildIncrementalUser(
        brain: StoryBrain,
        chapterId: String,
        chapterTitle: String,
        chapterText: String
    ): String {
        // registry 软截断：超长小说角色多，全量投喂会挤占章节正文 token 预算
        val registryRaw = brain.globalRegistry.characters.entries.joinToString("\n") {
            "  ${it.key}: ${it.value.name} (别称: ${it.value.aliases.joinToString("/")})"
        }
        val registry = when {
            registryRaw.isEmpty() -> "  (暂无)"
            registryRaw.length > MAX_REGISTRY_CHARS -> registryRaw.take(MAX_REGISTRY_CHARS) + "\n  …(已截断，仅显示部分角色)"
            else -> registryRaw
        }

        val activeNodes = brain.activeNodes().joinToString("\n\n") { n ->
            buildString {
                append("节点 ${n.id} [${n.status}] 《${n.title}》\n")
                append("  摘要: ${n.summary}\n")
                append("  章节范围: ${n.chapterRange}\n")
                append("  父节点: ${n.parentNodes.joinToString(",")}\n")
                append("  活跃角色: ${n.localNetwork.activeCharacters.joinToString(",")}\n")
                append("  人物关系: ${n.localNetwork.links.joinToString(" ; ") { "${it.from}-${it.relation}->${it.to}" }}")
            }
        }.ifEmpty { "  (暂无活跃节点)" }

        return """
【上一状态记忆盘上下文（折叠视图）】
全局角色索引:
$registry

当前活跃/挂起剧情节点:
$activeNodes

【当前这一章正文】
章节: $chapterId  $chapterTitle
$chapterText
""".trimIndent()
    }

    /** 角色索引投喂软上限（字符数），超出截断避免挤占章节正文 token 预算 */
    private const val MAX_REGISTRY_CHARS = 3000

    // —— AI 深度去广告 prompt ——

    fun buildCleanSystem(): String = """
你是一个小说文本清洗器。你的任务是从原始小说章节中删除广告、推广、求票、网站水印、防盗版提示、章节重复等与小说正文无关的内容。

规则：
1. 只保留小说正文内容（叙事、描写、对话）。
2. 删除：网站推广语（"本书首发于…""手机用户请…""笔趣阁…"）、求票求收藏求打赏、书友群/公众号推广、防盗版水印、章节标题重复出现、"本章完/未完待续"类收尾语。
3. 保留：作者的话、请假条、上架感言等作者与读者的正常互动内容；正文内容一字不改。
4. 输出必须是严格的 JSON 对象，结构如下：
{ "cleanText": "清洗后的完整正文（保留原换行）", "removedCount": 删掉的段落数 }
5. 不要输出任何解释文字，只输出 JSON。
""".trimIndent()

    fun buildCleanUser(chapterTitle: String, chapterText: String): String = """
章节标题：$chapterTitle

原始章节内容：
$chapterText

请输出清洗后的 JSON。
""".trimIndent()
}
