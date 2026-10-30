package com.yfantasyxx.sync

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * 备份文件名的解析工具。
 *
 * 备份文件名形如 `yfantasyxx-backup-20261025-140322-123-v15.db`：
 * 前面是**备份当时那台设备的本地时间**（比服务器修改时间更贴近「这份数据是什么时候的」），
 * `-123` 是毫秒（同一秒内多次备份也不会重名），
 * 末尾 `-v15` 是写这份备份时的数据库 schema 版本——旧版 App 读不了更高的版本，
 * 列表里直接标出来，就不用等到恢复报错才知道。
 */
object BackupFileMeta {

    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * 文件名里的时间戳部分必须严格匹配这个格式，否则当成「解析不出来」。
     * 毫秒段与版本段都是可选的，老文件（`...-140322-v15.db`、`...-140322.db`）照样能解析。
     */
    private val STAMP_REGEX =
        Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})(?:-\\d{3})?(?:-v(\\d+))?$")

    private fun stampOf(name: String): String =
        name.removePrefix(WebDavClient.BACKUP_PREFIX).removeSuffix(".db")

    /** 从文件名解析备份时间（毫秒）；解析不出来返回 null */
    fun timestampOf(name: String): Long? {
        val m = STAMP_REGEX.matcher(stampOf(name))
        if (!m.matches()) return null
        // 逐段校验范围：宽松的 SimpleDateFormat 会把 20261345-996699
        // 滚成另一个日期（甚至未来时间），列表里就会冒出「明天 25:00」
        val year = m.group(1) ?: return null
        val month = m.group(2) ?: return null
        val day = m.group(3) ?: return null
        val hour = m.group(4) ?: return null
        val minute = m.group(5) ?: return null
        val second = m.group(6) ?: return null
        if (month.toInt() !in 1..12 || day.toInt() !in 1..31 ||
            hour.toInt() !in 0..23 || minute.toInt() !in 0..59 || second.toInt() !in 0..59
        ) {
            return null
        }
        val raw = "$year$month$day-$hour$minute$second"
        return runCatching {
            val format = SimpleDateFormat(STAMP_PATTERN, Locale.US).apply { isLenient = false }
            val pos = ParsePosition(0)
            val date = format.parse(raw, pos) ?: return@runCatching null
            if (pos.index != raw.length) null else date.time
        }.getOrNull()
    }

    /** 从文件名解析 schema 版本；老格式（没有 -vN）返回 null */
    fun schemaOf(name: String): Int? {
        val m = STAMP_REGEX.matcher(stampOf(name))
        if (!m.matches()) return null
        return m.group(7)?.toIntOrNull()
    }

    /**
     * 备份时间：优先文件名里的时间戳，其次服务器上的修改时间。
     * 两个都没有就是 null（只能显示原始文件名）。
     */
    fun effectiveTimestamp(file: RemoteBackupFile): Long? =
        timestampOf(file.name) ?: file.lastModified
}
