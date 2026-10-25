package com.yfantasyxx.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.yfantasyxx.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupState(
    val running: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /** 远端备份列表（**最新的在前**） */
    val remoteBackups: List<RemoteBackupFile> = emptyList(),
    val lastBackupTime: Long? = null,
)

/**
 * 备份管理器：将 Room 数据库整库备份到 WebDAV，或从 WebDAV 恢复。
 */
class BackupManager(
    private val context: Context,
    private val dbProvider: () -> AppDatabase,
) {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val dbFile: File
        get() = context.getDatabasePath(AppDatabase.NAME)

    /** 立即备份到 WebDAV */
    suspend fun backup(client: WebDavClient): Result<String> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(running = true, message = null, isError = false)
        runCatching {
            client.ensureDirectory(WebDavClient.BACKUP_DIRECTORY).getOrThrow()
            val bytes = checkpointAndReadDb()
            val name = backupFileName()
            val receipt = client
                .upload("${WebDavClient.BACKUP_DIRECTORY}/$name", bytes)
                .getOrThrow()
            // 清理旧备份：绝不能失手删掉刚传上去的这份，失败也只提示不影响本次
            val pruneNote = pruneOldBackups(client, name)
            _state.value = _state.value.copy(
                running = false,
                message = buildString {
                    append("备份成功（")
                    append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()))
                    append(" · ")
                    append(formatSize(receipt.bytes))
                    if (!receipt.verified) append(" · 服务器未回报校验")
                    append("）")
                    if (pruneNote != null) {
                        append('\n')
                        append(pruneNote)
                    }
                },
                lastBackupTime = System.currentTimeMillis(),
            )
            name
        }.onFailure { e ->
            _state.value = _state.value.copy(
                running = false,
                message = "备份失败：${e.message ?: "未知错误"}",
                isError = true,
            )
        }
    }

    /** 列出远端备份（按时间倒序，最新的在前） */
    suspend fun refreshRemoteBackups(client: WebDavClient) = withContext(Dispatchers.IO) {
        val result = client.listBackupFiles(WebDavClient.BACKUP_DIRECTORY)
        _state.value = _state.value.copy(
            remoteBackups = sortByTimeDesc(result.getOrDefault(emptyList())),
            message = result.exceptionOrNull()?.let { "获取备份列表失败：${it.message}" },
            isError = result.isFailure,
        )
        result
    }

    /**
     * 从 WebDAV 恢复指定备份。
     * 恢复成功后需要重启应用进程才能让 Room 读到新文件。
     */
    suspend fun restore(client: WebDavClient, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(running = true, message = null, isError = false)
            runCatching {
                val bytes = client.download("${WebDavClient.BACKUP_DIRECTORY}/$fileName").getOrThrow()
                // 覆盖本地库前先验一下，避免把 HTML 错误页 / 半个文件写进去导致应用起不来
                if (!looksLikeSqlite(bytes)) {
                    throw IOException(
                        "这份备份不是有效的数据库（${formatSize(bytes.size.toLong())}），未执行恢复"
                    )
                }
                // 关闭当前数据库连接，先删 wal/shm，再覆盖 db 文件
                dbProvider().close()
                listOf("${AppDatabase.NAME}-wal", "${AppDatabase.NAME}-shm").forEach { suffix ->
                    File(dbFile.parentFile, suffix).let { if (it.exists()) it.delete() }
                }
                dbFile.parentFile?.mkdirs()
                dbFile.writeBytes(bytes)
                _state.value = _state.value.copy(
                    running = false,
                    message = "恢复成功，请重启应用",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    running = false,
                    message = "恢复失败：${e.message ?: "未知错误"}",
                    isError = true,
                )
            }
        }

    /** 删除远端备份 */
    suspend fun deleteRemote(client: WebDavClient, fileName: String) = withContext(Dispatchers.IO) {
        client.delete("${WebDavClient.BACKUP_DIRECTORY}/$fileName")
        refreshRemoteBackups(client)
    }

    /** 外部设置错误信息（如未配置） */
    fun setError(message: String) {
        _state.value = _state.value.copy(message = message, isError = true)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, isError = false)
    }

    /** WAL checkpoint + 读取 db 文件，保证备份文件自包含 */
    private fun checkpointAndReadDb(): ByteArray {
        val db = dbProvider()
        val helper = db.openHelper
        // Room 2.7+: openHelper.writableDatabase
        val sqlite = helper.writableDatabase
        // 返回值第一列是 busy 标记：非 0 说明有一页没写回主库，
        // 此时只拷 .db 会漏掉最近的数据（备份"成功"但内容是旧的），必须报错重试
        var busy = -1
        sqlite.query("PRAGMA wal_checkpoint(FULL_TRUNCATE)").use { cursor ->
            if (cursor.moveToFirst()) busy = cursor.getInt(0)
        }
        if (busy != 0) {
            throw IOException("数据库正忙，最近的数据还没写回备份文件，请稍后重试")
        }
        val bytes = dbFile.readBytes()
        if (bytes.isEmpty()) throw IOException("数据库文件为空，已取消备份")
        return bytes
    }

    /**
     * 只保留最近 [KEEP_COUNT] 份备份。
     *
     * 注意：[keepName] 是刚上传成功的那份，任何情况下都不能被清掉；
     * 其余按时间从新到旧保留，**超出保留数的从最老的开始删**。
     * 单份删除失败不影响本次备份，只回一句提示。
     */
    private suspend fun pruneOldBackups(client: WebDavClient, keepName: String): String? {
        val list = client.listBackupFiles(WebDavClient.BACKUP_DIRECTORY).getOrElse {
            return "旧备份未清理（${it.message ?: "获取列表失败"}），不影响本次备份"
        }
        val others = list
            .filter { it.name != keepName }
            .sortedWith(TIME_DESC)
        // 保留额度里已经有一份是 keepName，其余还能留 KEEP_COUNT - 1 份
        val stale = others.drop((KEEP_COUNT - 1).coerceAtLeast(0))
        if (stale.isEmpty()) return null
        var failed = 0
        stale.forEach { old ->
            client.delete("${WebDavClient.BACKUP_DIRECTORY}/${old.name}")
                .onFailure { failed++ }
        }
        return if (failed == 0) {
            null
        } else {
            "有 $failed 份旧备份没删掉（不影响本次备份）"
        }
    }

    /** 按备份时间倒序（最新的在前）；时间解析不出来的排最后 */
    private fun sortByTimeDesc(list: List<RemoteBackupFile>): List<RemoteBackupFile> =
        list.sortedWith(TIME_DESC)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024)
    }

    /** 是不是一个真正的 SQLite 数据库文件（头部魔数 + 至少一页） */
    private fun looksLikeSqlite(bytes: ByteArray): Boolean {
        if (bytes.size < 1024) return false
        if (bytes.size < SQLITE_MAGIC.size) return false
        return SQLITE_MAGIC.indices.all { bytes[it] == SQLITE_MAGIC[it] }
    }

    private fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${WebDavClient.BACKUP_PREFIX}$stamp.db"
    }

    companion object {
        const val KEEP_COUNT = 5

        /** SQLite 文件头：`SQLite format 3\0` */
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * 按备份时间倒序（最新的在前）。
         * 解析不出时间的用 Long.MIN_VALUE 兜底，倒序时自然沉到最后。
         */
        private val TIME_DESC: Comparator<RemoteBackupFile> =
            compareByDescending<RemoteBackupFile> {
                BackupFileMeta.effectiveTimestamp(it) ?: Long.MIN_VALUE
            }.thenByDescending { it.name }
    }
}
