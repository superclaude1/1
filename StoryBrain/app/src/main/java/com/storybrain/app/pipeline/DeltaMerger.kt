package com.storybrain.app.pipeline

import com.storybrain.app.data.model.*

/**
 * 把 LLM 返回的 Delta 增量改动合并进 StoryBrain“记忆盘”。
 * 旧节点历史关系保持不动，只动当前活跃节点内部的局部人物网。
 *
 * 注意：全程深拷贝，不修改入参 brain 的任何可变集合，避免污染 BookRepository 缓存。
 */
object DeltaMerger {

    fun merge(brain: StoryBrain, delta: LlmDeltaResponse, currentChapter: Chapter): StoryBrain {
        val registry = brain.globalRegistry.deepCopy()
        // 深拷贝所有节点，确保后续 add/clear 不影响原 brain
        val nodes = brain.nodes.mapValues { (_, node) -> node.deepCopy() }.toMutableMap()
        val activeIds = brain.activeNodeIds.toMutableList()

        // 1) 角色索引增量
        delta.registryUpdates.forEach { (id, entry) ->
            registry.characters[id] = entry
        }

        // 2) 节点操作
        delta.nodeOps.forEach { op ->
            when (op.op) {
                "create" -> {
                    val node = PlotNode(
                        id = op.nodeId,
                        title = op.title ?: "未命名",
                        summary = op.summary ?: "",
                        chapterRange = op.chapterRange ?: currentChapter.id,
                        status = PlotNode.STATUS_ACTIVE,
                        parentNodes = delta.routing.newParentIds.toMutableList(),
                        localNetwork = LocalCharacterNetwork(
                            activeCharacters = (op.activeCharacters ?: emptyList()).toMutableList(),
                            links = (op.links ?: emptyList()).toMutableList()
                        )
                    )
                    nodes[op.nodeId] = node
                    if (op.nodeId !in activeIds) activeIds.add(op.nodeId)
                    // 建立父子拓扑（操作的是深拷贝后的节点）
                    delta.routing.newParentIds.forEach { pid ->
                        nodes[pid]?.let { parent ->
                            nodes[pid] = parent.copy(childNodes = (parent.childNodes + op.nodeId).toMutableList())
                        }
                    }
                }
                "update" -> {
                    val existing = nodes[op.nodeId] ?: return@forEach
                    // 构造全新的 LocalCharacterNetwork，不复用 existing 的 list
                    val newNet = LocalCharacterNetwork(
                        activeCharacters = (op.activeCharacters ?: existing.localNetwork.activeCharacters).toMutableList(),
                        links = (op.links ?: existing.localNetwork.links).toMutableList()
                    )
                    nodes[op.nodeId] = existing.copy(
                        title = op.title ?: existing.title,
                        summary = op.summary ?: existing.summary,
                        chapterRange = op.chapterRange ?: existing.chapterRange,
                        localNetwork = newNet
                    )
                }
                "activate" -> {
                    nodes[op.nodeId]?.let { n ->
                        nodes[op.nodeId] = n.copy(status = PlotNode.STATUS_ACTIVE)
                        if (op.nodeId !in activeIds) activeIds.add(op.nodeId)
                    }
                }
                "suspend" -> {
                    nodes[op.nodeId]?.let { n ->
                        nodes[op.nodeId] = n.copy(status = PlotNode.STATUS_SUSPENDED)
                    }
                }
                "complete" -> {
                    nodes[op.nodeId]?.let { n ->
                        nodes[op.nodeId] = n.copy(status = PlotNode.STATUS_COMPLETED)
                        activeIds.remove(op.nodeId) // 陈旧节点折叠隐藏
                    }
                }
            }
        }

        // 3) 把本章精细化对话/旁白追加进路由目标节点（操作深拷贝后的副本）
        val targetId = delta.routing.targetNodeId
        nodes[targetId]?.let { n ->
            val combined = if (n.narrationAnalysis.isBlank()) {
                delta.narrationAnalysis
            } else {
                n.narrationAnalysis + "\n\n【${currentChapter.id}】\n" + delta.narrationAnalysis
            }
            nodes[targetId] = n.copy(
                dialogues = (n.dialogues + delta.dialogues).toMutableList(),
                narrationAnalysis = combined
            )
        }

        return brain.copy(
            globalRegistry = registry,
            nodes = nodes,
            activeNodeIds = activeIds,
            processedChapterCount = currentChapter.index,
            totalChapters = maxOf(brain.totalChapters, currentChapter.index)
        )
    }

    /** 深拷贝 GlobalCharacterRegistry：只拷贝 Map 容器。
     *  CharacterEntry 本身所有属性为 val 不可变，且 merge 只 put 新 entry 不修改已有 entry，
     *  因此浅拷贝 values 在当前场景安全。若未来 CharacterEntry 添加可变属性需改为真正深拷贝。 */
    private fun GlobalCharacterRegistry.deepCopy() = copy(
        characters = characters.toMutableMap()
    )

    /** 深拷贝 PlotNode 及其内嵌可变集合 */
    private fun PlotNode.deepCopy() = copy(
        parentNodes = parentNodes.toMutableList(),
        childNodes = childNodes.toMutableList(),
        localNetwork = localNetwork.deepCopy(),
        dialogues = dialogues.toMutableList()
    )

    private fun LocalCharacterNetwork.deepCopy() = copy(
        activeCharacters = activeCharacters.toMutableList(),
        links = links.toMutableList()
    )
}
