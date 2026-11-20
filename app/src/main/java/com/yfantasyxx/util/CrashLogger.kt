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
 *  - getExternalFilesDir/logs/crash-*.txt：带时间戳的存档
 *
 * 存档路径是 /Android/data/<包名>/files/logs/。注意 API 30+ 起这个目录
 * 对文件管理器基本不可见（分区存储），用户拿不到——真正的出口是
 * 测试工具里的「诊断包」，它会把 logs/ 打进 zip 再走 FileProvider 分享。
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

                // 2) 带时间戳的存档，供诊断包收集
                val dir = logDir(app)
                if (!dir.exists()) dir.mkdirs()
                File(dir, "crash-${timeStamp("yyyyMMdd-HHmmss")}.txt").writeText(text)
            }
            // 交回上一级处理器。正常情况下它就是 KillProcessHandler，会让进程退出；
            // 但它是 null（没装处理器、或被人换成了空实现）时不能什么都不做——
            // 异常线程会带着坏掉的状态继续跑，界面卡死又不留痕。这里兜一个 halt。
            val delegate = previous
            if (delegate != null) {
                delegate.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().halt(1)
            }
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
