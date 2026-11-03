package com.yfantasyxx.ui.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.yfantasyxx.AppContainer
import com.yfantasyxx.BuildConfig
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一键诊断包。
 *
 * 以前排查问题要分四步：导出库文件、翻文件管理器找 `logs/`、截图设置页、再说机型版本——
 * 而且 WebDAV 密码就在设置里，截图必然泄露。这里打成一个 zip，
 * 口令一律打码，分享面板一键发出去。
 */
object DevDiagnostics {

    /** 日志最多带几份：崩溃日志一份就几 KB，但连崩十几次时没必要全带上 */
    private const val MAX_LOG_FILES = 8

    /**
     * 打包。返回的 zip 落在 `cacheDir/shares/`——
     * `file_paths.xml` 只把这一层授权给 FileProvider，不用额外开目录。
     */
    suspend fun build(context: Context, container: AppContainer, info: DevInfo): File =
        withContext(Dispatchers.IO) {
            // 一次读取就够，不需要持续收集；awaitSettings 保证拿到的是真实设置
            // 而不是 StateFlow 的默认占位值（诊断包里的设置有误导性就白做了）
            val settings = runCatching {
                container.settingsRepository.awaitSettings()
            }.getOrNull()

            val dir = File(context.cacheDir, "shares").also { it.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "yfantasyxx-diag-$stamp.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }

                put("device.txt", deviceReport(context, info).toByteArray())

                settings?.let { put("settings.txt", settingsReport(it).toByteArray()) }

                // 库：临时 vacuum 一份，塞进 zip 后立刻删掉，不占额外空间
                val tmpDb = File(context.cacheDir, "diag-snapshot.db")
                runCatching { tmpDb.delete() }
                val vacuumed = runCatching {
                    container.db().openHelper.writableDatabase.execSQL(
                        "VACUUM INTO '${tmpDb.absolutePath.replace("'", "''")}'"
                    )
                    tmpDb.exists() && tmpDb.length() > 0
                }.getOrDefault(false)
                if (vacuumed) {
                    put("db/snapshot.db", tmpDb.readBytes())
                    runCatching { tmpDb.delete() }
                } else {
                    put("db/README.txt", "快照生成失败，本次诊断包不含数据库".toByteArray())
                }

                CrashLogger.readLast(context)?.let {
                    put("crash/last_crash.txt", it.toByteArray())
                }
                logFiles(context).forEach { file ->
                    runCatching { put("crash/logs/${file.name}", file.readBytes()) }
                }
            }
            out
        }

    /** 调系统分享面板把 zip 发出去 */
    fun share(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "YFantasyXX 诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享诊断包"))
    }

    private fun logFiles(context: Context): List<File> {
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        return dir.listFiles { f -> f.isFile }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(MAX_LOG_FILES)
    }

    private fun deviceReport(context: Context, info: DevInfo): String = buildString {
        appendLine("time       : ${stamp("yyyy-MM-dd HH:mm:ss")}")
        appendLine("version    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        appendLine("package    : ${BuildConfig.APPLICATION_ID}")
        appendLine("device     : ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("manufacture: ${Build.MANUFACTURER}")
        appendLine("android    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abis       : ${Build.SUPPORTED_ABIS.joinToString()}")
        val dm = context.resources.displayMetrics
        appendLine("screen     : ${dm.widthPixels}x${dm.heightPixels} @${dm.density} dpi=${dm.densityDpi}")
        val rt = Runtime.getRuntime()
        appendLine(
            "memory     : 已用 ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024} MB / " +
                "上限 ${rt.maxMemory() / 1024 / 1024} MB"
        )
        appendLine("schema     : v${info.schemaVersion}")
        appendLine("db size    : ${DevSnapshots.sizeLabel(info.dbBytes)}")
        appendLine("plan       : ${info.planName ?: "(无启用计划)"}")
        appendLine("plan detail: ${info.planDetail ?: "-"}")
        appendLine("--------- 表行数 ---------")
        info.tables.forEach { appendLine("${it.table.padEnd(20)} ${it.rows}") }
    }

    private fun settingsReport(s: AppSettings): String = buildString {
        appendLine("# WebDAV 口令一律不写、用户名打码")
        appendLine("themeMode            = ${s.themeMode}")
        appendLine("weightUnit           = ${s.weightUnit}")
        appendLine("restSeconds          = ${s.restSeconds}")
        appendLine("restAlertSound       = ${s.restAlertSound}")
        appendLine("restOverlay          = ${s.restOverlay}")
        appendLine("restOverlayPersistent= ${s.restOverlayPersistent}")
        appendLine("autoBackup           = ${s.autoBackup}")
        appendLine("lastBackupTime       = ${s.lastBackupTime?.let { stampAt(it) } ?: "-"}")
        appendLine("bottomBarStyle       = ${s.bottomBarStyle}")
        appendLine("blurBottomBar        = ${s.blurBottomBar}")
        appendLine("tapHaptics           = ${s.tapHaptics}")
        appendLine("barWeightKg          = ${s.barWeightKg}")
        appendLine("plates               = ${s.plates}")
        appendLine("webdavUrl            = ${maskUrl(s.webdavUrl)}")
        appendLine("webdavUsername       = ${mask(s.webdavUsername)}")
        appendLine("webdavPassword       = ${if (s.webdavPassword.isBlank()) "(空)" else "***(已省略)"}")
    }

    /** 用户名这类短串：留头留尾，中间打码 */
    private fun mask(value: String): String = when {
        value.isBlank() -> "(空)"
        value.length <= 2 -> "*".repeat(value.length)
        else -> value.first() + "*".repeat((value.length - 2).coerceAtMost(8)) + value.last()
    }

    /** URL 里可能写着 `user:pass@host`，只保留协议和主机 */
    private fun maskUrl(url: String): String {
        if (url.isBlank()) return "(空)"
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty()) return url
        val rest = url.substringAfter("://")
        return if ("@" in rest) "$scheme://***@${rest.substringAfter("@")}" else url
    }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.CHINA).format(Date())

    private fun stampAt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))

    /** 供调用方判空用：zip 有可能写失败（空间不足等） */
    fun ensureUsable(file: File): File {
        if (!file.exists() || file.length() <= 0L) throw IOException("诊断包没生成出来")
        return file
    }
}
