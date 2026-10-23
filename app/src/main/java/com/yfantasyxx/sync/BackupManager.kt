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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupState(
    val running: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /** 远端备份列表（名称） */
    val remoteBackups: List<String> = emptyList(),
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
            client.upload("${WebDavClient.BACKUP_DIRECTORY}/$name", bytes).getOrThrow()
            pruneOldBackups(client)
            _state.value = _state.value.copy(
                running = false,
                message = "备份成功：$name",
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

    /** 列出远端备份 */
    suspend fun refreshRemoteBackups(client: WebDavClient) = withContext(Dispatchers.IO) {
        val result = client.listBackups(WebDavClient.BACKUP_DIRECTORY)
        _state.value = _state.value.copy(
            remoteBackups = result.getOrDefault(emptyList()),
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
        sqlite.query("PRAGMA wal_checkpoint(FULL_TRUNCATE)").use { it.moveToFirst() }
        return dbFile.readBytes()
    }

    /** 仅保留最近 [KEEP_COUNT] 个备份 */
    private suspend fun pruneOldBackups(client: WebDavClient) {
        val list = client.listBackups(WebDavClient.BACKUP_DIRECTORY).getOrDefault(emptyList())
        if (list.size > KEEP_COUNT) {
            list.sorted().take(list.size - KEEP_COUNT).forEach { old ->
                client.delete("${WebDavClient.BACKUP_DIRECTORY}/$old")
            }
        }
    }

    private fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${WebDavClient.BACKUP_PREFIX}$stamp.db"
    }

    companion object {
        const val KEEP_COUNT = 5
    }
}
