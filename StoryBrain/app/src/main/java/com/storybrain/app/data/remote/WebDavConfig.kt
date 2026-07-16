package com.storybrain.app.data.remote

/**
 * WebDAV 服务器配置，持久化在 [com.storybrain.app.data.SettingsManager]。
 * 用于书库（所有书籍的章节/分析/记忆盘）云端备份与恢复。
 */
data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** 远程存放备份的目录，自动创建；所有备份 zip 存在此目录下 */
    val remotePath: String = "/StoryBrain"
) {
    /** 仅校验必填项：服务器地址与远程路径非空 */
    fun isValid(): Boolean = serverUrl.isNotBlank() && remotePath.isNotBlank()

    /** 拼接远程文件完整路径（确保 path 与 fileName 间有且仅有一个 `/`） */
    fun fullRemotePath(fileName: String): String {
        val base = if (remotePath.endsWith('/')) remotePath else "$remotePath/"
        return "$base$fileName"
    }
}
