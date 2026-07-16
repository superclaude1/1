package com.storybrain.app.data.remote

import android.content.Context
import com.storybrain.app.data.local.BookRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * WebDAV 备份恢复服务：在 [WebDavClient] 基础上封装"整库打包 zip 上传 / 下载解压"流程。
 * 备份范围：整个 books/ 目录（所有书的章节切片、对话分析、记忆盘、书名）。
 * 恢复策略：直接覆盖本地 books/ 目录（同名书会被替换），恢复后清空内存缓存。
 *
 * 注意：必须传入 Application 级别的 [BookRepository] 单例（而非 new 新实例），
 * 这样恢复后调用的 [BookRepository.clearCaches] 才能作用于 Reader 正在用的同一个缓存。
 */
class WebDavBackupService(
    private val context: Context,
    private val repo: BookRepository
) {

    /** 打包整库为 zip 上传到 WebDAV，文件名带时间戳便于多次备份 */
    suspend fun backupAll(config: WebDavConfig): Result<String> {
        val tempZip = File(context.cacheDir, "storybrain_backup_${System.currentTimeMillis()}.zip")
        return try {
            val books = repo.books()
            if (books.isEmpty()) return Result.failure(Exception("暂无书籍可备份"))

            zipDirectory(repo.rootDir, tempZip)

            val remoteName = "storybrain_backup_${System.currentTimeMillis()}.zip"
            val client = WebDavClient(config)
            client.uploadFile(tempZip, remoteName).getOrThrow()

            Result.success("已备份 ${books.size} 本书到 $remoteName")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { tempZip.delete() }
        }
    }

    /** 下载指定备份文件并解压覆盖本地 books/ 目录，随后清空内存缓存避免读到旧数据 */
    suspend fun restore(config: WebDavConfig, remoteFileName: String): Result<String> {
        val tempZip = File(context.cacheDir, "restore_${System.currentTimeMillis()}.zip")
        return try {
            val client = WebDavClient(config)
            client.downloadFile(remoteFileName, tempZip).getOrThrow()

            unzipFile(tempZip, repo.rootDir)

            // 恢复覆盖了磁盘文件，必须清空内存缓存，否则 Reader 读到的是恢复前的旧记忆盘/旧正文
            repo.clearCaches()

            val books = repo.books()
            Result.success("已恢复 ${books.size} 本书，建议重启应用以重新加载后台流水线")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 无论成功失败都清理临时文件
            runCatching { tempZip.delete() }
        }
    }

    /** 列出云端所有 storybrain_backup_*.zip，按时间戳降序（最新在前） */
    suspend fun listBackups(config: WebDavConfig): Result<List<String>> {
        return try {
            val client = WebDavClient(config)
            val all = client.listFiles().getOrThrow()
            val backups = all.filter { it.startsWith("storybrain_backup_") && it.endsWith(".zip") }
                .sortedByDescending { it }
            Result.success(backups)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 递归打包目录为 zip（保留相对路径结构） */
    private fun zipDirectory(sourceDir: File, destFile: File) {
        ZipOutputStream(FileOutputStream(destFile)).use { zos ->
            sourceDir.walk().forEach { file ->
                val entryName = sourceDir.toPath().relativize(file.toPath()).toString()
                val entry = ZipEntry(if (file.isDirectory) "$entryName/" else entryName)
                zos.putNextEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
    }

    /** 解压 zip 到目标目录（自动创建子目录） */
    private fun unzipFile(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
