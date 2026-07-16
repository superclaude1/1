package com.storybrain.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 故事大脑 (Story Brain) —— 整个小说世界的动态演变的图结构 JSON。
 * 由三部分组成：全局角色索引表 + 树网状剧情节点拓扑 + (节点内嵌)局部人物网。
 *
 * 这是滚动流水线的“记忆盘”：每章分析都基于且只修改上一次的结果。
 */
@Serializable
data class StoryBrain(
    val version: Int = 1,
    val bookTitle: String = "",
    val globalRegistry: GlobalCharacterRegistry = GlobalCharacterRegistry(),
    /** 所有剧情节点。id -> node */
    val nodes: MutableMap<String, PlotNode> = mutableMapOf(),
    /** 当前活跃 + 挂起节点 ID（已完成陈旧节点自动折叠隐藏，不参与投喂） */
    val activeNodeIds: MutableList<String> = mutableListOf(),
    val rootNodeId: String? = null,
    /** 已处理到的章节序号（1-based） */
    val processedChapterCount: Int = 0,
    /** 全书总章数 */
    val totalChapters: Int = 0
) {
    fun activeNodes(): List<PlotNode> = activeNodeIds.mapNotNull { nodes[it] }

    /** 折叠陈旧节点：只保留 active + suspended 节点用于上下文投喂 */
    fun compactContextView(): StoryBrain {
        val keep = activeNodeIds.toSet()
        return copy(
            nodes = nodes.filterKeys { it in keep || it == rootNodeId }.toMutableMap()
        )
    }
}
