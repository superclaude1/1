package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

/**
 * 全局角色索引表 (Global Character Registry)
 * 仅记录人名唯一 ID、标准名、所有别称。用于全局去重，防止数据臃肿。
 * 形如: {"char_001": {"name":"张小凡","aliases":["鬼厉","小凡"]}}
 */
@Serializable
data class CharacterEntry(
    val name: String = "未知",
    val aliases: List<String> = emptyList(),
    /** 角色头像首字（本地生成，避免网络依赖） */
    val avatarSeed: String = "书"
)

@Serializable
data class GlobalCharacterRegistry(
    val characters: MutableMap<String, CharacterEntry> = mutableMapOf()
) {
    /** 按标准名/别称反查 ID，找不到返回 null */
    fun lookupId(rawName: String): String? {
        characters.forEach { (id, entry) ->
            if (entry.name == rawName || entry.aliases.contains(rawName)) return id
        }
        return null
    }

    fun nextId(): String {
        val max = characters.keys.mapNotNull { it.removePrefix("char_").toIntOrNull() }.maxOrNull() ?: 0
        return "char_%03d".format(max + 1)
    }
}
