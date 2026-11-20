package com.yfantasyxx.ui.dev

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.db.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一份开发用的数据快照 */
data class DevSnapshot(
    val file: File,
    val time: Long,
    val reason: String,
    val bytes: Long,
    val sessions: Int,
)

/**
 * 造数据前的「存档点」。
 *
 * 测试工具里那几个造数据入口都是破坏性的：`seedBodyMetrics` 先 `DELETE FROM body_metrics`，
 * `clearWorkouts` 三张表全清，`startFakeWorkout` 会丢掉正在练的草稿。以前想用它们，
 * 就得先接受「真实记录可能回不来」——结果就是越强的工具越不敢点。
 *
 * 这里用 `VACUUM INTO` 存一份自包含的库文件（它在事务内写完，天然一致，
 * 和 [com.yfantasyxx.sync.BackupManager] 备份走的是同一条路径），
 * 造数据前自动存、还原前也自动存，于是可以放心乱点。
 *
 * 快照放在内部私有目录 `files/dev/snapshots/`，不进外部存储、也不参与系统备份。
 */
object DevSnapshots {

    private const val DIR = "dev/snapshots"

    /** 保留份数：一份约 1–2 MB，20 份足够回退，又不会把手机塞满 */
    const val MAX_KEEP = 20

    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /** SQLite 文件头：`SQLite format 3\0` */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /**
     * 存一份快照。
     *
     * 失败会抛异常而不是「静默跳过」：调用它的都是破坏性操作，
     * 存不下来就不该继续往下删数据。
     */
    fun snapshot(context: Context, db: AppDatabase, reason: String): DevSnapshot {
        val target = File(dir(context), "snap-${STAMP.format(Date())}@$reason.db")
        // VACUUM INTO 要求目标文件不存在
        if (target.exists() && !target.delete()) throw IOException("旧的同名快照删不掉")

        db.openHelper.writableDatabase.execSQL(
            "VACUUM INTO '${target.absolutePath.replace("'", "''")}'"
        )
        if (!target.exists() || target.length() <= 0L) throw IOException("快照没有生成")

        prune(context)
        return DevSnapshot(
            file = target,
            time = target.lastModified(),
            reason = reason,
            bytes = target.length(),
            sessions = countSessions(target),
        )
    }

    /** 已存的快照，最新的在前 */
    fun list(context: Context): List<DevSnapshot> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith("snap-") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map {
                DevSnapshot(
                    file = it,
                    time = it.lastModified(),
                    reason = reasonOf(it),
                    bytes = it.length(),
                    sessions = countSessions(it),
                )
            }

    fun delete(context: Context, file: File) {
        runCatching { file.delete() }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * 还原到某一份快照。
     *
     * 写盘顺序照抄 [com.yfantasyxx.sync.BackupManager.restore]：临时文件 + fsync → 关库 →
     * 删 sidecar → 替换 → 重建实例。差别只有一个：这里**先给当前状态存一份**，
     * 选错了还能退回来。
     */
    fun restore(context: Context, container: AppContainer, file: File): String {
        if (!file.exists()) throw IOException("快照文件不见了")
        val bytes = file.readBytes()
        if (!looksLikeSqlite(bytes)) {
            throw IOException("这不是一个有效的数据库文件（${sizeLabel(bytes.size.toLong())}），未执行还原")
        }
        // Room 遇到「库比 App 新」会销毁式重建：表全清空，看起来就是「还原成功但没数据」
        val snapVersion = schemaVersionOf(bytes)
        val appVersion = runCatching {
            container.db().openHelper.readableDatabase.version
        }.getOrDefault(-1)
        if (snapVersion > 0 && appVersion > 0 && snapVersion > appVersion) {
            throw IOException(
                "这份快照是 v$snapVersion，比当前 App 的 v$appVersion 新。" +
                    "旧版 App 打不开新库，硬还原会把数据清空，已拦下。"
            )
        }

        // 先把要展示的信息读出来：下面存快照时会按份数清理，这份有可能被挤掉
        val stamp = stampOf(file)
        val sessions = countSessions(file)

        // 还原前先把「现在的状态」存下来，选错快照还有得退
        snapshot(context, container.db(), "before-restore")

        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        dbFile.parentFile?.mkdirs()
        val tmp = File(dbFile.parentFile, "${AppDatabase.NAME}.dev-restore-tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw IOException("写入临时文件失败（可能是空间不足），现有数据没动", t)
        }

        runCatching { container.db().close() }
        try {
            // sidecar 留着会让新库读到旧的未提交帧
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dbFile.parentFile, "${AppDatabase.NAME}$suffix")
                    .let { if (it.exists()) it.delete() }
            }
            // rename 在同一个目录内是原子的：失败时旧库一个字节都没动
            if (!tmp.renameTo(dbFile)) throw IOException("替换数据库文件失败")
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            // 库已经关过了，必须还给容器一个能打开的实例，
            // 否则后面每个 DAO 调用都抛 already-closed，连测试页都回不去
            runCatching { container.rebuildDatabase() }
            throw t
        }
        container.rebuildDatabase()

        return "回到 $stamp 的状态（${if (sessions < 0) "行数未知" else "$sessions 次训练"}）；" +
            "还原前已自动存一份快照"
    }

    /** 只留最近的 [MAX_KEEP] 份，按修改时间从新到旧 */
    private fun prune(context: Context) {
        dir(context).listFiles { f -> f.isFile && f.name.startsWith("snap-") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(MAX_KEEP)
            .forEach { runCatching { it.delete() } }
    }

    /** 文件名里 `@` 后面那段就是存它的原因 */
    private fun reasonOf(file: File): String =
        file.name.substringAfter('@', "").removeSuffix(".db").ifBlank { "snapshot" }

    fun reasonLabel(reason: String): String = when (reason) {
        "manual" -> "手动"
        "before-seed" -> "造历史训练前"
        "before-seed-body" -> "造体重前"
        "before-clear" -> "清空训练前"
        "before-fake-workout" -> "假训练前"
        "before-restore" -> "还原前"
        else -> reason
    }

    fun stampOf(file: File): String =
        SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA).format(Date(file.lastModified()))

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.2f MB", bytes / 1024f / 1024f)
    }

    /**
     * 数一下快照里有多少次训练。
     * 打不开 / 表不存在返回 -1，列表里就不显示行数，不影响还原。
     */
    private fun countSessions(file: File): Int = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }
        }
    }.getOrDefault(-1)

    private fun looksLikeSqlite(bytes: ByteArray): Boolean {
        if (bytes.size < 1024 || bytes.size < SQLITE_MAGIC.size) return false
        return SQLITE_MAGIC.indices.all { bytes[it] == SQLITE_MAGIC[it] }
    }

    /** SQLite 头部偏移 60..63 是 `user_version`，Room 就用它存 schema 版本 */
    private fun schemaVersionOf(bytes: ByteArray): Int {
        if (bytes.size < 64) return -1
        return ((bytes[60].toInt() and 0xFF) shl 24) or
            ((bytes[61].toInt() and 0xFF) shl 16) or
            ((bytes[62].toInt() and 0xFF) shl 8) or
            (bytes[63].toInt() and 0xFF)
    }
}
