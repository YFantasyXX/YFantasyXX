package com.yfantasyxx.ui.dev

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.yfantasyxx.sync.RemoteBackupFile
import com.yfantasyxx.sync.RemoteStore
import com.yfantasyxx.sync.UploadReceipt
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * 演练时注入的故障。
 *
 * 每一项都对应一条「真的会让人丢数据」的现实路径，
 * 而不是随便造个错误：401 是网盘改密码最常见的表现，
 * 507 是免费空间用完，PARTIAL 是网关吞请求/目录只读，
 * CORRUPT 是反向代理返回 HTML 错误页，EMPTY 是新装 App 备份出来的空库。
 */
enum class RemoteFault(val label: String) {
    NONE("正常"),
    AUTH_401("凭据失效 401"),
    QUOTA_507("空间不足 507"),
    TIMEOUT("连接超时"),
    PARTIAL_UPLOAD("上传只写了一半"),
    CORRUPT_BODY("下载到 HTML 错误页"),
    EMPTY_DATABASE("下载到空库"),
    SLOW("每步慢 800ms"),
}

/** 一次演练。`expectFailure` 就是这条演练要验证的断言 */
enum class BackupDrill(
    val label: String,
    /** 胶囊按钮上的短名：一行塞得下 2–3 个 */
    val short: String,
    val fault: RemoteFault,
    val op: DrillOp,
    val expectFailure: Boolean,
    /**
     * 失败时必须出现在提示里的关键词。
     *
     * 只判断「失败了」是不够的：CORRUPT 场景如果因为「文件不存在」而失败，
     * 也会被算成通过，可它根本没验到 SQLite 头校验那条防线。
     */
    val expectKeyword: String,
    val detail: String,
) {
    BACKUP_OK(
        "备份到假远端", "正常备份", RemoteFault.NONE, DrillOp.BACKUP, expectFailure = false,
        expectKeyword = "",
        detail = "建目录 → 上传 → 回查字节数 → 清理旧备份，走的是真实 BackupManager",
    ),
    AUTH(
        "凭据失效（401）", "401", RemoteFault.AUTH_401, DrillOp.RESTORE, expectFailure = true,
        expectKeyword = "认证失败",
        detail = "应当直接报「认证失败」，而不是超时或「文件不存在」",
    ),
    QUOTA(
        "空间不足（507）", "507 配额", RemoteFault.QUOTA_507, DrillOp.BACKUP, expectFailure = true,
        expectKeyword = "507",
        detail = "上传被拒；本地库一个字节都不该动",
    ),
    TIMEOUT(
        "网络超时", "超时", RemoteFault.TIMEOUT, DrillOp.RESTORE, expectFailure = true,
        expectKeyword = "超时",
        detail = "连接阶段超时；界面要能从「进行中」恢复，不能卡死",
    ),
    PARTIAL(
        "上传不完整", "上传半截", RemoteFault.PARTIAL_UPLOAD, DrillOp.BACKUP, expectFailure = true,
        expectKeyword = "大小不符",
        detail = "服务器只写进去一半，回查字节数必须拦住，不能报成功",
    ),
    CORRUPT(
        "下载到 HTML 错误页", "HTML 错误页", RemoteFault.CORRUPT_BODY, DrillOp.RESTORE, expectFailure = true,
        expectKeyword = "不是有效的数据库",
        detail = "应在写盘前被 SQLite 头校验拦下，本地库保持原样",
    ),
    EMPTY(
        "下载到空库", "空库", RemoteFault.EMPTY_DATABASE, DrillOp.RESTORE, expectFailure = true,
        expectKeyword = "没有任何训练记录",
        detail = "应在写盘前被「空库拦截」拦下，本地训练记录不受影响",
    ),
    MISSING(
        "远端文件已被删", "文件已删", RemoteFault.NONE, DrillOp.RESTORE, expectFailure = true,
        expectKeyword = "文件不存在",
        detail = "下载一个不存在的文件名，应报「文件不存在」",
    ),
    SLOW(
        "慢速链路", "慢速", RemoteFault.SLOW, DrillOp.BACKUP, expectFailure = false,
        expectKeyword = "",
        detail = "每步延迟 800ms，用来验证界面「进行中」状态和按钮禁用",
    ),
}

enum class DrillOp { BACKUP, RESTORE }

/**
 * 把本地目录当远端的 [RemoteStore]。
 *
 * 为什么是「本地目录」而不是 mock：这样 `BackupManager` 里
 * VACUUM 快照、文件名生成、旧备份清理、恢复前的字节校验
 * **一行都不用改就能跑**，演练的是同一份代码，不是另一套简化实现。
 *
 * 只存在于 `ui/dev`，正式版被 R8 整段裁掉。
 */
class LocalRemoteStore(
    private val root: File,
    private val fault: RemoteFault = RemoteFault.NONE,
) : RemoteStore {

    override val directory: String get() = RemoteStore.BACKUP_DIRECTORY

    override suspend fun testConnection(): Result<Unit> = runCatching {
        gate()
        if (!root.exists() && !root.mkdirs()) throw IOException("假远端目录建不出来")
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = runCatching {
        gate()
        val dir = resolve(path)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("创建目录失败：$path")
        }
    }

    override suspend fun upload(path: String, bytes: ByteArray): Result<UploadReceipt> = runCatching {
        gate()
        if (bytes.isEmpty()) throw IOException("内容为空，已取消上传")
        val target = resolve(path)
        target.parentFile?.mkdirs()

        if (fault == RemoteFault.PARTIAL_UPLOAD) {
            // 只落一半，然后按真实客户端的规矩回查：大小对不上就不许报成功
            val half = bytes.size / 2
            target.writeBytes(bytes.copyOf(half))
            throw IOException(
                "上传后大小不符：服务器 ${target.length()} 字节，本地 ${bytes.size} 字节（演练注入）"
            )
        }

        target.writeBytes(bytes)
        // 和 WebDavClient.verifyUploaded 一样：写完必须能查回来
        if (!target.exists() || target.length() != bytes.size.toLong()) {
            throw IOException("上传返回成功，但服务器上找不到这份文件（目录可能不可写）")
        }
        UploadReceipt(bytes.size.toLong(), verified = true)
    }

    override suspend fun stat(path: String): Result<RemoteBackupFile?> = runCatching {
        gate()
        val f = resolve(path)
        if (!f.isFile) null else RemoteBackupFile(f.name, f.length(), f.lastModified())
    }

    override suspend fun download(path: String): Result<ByteArray> = runCatching {
        gate()
        val f = resolve(path)
        if (!f.isFile) throw IOException("文件不存在：$path")
        when (fault) {
            // 反向代理 502 时最常见的返回：状态码可能是 200，正文是 HTML
            RemoteFault.CORRUPT_BODY -> """
                <!doctype html><html><head><title>502 Bad Gateway</title></head>
                <body><h1>502 Bad Gateway</h1><hr>nginx</body></html>
            """.trimIndent().toByteArray()
            // 合法 SQLite，但 sessions 是空的：专测「恢复空库会把数据清空」那条防线
            RemoteFault.EMPTY_DATABASE -> emptyDatabaseBytes()
            else -> f.readBytes()
        }
    }

    override suspend fun listBackupFiles(directory: String): Result<List<RemoteBackupFile>> =
        runCatching {
            gate()
            val dir = resolve(directory)
            if (!dir.isDirectory) return@runCatching emptyList()
            dir.listFiles().orEmpty()
                .filter {
                    it.isFile &&
                        it.name.startsWith(RemoteStore.BACKUP_PREFIX) &&
                        it.name.endsWith(".db")
                }
                .map { RemoteBackupFile(it.name, it.length(), it.lastModified()) }
                .sortedBy { it.name }
        }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        gate()
        val f = resolve(path)
        if (f.exists() && !f.delete()) throw IOException("删除失败：$path")
    }

    /** 每一步统一的故障开关 */
    private suspend fun gate() {
        when (fault) {
            RemoteFault.AUTH_401 -> throw IOException("认证失败：请检查用户名和密码（演练注入）")
            RemoteFault.TIMEOUT -> {
                // 真超时是「等很久然后失败」，不是立刻失败——顺带验证界面能不能扛住等待
                delay(1_500)
                throw IOException("连接超时（演练注入）")
            }
            RemoteFault.QUOTA_507 -> throw IOException("上传失败(507)：存储空间不足（演练注入）")
            RemoteFault.SLOW -> delay(800)
            else -> Unit
        }
    }

    /** 拒绝 `..`：假远端也不能让它写到 filesDir 外面去 */
    private fun resolve(path: String): File {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return root
        if (segments.any { it == ".." || it == "." }) throw IOException("非法路径：$path")
        return segments.fold(root) { acc, seg -> File(acc, seg) }
    }

    /** 造一个「合法但不是有效备份」的库：header 正确、schema 版本对，但一条训练记录都没有 */
    private fun emptyDatabaseBytes(): ByteArray {
        val probe = File(root.parentFile ?: root, "empty-probe.db")
        val sidecars = listOf("", "-wal", "-shm", "-journal")
        sidecars.forEach { runCatching { File(probe.path + it).delete() } }
        probe.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(probe, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)"
            )
            // schema 版本对齐当前 App，确保被拦的理由只会是「空库」而不是「版本太新」
            db.execSQL("PRAGMA user_version = 18")
        }
        val bytes = probe.readBytes()
        sidecars.forEach { runCatching { File(probe.path + it).delete() } }
        return bytes
    }
}

/** 假远端在磁盘上的位置与清理 */
object DevBackupLab {

    private const val ROOT = "dev/remote"

    fun rootOf(context: Context): File = File(context.filesDir, ROOT)

    fun store(context: Context, fault: RemoteFault = RemoteFault.NONE): LocalRemoteStore =
        LocalRemoteStore(rootOf(context), fault)

    fun backupDir(context: Context): File = File(rootOf(context), RemoteStore.BACKUP_DIRECTORY)

    /** 假远端里现存几份备份 */
    fun fileCount(context: Context): Int =
        backupDir(context).listFiles()?.count {
            it.isFile && it.name.startsWith(RemoteStore.BACKUP_PREFIX)
        } ?: 0

    fun totalBytes(context: Context): Long =
        backupDir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** 远端里最旧那份的文件名：用来喂「恢复已删文件」之外的场景 */
    fun firstFileName(context: Context): String? =
        backupDir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith(RemoteStore.BACKUP_PREFIX) }
            ?.minByOrNull { it.name }
            ?.name

    fun clear(context: Context): Int {
        val dir = rootOf(context)
        val n = fileCount(context)
        runCatching { dir.deleteRecursively() }
        return n
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    }
}
