package com.yfantasyxx.sync

/**
 * 备份远端。
 *
 * 抽成接口只为一件事：**让恢复流程可以被演练**。
 * 原来 [BackupManager] 直接吃 [WebDavClient]，于是「401 凭据过期」「配额满了 507」
 * 「下载回来是个 HTML 错误页」「远端文件被删了」这些真正会让人丢数据的路径，
 * 只能靠真的连网盘、真的等它出错来碰运气。
 *
 * 接口只有 7 个方法，实现两个：真网盘的 [WebDavClient]，
 * 和 lite 版测试工具里「拿本地目录当远端」的 `LocalRemoteStore`
 * （后者能按需注入故障，见 `ui/dev/DevBackupLab.kt`）。
 */
interface RemoteStore {

    /** 备份目录名 */
    val directory: String

    /** 能连上吗（只证明连通，不证明可写） */
    suspend fun testConnection(): Result<Unit>

    /** 确保目录存在（逐级创建，已存在不算失败） */
    suspend fun ensureDirectory(path: String): Result<Unit>

    /**
     * 上传并回查。
     *
     * 实现必须**核对写进去的字节数**：PUT 返回 2xx 不代表文件落盘了，
     * 目录只读、网关吞请求、配额超限后返回畸形 200 都可能。
     * 对不上要算失败，不能返回成功。
     */
    suspend fun upload(path: String, bytes: ByteArray): Result<UploadReceipt>

    /** 查单个文件；`success(null)` 表示明确不存在 */
    suspend fun stat(path: String): Result<RemoteBackupFile?>

    suspend fun download(path: String): Result<ByteArray>

    /** 列目录下的备份文件（按文件名） */
    suspend fun listBackupFiles(directory: String): Result<List<RemoteBackupFile>>

    suspend fun delete(path: String): Result<Unit>

    companion object {
        const val BACKUP_DIRECTORY = "YFantasyXX"
        const val BACKUP_PREFIX = "yfantasyxx-backup-"
    }
}
