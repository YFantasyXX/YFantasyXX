package com.yfantasyxx

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.yfantasyxx.data.prefs.applySafeModeDefaults
import com.yfantasyxx.util.CrashLogger
import com.yfantasyxx.util.SafeModeGuard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 安全模式。
 *
 * 这个界面**故意不用 Compose、不用 MiuixTheme、不用 backdrop**，全部是
 * 原生 View。因为进到这里的前提就是「主界面起不来」——如果安全模式还走
 * 同一套渲染栈，它自己也会跟着崩，那就白做了。
 *
 * 它只做三件事：
 *  1. 告诉用户发生了什么（连续几次启动没活下来）；
 *  2. 给一条能真正解决问题的出路（关掉液态玻璃/毛玻璃）；
 *  3. 把诊断信息导出，方便反馈。
 *
 * 不碰训练数据、不碰备份、不碰账号口令。
 */
class SafeModeActivity : Activity() {

    private val bgColor = 0xFFF5F5F7.toInt()
    private val cardColor = 0xFFFFFFFF.toInt()
    private val textColor = 0xFF17171A.toInt()
    private val textSubColor = 0xFF6B6B73.toInt()
    private val primaryColor = 0xFF3482FF.toInt()
    private val secondaryColor = 0xFFECECF1.toInt()
    private val dangerColor = 0xFFFF3B30.toInt()

    private lateinit var buttons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val streak = SafeModeGuard.streak(this).coerceAtLeast(SafeModeGuard.THRESHOLD)
        val ago = SafeModeGuard.sinceLastFaultMs(this)
        val lastCrash = CrashLogger.readLast(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(40))
            setBackgroundColor(bgColor)
        }

        root.addView(label("安全模式", 26f, textColor, bold = true))
        root.addView(spacer(10))
        root.addView(
            label(
                "连续 $streak 次打开都没能撑过启动阶段。界面相关的模块已经全部停用，" +
                    "这样你才有机会进来说明情况、把设置改回去。",
                14f, textSubColor,
            )
        )

        root.addView(spacer(22))
        root.addView(diagnosisCard(streak, ago))

        root.addView(spacer(18))
        root.addView(
            label(
                "下面三个按钮的区别：\n" +
                    "· 第一个会把底栏改回「悬浮胶囊」并关掉毛玻璃 —— 大多数启动崩溃都是它引起的；\n" +
                    "· 第二个不动任何设置，只在这次运行里绕开玻璃和转场，冷启动后会重新判定；\n" +
                    "· 第三个什么都不改，直接再试一次。",
                12.5f, textSubColor,
            )
        )

        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
            it.topMargin = dp(16)
        })

        buttons.addView(
            pill("关闭玻璃模糊并进入", primaryColor, 0xFFFFFFFF.toInt()) { applyDefaultsAndEnter() }
        )
        buttons.addView(
            pill("强制安全渲染进入（不改设置）", secondaryColor, textColor) {
                enter(degraded = true)
            }
        )
        buttons.addView(
            pill("直接进入（什么都不改）", secondaryColor, textColor) {
                enter(degraded = false)
            }
        )
        buttons.addView(spacer(6))
        buttons.addView(
            pill("复制诊断信息", secondaryColor, textColor) { copyDiagnosis(streak, ago, lastCrash) }
        )
        buttons.addView(
            pill("分享诊断包", secondaryColor, textColor) { shareDiagnosis(streak, ago, lastCrash) }
        )

        if (!lastCrash.isNullOrBlank()) {
            root.addView(spacer(24))
            root.addView(label("最近一次未捕获异常", 13f, textColor, bold = true))
            root.addView(spacer(8))
            root.addView(
                label(lastCrash.takeLast(4000), 11f, textSubColor).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = rounded(cardColor, 12)
                }
            )
        }

        root.addView(spacer(28))
        root.addView(
            label(
                "你的训练记录、模板、设置都在本地，安全模式一个都没动。",
                11.5f, textSubColor,
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgColor)
            addView(root)
        })
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 关掉玻璃、改回悬浮胶囊。
     *
     * DataStore 的写入是协程，不能挂在 Activity 的生命周期上——用户点完
     * 马上就会跳走，作用域一取消写入就丢了。所以单开线程 + runBlocking，
     * 写完了再回主线程跳转。
     */
    private fun applyDefaultsAndEnter() {
        setBusy(true)
        Thread {
            val result = runCatching {
                runBlocking {
                    withTimeoutOrNull(3_000L) { applicationContext.applySafeModeDefaults() }
                }
            }
            runOnUiThread {
                setBusy(false)
                if (result.getOrNull() != null) {
                    enter(degraded = false)
                } else {
                    toast("设置写入失败，可以先试「强制安全渲染进入」")
                }
            }
        }.start()
    }

    private fun enter(degraded: Boolean) {
        SafeModeGuard.exit(this, keepDegraded = degraded)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun setBusy(busy: Boolean) {
        for (i in 0 until buttons.childCount) {
            buttons.getChildAt(i).isEnabled = !busy
            buttons.getChildAt(i).alpha = if (busy) 0.5f else 1f
        }
    }

    private fun copyDiagnosis(streak: Int, ago: Long, lastCrash: String?) {
        val text = diagnosisText(streak, ago, lastCrash)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YFantasyXX 诊断", text))
        toast("已复制，粘贴到聊天里发我")
    }

    private fun shareDiagnosis(streak: Int, ago: Long, lastCrash: String?) {
        runCatching {
            val dir = File(cacheDir, "shares").apply { if (!exists()) mkdirs() }
            val file = File(dir, "safe-mode-${System.currentTimeMillis()}.txt")
            file.writeText(diagnosisText(streak, ago, lastCrash))

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "YFantasyXX 安全模式诊断")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享诊断信息"))
        }.onFailure { toast("分享失败：${it.message}") }
    }

    // ---------------------------------------------------------------- 内容

    private fun diagnosisCard(streak: Int, ago: Long): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(cardColor, 16)
        }
        card.addView(row("版本", "v${BuildConfig.VERSION_NAME} (vc${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}"))
        card.addView(row("设备", "${Build.BRAND} ${Build.MODEL}"))
        card.addView(row("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"))
        card.addView(row("异常启动", "$streak 次", dangerColor))
        card.addView(row("上次异常", agoLabel(ago)))
        return card
    }

    private fun row(key: String, value: String, valueColor: Int = textColor): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(
                label(key, 13f, textSubColor).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f)
                }
            )
            addView(
                label(value, 13f, valueColor).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.64f)
                    setTextIsSelectable(true)
                }
            )
        }
    }

    private fun diagnosisText(streak: Int, ago: Long, lastCrash: String?): String = buildString {
        appendLine("YFantasyXX 安全模式诊断")
        appendLine("版本     : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        appendLine("包名     : $packageName")
        appendLine("设备     : ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("系统     : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("异常启动 : $streak 次")
        appendLine("上次异常 : ${agoLabel(ago)}")
        appendLine("--------- last uncaught exception ---------")
        append(lastCrash?.takeIf { it.isNotBlank() } ?: "（没有 Java 层未捕获异常，说明是原生崩溃）")
    }

    private fun agoLabel(ms: Long): String {
        if (ms < 0L) return "无记录"
        val seconds = ms / 1000
        return when {
            seconds < 5 -> "刚刚"
            seconds < 60 -> "${seconds} 秒前"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            else -> "${seconds / 3600} 小时前"
        }
    }

    // ---------------------------------------------------------------- 控件

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(4).toFloat(), 1f)
        }

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = rounded(bg, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.topMargin = dp(10)
            }
        }

    private fun spacer(dpValue: Int) = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(dpValue))
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
