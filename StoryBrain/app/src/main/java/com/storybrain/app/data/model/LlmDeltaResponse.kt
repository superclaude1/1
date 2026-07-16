package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/**
 * LLM 单章增量改动指令 (Delta)。
 * AI 每章执行“数据管理(CRUD)指令”后返回该结构，由 Orchestrator 合并写入 story_brain.json。
 */
@Serializable
data class LlmDeltaResponse(
    /** 路由判定：continue 延续当前节点 / branch 新开子节点(分叉) / merge 多线合并(激活聚合节点) */
    val routing: RoutingDecision = RoutingDecision(),
    /** 角色索引增量：新增或更新的角色。key=charId, value=条目 */
    val registryUpdates: Map<String, CharacterEntry> = emptyMap(),
    /** 节点操作序列 */
    val nodeOps: List<NodeOp> = emptyList(),
    /** 本章精细化分离后的【纯对话结构】 */
    val dialogues: List<DialogueLine> = emptyList(),
    /** 本章【纯旁白环境描写分析】 */
    val narrationAnalysis: String = ""
)

@Serializable
data class RoutingDecision(
    val action: String = "continue",                 // continue | branch | merge
    val targetNodeId: String = "node_root",           // 操作的目标节点 ID
    val newParentIds: List<String> = emptyList()  // branch/merge 时的父节点
)

@Serializable
data class NodeOp(
    val op: String = "update",                     // create | update | activate | suspend | complete
    val nodeId: String = "",
    val title: String? = null,
    val summary: String? = null,
    val chapterRange: String? = null,
    val activeCharacters: List<String>? = null,
    val links: List<CharacterLink>? = null
)
