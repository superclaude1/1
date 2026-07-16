package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/**
 * 局部人物网 (Local Character Network) —— 嵌套在每个剧情节点内部。
 * 只记录当前剧情单元内活跃的角色 ID 及其在该时空背景下的网状人际关系。
 */
@Serializable
data class CharacterLink(
    val from: String = "",        // 角色 ID
    val to: String = "",          // 角色 ID
    val relation: String = "",    // "生死之交" / "对立阵营" / "师徒" ...
    val context: String = "" // 该关系的时空背景说明
)

@Serializable
data class LocalCharacterNetwork(
    /** 当前剧情单元内活跃的角色 ID 列表 */
    val activeCharacters: MutableList<String> = mutableListOf(),
    /** 该时空下的网状人际关系 */
    val links: MutableList<CharacterLink> = mutableListOf()
)
