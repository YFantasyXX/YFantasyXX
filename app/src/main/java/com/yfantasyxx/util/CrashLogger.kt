package com.yfantasyxx.util

import android.content.Context
import android.os.Build
import com.yfantasyxx.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录最近一次未捕获异常，方便用户反馈问题。
 *
 * 同时写两处：
 *  - filesDir/last_crash.txt：首页展示用
 *  - getExternalFilesDir/logs/crash-*.txt：带时间戳的存档，
 *    路径为 /Android/data/<包名>/files/logs/，文件管理器可直接查看，无需任何权限
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"
    private const val LOG_DIR = "logs"

    /** 日志目录：优先 App 外部私有目录，不可用时退回内部私有目录 */
    private fun logDir(context: Context): File =
        context.getExternalFilesDir(LOG_DIR)
            ?: File(context.filesDir, LOG_DIR).also { if (!it.exists()) it.mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                val text = buildString {
                    appendLine("time     : ${timeStamp("yyyy-MM-dd HH:mm:ss")}")
                    appendLine("version  : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
                    appendLine("device   : ${Build.BRAND} ${Build.MODEL}")
                    appendLine("android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("--------- stack trace ---------")
                    append(stack)
                }

                // 1) 首页展示
                File(app.filesDir, FILE_NAME).writeText(text)

                // 2) 带时间戳的存档，供文件管理器查看
                val dir = logDir(app)
                if (!dir.exists()) dir.mkdirs()
                File(dir, "crash-${timeStamp("yyyyMMdd-HHmmss")}.txt").writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLast(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() }
            ?.readText()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** 清空所有历史日志文件 */
    fun clearHistory(context: Context) {
        runCatching {
            logDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun timeStamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date())
}
