package com.yfantasyxx.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 训练分享卡片。
 *
 * 直接用 android.graphics 画，不走 Compose 截图：
 * 截图要等界面布局完成、还要处理主题与滚动位置，而这张卡片是固定版式，
 * 直接画反而更可控（深浅色主题下都是同一张图，发出去观感一致）。
 * 输出到 cacheDir，由 FileProvider 授权给分享目标，用完系统会自己清理。
 */
object ShareCard {

    data class Data(
        val title: String,
        val date: String,
        val durationMinutes: Int,
        val exerciseCount: Int,
        val setCount: Int,
        val volumeKg: Float,
        /** 亮点：如「杠铃卧推 100 kg × 8」 */
        val highlights: List<String>,
    )

    private const val W = 900
    private const val H = 1200

    private val bgTop = Color.parseColor("#12131A")
    private val bgBottom = Color.parseColor("#1D1F2A")
    private val accent = Color.parseColor("#4C8DFF")
    private val accent2 = Color.parseColor("#8B5CF6")

    fun write(context: Context, data: Data): File {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景：竖向渐变 + 右上角一团辉光，避免纯色死板
        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), bgTop, bgBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null

        paint.color = accent
        paint.alpha = 46
        canvas.drawCircle(W * 0.86f, 120f, 260f, paint)
        paint.color = accent2
        paint.alpha = 34
        canvas.drawCircle(W * 0.1f, H * 0.92f, 300f, paint)
        paint.alpha = 255

        var y = 120f
        // 顶部标记
        paint.color = accent
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 28f
        canvas.drawText("TRAINING LOG", 72f, y, paint)
        y += 26f
        paint.color = Color.parseColor("#66FFFFFF")
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 26f
        canvas.drawText(data.date, 72f, y + 34f, paint)

        // 训练名
        y += 132f
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 72f
        canvas.drawText(ellipsize(data.title, paint, W - 144f), 72f, y, paint)

        // 分隔线
        y += 54f
        paint.color = Color.parseColor("#33FFFFFF")
        canvas.drawRect(72f, y, W - 72f, y + 2f, paint)

        // 四个统计块（2×2），比一行四个更好读
        y += 46f
        val cellW = (W - 144f) / 2f
        stat(canvas, paint, 72f, y, cellW, "时长", durationText(data.durationMinutes))
        stat(canvas, paint, 72f + cellW, y, cellW, "容量", volumeText(data.volumeKg) + " kg")
        y += 130f
        stat(canvas, paint, 72f, y, cellW, "动作", "${data.exerciseCount} 个")
        stat(canvas, paint, 72f + cellW, y, cellW, "组数", "${data.setCount} 组")

        // 亮点
        if (data.highlights.isNotEmpty()) {
            y += 150f
            paint.color = Color.parseColor("#99FFFFFF")
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 28f
            canvas.drawText("本次亮点", 72f, y, paint)
            y += 52f
            paint.color = Color.WHITE
            paint.textSize = 34f
            data.highlights.take(4).forEach { line ->
                paint.color = accent
                canvas.drawCircle(84f, y - 12f, 6f, paint)
                paint.color = Color.WHITE
                canvas.drawText(ellipsize(line, paint, W - 200f), 112f, y, paint)
                y += 58f
            }
        }

        // 底部水印
        paint.color = Color.parseColor("#55FFFFFF")
        paint.textSize = 26f
        canvas.drawText("YFantasyXX · 记录每一次进步", 72f, H - 72f, paint)

        // 圆角裁一下，某些 IM 客户端不会自动加圆角
        val rounded = roundCorners(bitmap, 48f)
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(dir, "workout-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            rounded.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (rounded !== bitmap) rounded.recycle()
        bitmap.recycle()
        return file
    }

    private fun stat(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        width: Float,
        label: String,
        value: String,
    ) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 26f
        paint.color = Color.parseColor("#8AFFFFFF")
        canvas.drawText(label, x, y, paint)

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 54f
        paint.color = Color.WHITE
        canvas.drawText(ellipsize(value, paint, width - 24f), x, y + 62f, paint)
    }

    private fun roundCorners(source: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun durationText(minutes: Int): String = when {
        minutes <= 0 -> "--"
        minutes >= 60 -> "${minutes / 60} 时 ${minutes % 60} 分"
        else -> "$minutes 分钟"
    }

    private fun volumeText(kg: Float): String =
        if (kg >= 10000f) String.format("%.1f 万", kg / 10000f)
        else if (kg >= 1000f) String.format("%.1fk", kg / 1000f)
        else kg.toInt().toString()

    /** 调起系统分享面板 */
    fun share(context: Context, file: File, chooserTitle: String = "分享训练记录") {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
