package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/**
 * 树网状剧情节点拓扑 (Plot Graph Nodes)
 * 每个节点代表一个剧情单元（一个副本 / 一条支线）。
 * parentNodes / childNodes 实现 剧情分叉(树状) 与 汇聚(网状)。
 */
@Serializable
data class PlotNode(
    val id: String,                       // node_001
    val title: String,                    // 剧情单元标题
    val chapterRange: String = "",        // "ch_0001-ch_0015"
    val summary: String = "",             // 该剧情单元摘要
    val parentNodes: MutableList<String> = mutableListOf(),
    val childNodes: MutableList<String> = mutableListOf(),
    /** active(活跃) | suspended(挂起) | completed(已完成，陈旧折叠隐藏) */
    val status: String = STATUS_ACTIVE,
    val localNetwork: LocalCharacterNetwork = LocalCharacterNetwork(),
    /** 该节点累积的对话分离结果（每章追加） */
    val dialogues: MutableList<DialogueLine> = mutableListOf(),
    /** 该节点累积的旁白环境描写分析 */
    val narrationAnalysis: String = ""
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_COMPLETED = "completed"
    }
}
