package com.yfantasyxx.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.yfantasyxx.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class BackupState(
    val running: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /** 远端备份列表（**最新的在前**） */
    val remoteBackups: List<RemoteBackupFile> = emptyList(),
    val lastBackupTime: Long? = null,
    /** 当前 App 的 schema 版本；用来在列表里标出「这份备份比 App 新，恢复不了」 */
    val appSchemaVersion: Int = -1,
)

/**
 * 备份管理器：将 Room 数据库整库备份到 WebDAV，或从 WebDAV 恢复。
 *
 * @param onDatabaseInvalidated 恢复失败时通知容器「当前库实例不可用」，
 *   让它重建一个能打开的实例；不传则不做补偿。
 */
class BackupManager(
    private val context: Context,
    private val dbProvider: () -> AppDatabase,
    private val onDatabaseInvalidated: () -> Unit = {},
) {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /**
     * 备份 / 恢复互斥。
     *
     * 两条协程同时 checkpoint + 读文件时，拷到的可能是「写了一半」的库；
     * 备份进行中恢复更糟：close() 正好插在 readBytes() 中间。
     */
    private val gate = Mutex()

    private val dbFile: File
        get() = context.getDatabasePath(AppDatabase.NAME)

    /** 立即备份到 WebDAV */
    suspend fun backup(client: WebDavClient): Result<String> = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) {
            return@withContext Result.failure(IOException("已有备份或恢复任务在进行，请稍候"))
        }
        try {
        _state.update { it.copy(running = true, message = null, isError = false) }
        runCatching {
            client.ensureDirectory(WebDavClient.BACKUP_DIRECTORY).getOrThrow()
            val bytes = checkpointAndReadDb()
            // 文件名带上 schema 版本，列表里一眼能看出是哪一版写的
            val schema = currentSchemaVersion()
            val name = backupFileName(schema)
            val receipt = client
                .upload("${WebDavClient.BACKUP_DIRECTORY}/$name", bytes)
                .getOrThrow()
            // 清理旧备份：绝不能失手删掉刚传上去的这份，失败也只提示不影响本次
            val pruneNote = pruneOldBackups(client, name)
            _state.value = _state.value.copy(
                running = false,
                appSchemaVersion = schema,
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
        } finally {
            gate.unlock()
        }
    }

    /** 列出远端备份（按时间倒序，最新的在前） */
    suspend fun refreshRemoteBackups(client: WebDavClient) = withContext(Dispatchers.IO) {
        val result = client.listBackupFiles(WebDavClient.BACKUP_DIRECTORY)
        _state.value = _state.value.copy(
            remoteBackups = sortByTimeDesc(result.getOrDefault(emptyList())),
            message = result.exceptionOrNull()?.let { "获取备份列表失败：${it.message}" },
            isError = result.isFailure,
            appSchemaVersion = currentSchemaVersion(),
        )
        result
    }

    /**
     * 从 WebDAV 恢复指定备份。
     * 恢复成功后需要重启应用进程才能让 Room 读到新文件。
     *
     * 写盘顺序是「临时文件 + fsync → 备份旧库 → 关库 → 替换」：
     * 以前是「先关库再 writeBytes」，只要写文件这一步失败（空间不足、
     * 权限、断电），App 就永远拿着一个已关闭的数据库，进任何页面都崩，
     * 连「再恢复一次」都做不到。
     */
    suspend fun restore(client: WebDavClient, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!gate.tryLock()) {
                return@withContext Result.failure(IOException("已有备份或恢复任务在进行，请稍候"))
            }
            try {
            _state.value = _state.value.copy(running = true, message = null, isError = false)
            var dbClosed = false
            runCatching {
                val bytes = client.download("${WebDavClient.BACKUP_DIRECTORY}/$fileName").getOrThrow()
                // 覆盖本地库前先验一下，避免把 HTML 错误页 / 半个文件写进去导致应用起不来
                if (!looksLikeSqlite(bytes)) {
                    throw IOException(
                        "这份备份不是有效的数据库（${formatSize(bytes.size.toLong())}），未执行恢复"
                    )
                }
                // 版本对不上必须拦下来：Room 遇到「库比 App 新」会走销毁式兜底，
                // 表全被清空重建 —— 表现就是「恢复成功但数据是空的」，本地原有数据也一起没了
                val backupVersion = schemaVersionOf(bytes)
                val appVersion = currentSchemaVersion()
                if (backupVersion > 0 && appVersion > 0 && backupVersion > appVersion) {
                    throw IOException(
                        "这份备份来自更新的版本（数据库 v$backupVersion，当前 App 是 v$appVersion）。" +
                            "旧版 App 打不开新库，硬恢复会把数据清空，已拦下。" +
                            "请先升级 App，或改用同版本客户端备份的文件。"
                    )
                }
                // 空库拦截：lite 和正式版是两个独立 App（包名不同），
                // 新装的那个备份出来就是空库；恢复回去会把当前数据清空，
                // 表现就是「恢复成功，但打开是空的」
                val sessions = countSessions(bytes)
                if (sessions == 0) {
                    throw IOException(
                        "这份备份里没有任何训练记录（是个空库），恢复会把你现在的数据清空，已拦下。" +
                            "要备份当前数据请先在当前版本里点「立即备份」。"
                    )
                }

                dbFile.parentFile?.mkdirs()
                // 1) 先写临时文件并 fsync：这一步失败时本地库一个字节都没动
                val tmp = File(dbFile.parentFile, "${AppDatabase.NAME}.restore-tmp")
                try {
                    FileOutputStream(tmp).use { out ->
                        out.write(bytes)
                        out.flush()
                        out.fd.sync()
                    }
                } catch (t: Throwable) {
                    runCatching { tmp.delete() }
                    throw IOException("写入临时文件失败（可能是空间不足），未改动现有数据", t)
                }

                // 2) 关库、备份旧库、替换。任何一步失败都要能回到可用状态
                val backup = File(dbFile.parentFile, "${AppDatabase.NAME}.bak")
                dbProvider().close()
                dbClosed = true
                try {
                    if (dbFile.exists()) dbFile.copyTo(backup, overwrite = true)
                    deleteSidecars()
                    if (!tmp.renameTo(dbFile)) throw IOException("替换数据库文件失败")
                } catch (t: Throwable) {
                    runCatching { tmp.delete() }
                    if (!dbFile.exists() && backup.exists()) {
                        runCatching { backup.copyTo(dbFile, overwrite = true) }
                    }
                    throw t
                }

                _state.value = _state.value.copy(
                    running = false,
                    message = buildString {
                        append("恢复成功")
                        if (sessions > 0) append("（$sessions 次训练）")
                        if (backupVersion in 1 until appVersion) {
                            append("，表结构已从 v$backupVersion 升到 v$appVersion")
                        }
                        append("，请重启应用")
                    },
                )
            }.onFailure { e ->
                // 关过库就必须给容器一个能用的实例，否则后续每个 DAO 调用都会
                // 抛 already-closed，用户连设置页都进不去
                if (dbClosed) runCatching { onDatabaseInvalidated() }
                _state.value = _state.value.copy(
                    running = false,
                    message = "恢复失败：${e.message ?: "未知错误"}",
                    isError = true,
                )
            }
            } finally {
                gate.unlock()
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

    /** 删除主库旁边的 -wal / -shm / -journal：留着会让新库读到旧的未提交帧 */
    private fun deleteSidecars() {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(dbFile.parentFile, "${AppDatabase.NAME}$suffix")
                .let { if (it.exists()) it.delete() }
        }
    }

    /**
     * 取一份能自包含还原的数据库快照。
     *
     * 首选 `VACUUM INTO`：它在事务内把当前库写成一个新文件，天然一致。
     * 之前是「checkpoint 完再 readBytes()」——checkpoint 返回 busy=0 只能证明
     * **那一刻**写回了，紧接着读文件的几百毫秒里 Room 完全可能开始新事务、
     * 触发下一次 checkpoint，拷到的就是半新半旧的库。
     *
     * 老系统不支持 VACUUM INTO 时退回原来的 checkpoint + 整文件读取。
     */
    private fun checkpointAndReadDb(): ByteArray {
        val db = dbProvider()
        val snapshotFile = File(context.cacheDir, "backup-snapshot.db")
        val viaVacuum = runCatching {
            if (snapshotFile.exists() && !snapshotFile.delete()) {
                throw IOException("旧的快照文件删不掉")
            }
            db.openHelper.writableDatabase.execSQL(
                "VACUUM INTO '${snapshotFile.absolutePath.replace("'", "''")}'"
            )
            if (!snapshotFile.exists() || snapshotFile.length() <= 0L) {
                throw IOException("快照文件为空")
            }
            snapshotFile.readBytes()
        }.getOrNull()
        runCatching { snapshotFile.delete() }
        if (viaVacuum != null && looksLikeSqlite(viaVacuum)) return viaVacuum

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
     * 清理旧备份。
     *
     * 规则（既能留很久，列表又不会长成一坨）：
     * 1. 刚上传的这份无条件保留，任何情况下都不能被清掉；
     * 2. 今天的备份全部保留——一天练两次、手动补备份几次都能回退；
     * 3. 更早的按天去重，每天只留最晚的那一份；
     * 4. 超过 [KEEP_DAYS] 天的删掉；
     * 5. 时间解析不出来的（不是本 App 传的）不猜，原样留着。
     *
     * 单份删除失败不影响本次备份结果，只附一句提示。
     */
    private suspend fun pruneOldBackups(client: WebDavClient, keepName: String): String? {
        val list = client.listBackupFiles(WebDavClient.BACKUP_DIRECTORY).getOrElse {
            return "旧备份未清理（${it.message ?: "获取列表失败"}），不影响本次备份"
        }
        val todayStart = startOfDay(System.currentTimeMillis())
        val cutoff = daysBefore(todayStart, KEEP_DAYS)
        val keep = linkedSetOf(keepName)
        val keptDays = mutableSetOf<Long>()

        // 从新到旧扫，先遇到的就是这一天最晚的那份
        for (file in list.sortedWith(TIME_DESC)) {
            val ts = BackupFileMeta.effectiveTimestamp(file) ?: continue
            val day = startOfDay(ts)
            when {
                day >= todayStart -> keep += file.name
                day < cutoff -> Unit
                keptDays.add(day) -> keep += file.name
            }
        }
        list.filter { BackupFileMeta.effectiveTimestamp(it) == null }.forEach { keep += it.name }

        val stale = list.filter { it.name !in keep }
        if (stale.isEmpty()) return null
        var failed = 0
        stale.forEach { old ->
            client.delete("${WebDavClient.BACKUP_DIRECTORY}/${old.name}")
                .onFailure { failed++ }
        }
        return when {
            failed == 0 -> "已清理 ${stale.size} 份旧备份（每天保留最后一份）"
            failed == stale.size -> "旧备份没删掉（不影响本次备份）"
            else -> "有 $failed 份旧备份没删掉（不影响本次备份）"
        }
    }

    /** 当天 0 点 */
    private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 按日历往前推 n 天（避开毫秒相加在夏令时上差一小时） */
    private fun daysBefore(ts: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        add(Calendar.DAY_OF_YEAR, -days)
    }.timeInMillis

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

    /**
     * 读备份文件里记录的 schema 版本。
     *
     * SQLite 头部偏移 60..63 是 `user_version`（4 字节大端），Room 就用它存版本号，
     * 所以不用打开数据库就能判断这份备份是哪个版本写出来的。
     */
    private fun schemaVersionOf(bytes: ByteArray): Int {
        if (bytes.size < 64) return -1
        return ((bytes[60].toInt() and 0xFF) shl 24) or
            ((bytes[61].toInt() and 0xFF) shl 16) or
            ((bytes[62].toInt() and 0xFF) shl 8) or
            (bytes[63].toInt() and 0xFF)
    }

    /** 当前 App 实际使用的 schema 版本；读不到返回 -1（此时不做版本校验） */
    private fun currentSchemaVersion(): Int =
        runCatching { dbProvider().openHelper.readableDatabase.version }.getOrDefault(-1)

    /**
     * 数一下备份文件里有多少条训练记录。
     * 打不开 / 表不存在都返回 -1（无法判断），此时不拿它做拦截。
     */
    private fun countSessions(bytes: ByteArray): Int {
        val probe = File(context.cacheDir, "restore-probe.db")
        // 探针库只读打开：读一次就扔，别给它留下 -wal/-journal
        listOf("", "-wal", "-shm", "-journal").forEach {
            runCatching { File(probe.path + it).delete() }
        }
        return runCatching {
            probe.writeBytes(bytes)
            SQLiteDatabase.openDatabase(
                probe.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else -1
                }
            }
        }.getOrDefault(-1).also {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                runCatching { File(probe.path + suffix).delete() }
            }
        }
    }

    private fun backupFileName(schemaVersion: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // 加毫秒：同一秒内点两次「立即备份」（或自动备份撞上手动备份）时，
        // 文件名相同会被 PUT 静默覆盖，用户以为存了两份其实只有一份
        val millis = String.format(Locale.US, "%03d", System.currentTimeMillis() % 1000)
        val suffix = if (schemaVersion > 0) "-v$schemaVersion" else ""
        return "${WebDavClient.BACKUP_PREFIX}$stamp-$millis$suffix.db"
    }

    companion object {
        /**
         * 按天保留的天数：每天留最后一份，最多回看这么久。
         * 单份约 1–2 MB，90 天也就一两百 MB。
         */
        const val KEEP_DAYS = 90

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
