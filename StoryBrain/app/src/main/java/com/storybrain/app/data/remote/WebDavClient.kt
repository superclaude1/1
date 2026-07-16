package com.storybrain.app.data.remote

import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * WebDAV 客户端：基于 OkHttp 实现，无额外依赖。
 * 覆盖备份恢复所需的操作：连接测试(PROPFIND) / 列目录(PROPFIND) /
 * 上传(PUT) / 下载(GET) / 删除(DELETE)。
 * 认证：HTTP Basic（Base64 编码 username:password）。
 * 复用 [OpenAiCompatClient.sharedHttp] 共享连接池，仅覆盖超时设置。
 */
class WebDavClient(private val config: WebDavConfig) {

    // 复用全局共享 OkHttpClient（连接池/线程池复用）
    private val client = OpenAiCompatClient.sharedHttp.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 生成 HTTP Basic 认证头 */
    private fun authHeader(): String {
        val cred = "${config.username}:${config.password}"
        return "Basic ${Base64.getEncoder().encodeToString(cred.toByteArray())}"
    }

    /** 拼接完整 URL：保证 base 与 path 间有且仅有一个 `/` */
    private fun url(path: String): String {
        val base = if (config.serverUrl.endsWith('/')) config.serverUrl else "${config.serverUrl}/"
        return base + path.removePrefix("/")
    }

    /** 测试连接：PROPFIND Depth=0 探测远程目录是否可达 */
    suspend fun testConnection(): Result<Boolean> {
        return try {
            val req = Request.Builder()
                .url(url(config.remotePath))
                .method("PROPFIND", RequestBody.create(null, "<propfind xmlns=\"DAV:\"><prop><displayname/></prop></propfind>"))
                .addHeader("Authorization", authHeader())
                .addHeader("Content-Type", "application/xml; charset=utf-8")
                .addHeader("Depth", "0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 列出远程目录下的文件名（PROPFIND Depth=1，解析 displayname） */
    suspend fun listFiles(): Result<List<String>> {
        return try {
            val req = Request.Builder()
                .url(url(config.remotePath))
                .method("PROPFIND", RequestBody.create(null, "<propfind xmlns=\"DAV:\"><prop><displayname/></prop></propfind>"))
                .addHeader("Authorization", authHeader())
                .addHeader("Content-Type", "application/xml; charset=utf-8")
                .addHeader("Depth", "1")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: ""
                val names = mutableListOf<String>()
                val regex = Regex("<d:displayname>([^<]+)</d:displayname>", RegexOption.IGNORE_CASE)
                regex.findAll(body).forEach {
                    val name = it.groupValues[1].trim()
                    if (name.isNotBlank() && name != "." && name != "..") {
                        names.add(name)
                    }
                }
                Result.success(names)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 上传本地文件到远程（PUT，201 Created 也视为成功） */
    suspend fun uploadFile(localFile: File, remoteFileName: String): Result<Unit> {
        return try {
            val req = Request.Builder()
                .url(url(config.fullRemotePath(remoteFileName)))
                .put(RequestBody.create(null, localFile))
                .addHeader("Authorization", authHeader())
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 201) Result.success(Unit)
                else Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 下载远程文件到本地（流式写入，避免大备份包占满内存） */
    suspend fun downloadFile(remoteFileName: String, localFile: File): Result<Unit> {
        return try {
            val req = Request.Builder()
                .url(url(config.fullRemotePath(remoteFileName)))
                .get()
                .addHeader("Authorization", authHeader())
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body ?: return Result.failure(Exception("空响应"))
                FileOutputStream(localFile).use { body.byteStream().copyTo(it) }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 删除远程文件（DELETE） */
    suspend fun deleteFile(remoteFileName: String): Result<Unit> {
        return try {
            val req = Request.Builder()
                .url(url(config.fullRemotePath(remoteFileName)))
                .delete()
                .addHeader("Authorization", authHeader())
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
