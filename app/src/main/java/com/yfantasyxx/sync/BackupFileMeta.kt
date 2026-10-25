package com.yfantasyxx.sync

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * 备份文件名的解析工具。
 *
 * 备份文件名形如 `yfantasyxx-backup-20261025-140322.db`，
 * 里面的时间戳是**备份当时那台设备的本地时间**，
 * 比服务器上的修改时间更贴近「这份数据是什么时候的」。
 */
object BackupFileMeta {

    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /** 文件名里的时间戳部分必须严格匹配这个格式，否则当成「解析不出来」 */
    private val STAMP_REGEX = Pattern.compile("^(\\d{8})-(\\d{6})$")

    /** 从文件名解析备份时间（毫秒）；解析不出来返回 null */
    fun timestampOf(name: String): Long? {
        val raw = name
            .removePrefix(WebDavClient.BACKUP_PREFIX)
            .removeSuffix(".db")
        val m = STAMP_REGEX.matcher(raw)
        if (!m.matches()) return null
        return runCatching {
            SimpleDateFormat(STAMP_PATTERN, Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    /**
     * 备份时间：优先文件名里的时间戳，其次服务器上的修改时间。
     * 两个都没有就是 null（只能显示原始文件名）。
     */
    fun effectiveTimestamp(file: RemoteBackupFile): Long? =
        timestampOf(file.name) ?: file.lastModified
}
