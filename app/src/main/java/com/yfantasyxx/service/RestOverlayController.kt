package com.yfantasyxx.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.yfantasyxx.MainActivity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/**
 * 休息悬浮窗。
 *
 * 通知栏在桌面/别的应用里很容易被忽略（横幅一闪就没了，锁屏上也只是小卡片），
 * 所以这里再盖一层系统级窗口：
 *
 * - **计时中**：右上角一个圆形气泡，环随剩余时间收缩，随时能瞥一眼还剩几秒；
 * - **结束时**：换成一张带呼吸边框的大卡片，停 12 秒，点一下直接回 App。
 *
 * 默认只在 App 不在前台时显示——人已经在训练页里的时候屏幕上本来就有休息条。
 * 开了「悬浮窗常驻」之后（[persistent]）：
 *
 * - 前台也照常显示气泡；
 * - 结束卡片照常停 12 秒，然后**收成一颗小气泡常驻**；
 * - 这颗小气泡没有关闭按钮、也不会自己消失，唯一的出口是去设置里
 *   关掉「悬浮窗常驻」开关（见 [RestTimerService] 里的设置监听）。
 *
 * 需要 `SYSTEM_ALERT_WINDOW` 权限（用户手动授予，[permissionIntent] 负责引导）。
 * 这个权限同时也是后台启动 Activity 的豁免项，所以点悬浮窗回 App 不会被系统拦。
 */
class RestOverlayController(private val context: Context) {

    companion object {
        const val MODE_COUNTDOWN = 0
        const val MODE_DONE = 1

        /** 完成之后常驻的小气泡：满环 + 一个勾，没有关闭按钮 */
        const val MODE_PERSIST = 2

        private const val PREFS = "rest_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        /** 结束卡片停留时长：够长能注意到，又不至于一直赖在屏幕上 */
        private const val DONE_DISMISS_MS = 12_000L

        /** 卡片收成气泡：淡出 → 换尺寸换位置 → 淡入，中间那一下看不见就不生硬 */
        private const val FADE_OUT_MS = 120L
        private const val FADE_IN_MS = 160L

        /** 悬浮窗权限重新检查间隔 */
        private const val PERMISSION_RECHECK_MS = 5_000L

        private const val COUNTDOWN_DP = 58f
        private const val DONE_WIDTH_DP = 236f
        private const val DONE_HEIGHT_DP = 108f

        private val BG = 0xF2161B24.toInt()
        private val ACCENT = 0xFF4C8DFF.toInt()

        /** 进程级单例。常驻模式下卡片要活过前台服务的生命周期，不能跟着服务一起销毁 */
        @Volatile
        private var shared: RestOverlayController? = null

        /**
         * 拿到进程级的悬浮窗控制器。
         *
         * 用 applicationContext 建：前台服务 12 秒后就 stopSelf，
         * 窗口挂在 Service 上会跟着一起没了，卡片留不住。
         */
        fun shared(context: Context): RestOverlayController =
            shared ?: synchronized(this) {
                shared ?: RestOverlayController(context.applicationContext).also { shared = it }
            }

        /** 是否已授予悬浮窗权限 */
        fun canShow(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        /** 跳系统设置开权限。必须带 NEW_TASK，Service 里也能用 */
        fun permissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 常驻模式。
     *
     * 由 Service 按设置写入：前台也显示气泡，结束卡片停 12 秒后收成常驻小气泡。
     */
    var persistent: Boolean = false

    private val windowManager: WindowManager? =
        ContextCompat.getSystemService(context, WindowManager::class.java)

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 权限缓存：5 秒内不重复问系统。
     *
     * 每次 tick 都问一次 `canDrawOverlays` 太浪费，但只问一次也不行：
     * 用户可能在休息期间去系统设置里撤销/授予权限，缓存成永久值会让
     * 「中途授权」不生效、「中途撤销」后每秒都试着 addView 失败。
     */
    private var permitted: Boolean = canShow(context)
    private var permittedCheckedAt: Long = System.currentTimeMillis()

    private fun stillPermitted(): Boolean {
        val now = System.currentTimeMillis()
        if (now - permittedCheckedAt >= PERMISSION_RECHECK_MS) {
            permitted = canShow(context)
            permittedCheckedAt = now
        }
        return permitted
    }

    private var view: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissTask: Runnable? = null

    val isShowing: Boolean get() = view != null

    /** 当前是气泡还是结束卡片；没显示时返回 null */
    val mode: Int? get() = view?.mode

    /** 显示/刷新倒计时气泡。返回是否真的显示出来了 */
    fun showCountdown(endTime: Long, totalSeconds: Int, label: String?): Boolean {
        if (!stillPermitted()) return false
        val v = ensureView() ?: return false
        cancelDismiss()
        cancelFade()
        v.mode = MODE_COUNTDOWN
        v.endTime = endTime
        v.totalSeconds = totalSeconds.coerceAtLeast(1)
        v.label = label
        applySize(MODE_COUNTDOWN)
        // 上一轮结束卡片比气泡宽得多，clampIntoScreen 挪过位置；
        // 切回气泡时把用户自己摆的位置还原回来
        restoreSavedPosition()
        v.invalidate()
        // 常驻气泡是静态画面，ticker 早就停了，这里得重新拉起来
        v.startTicker()
        return true
    }

    /**
     * 结束卡片。返回是否真的显示出来了。
     *
     * [lingerMs] 是卡片停留时长，默认 12 秒。测试工具传 0 可以跳过这段等待，
     * 直接看它收成常驻小球的样子。
     */
    fun showFinished(label: String?, lingerMs: Long = DONE_DISMISS_MS): Boolean {
        if (!stillPermitted()) return false
        val v = ensureView() ?: return false
        cancelDismiss()
        cancelFade()
        v.mode = MODE_DONE
        v.label = label
        applySize(MODE_DONE)
        clampIntoScreen()
        v.invalidate()
        v.startTicker()

        // 时间到：常驻模式收成小气泡，否则直接撤掉
        val task = Runnable {
            if (persistent) switchToPersistentBubble() else hide()
        }
        dismissTask = task
        handler.postDelayed(task, lingerMs.coerceAtLeast(0L))
        return true
    }

    /**
     * 把结束卡片收成常驻小气泡。
     *
     * 卡片比气泡大得多，直接换尺寸会「啪」地缩一下。这里先淡出、在透明的
     * 那一瞬间换成气泡的尺寸和位置、再淡入——同一个窗口换形态，屏幕上
     * 始终只有一个悬浮窗，大卡片不会残留成第二个。
     */
    private fun switchToPersistentBubble() {
        cancelDismiss()
        val v = view ?: return
        if (!stillPermitted()) {
            hide()
            return
        }

        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                // 淡出这一小段时间里可能被 hide() 掉了
                val target = view ?: return@withEndAction
                target.mode = MODE_PERSIST
                applySize(MODE_PERSIST)
                restoreSavedPosition()
                target.invalidate()
                target.startTicker()
                target.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            }
            .start()
    }

    /** 加时之后同步一下气泡上的时间 */
    fun updateCountdown(endTime: Long, totalSeconds: Int) {
        val v = view ?: return
        v.endTime = endTime
        v.totalSeconds = totalSeconds.coerceAtLeast(1)
        v.invalidate()
    }

    fun hide() {
        cancelDismiss()
        val v = view ?: return
        view = null
        params = null
        v.animate().cancel()
        v.stopTicker()
        runCatching { windowManager?.removeView(v) }
    }

    private fun cancelDismiss() {
        dismissTask?.let { handler.removeCallbacks(it) }
        dismissTask = null
    }

    /** 已经在淡出/淡入的半路上了又来了新指令：把动画掐掉、透明度复位 */
    private fun cancelFade() {
        val v = view ?: return
        v.animate().cancel()
        v.alpha = 1f
    }

    @Suppress("DEPRECATION")
    private fun ensureView(): OverlayView? {
        view?.let { return it }
        val wm = windowManager ?: return null
        val v = OverlayView(context)
        val lp = WindowManager.LayoutParams(
            dp(COUNTDOWN_DP),
            dp(COUNTDOWN_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 尽量让锁屏上也露出来（部分机型/系统版本会强制拦下）。
                // 真正的锁屏兜底还是靠「休息结束」那条通知，它有 PUBLIC 可见性
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val screenWidth = context.resources.displayMetrics.widthPixels
            x = prefs.getInt(KEY_X, screenWidth - dp(COUNTDOWN_DP) - dp(12f))
            y = prefs.getInt(KEY_Y, dp(72f))
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) {
            // 权限被撤销/被 ROM 拦下：把缓存置为 false 并记下时间，
            // 下次重试推到 5 秒后，否则 tick 每秒都会新建一个 View 再抛一次异常
            permitted = false
            permittedCheckedAt = System.currentTimeMillis()
            return null
        }
        view = v
        params = lp
        v.startTicker()
        return v
    }

    private fun applySize(mode: Int) {
        val v = view ?: return
        val lp = params ?: return
        if (mode == MODE_DONE) {
            lp.width = dp(DONE_WIDTH_DP)
            lp.height = dp(DONE_HEIGHT_DP)
        } else {
            lp.width = dp(COUNTDOWN_DP)
            lp.height = dp(COUNTDOWN_DP)
        }
        runCatching { windowManager?.updateViewLayout(v, lp) }
    }

    /** 卡片比气泡宽得多，靠右停的时候会顶出屏幕，往里收一下 */
    private fun clampIntoScreen() {
        val v = view ?: return
        val lp = params ?: return
        val dm = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(v, lp) }
    }

    private fun savePosition() {
        val lp = params ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, lp.x)
            .putInt(KEY_Y, lp.y)
            .apply()
    }

    /**
     * 把用户自己摆的位置还原回来。
     *
     * 结束卡片比气泡宽得多，靠右停的时候 [clampIntoScreen] 会把 x 往里收；
     * 下一轮切回气泡如果沿用那个坐标，位置就飘了。
     */
    private fun restoreSavedPosition() {
        val v = view ?: return
        val lp = params ?: return
        val dm = context.resources.displayMetrics
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultX = dm.widthPixels - dp(COUNTDOWN_DP) - dp(12f)
        val defaultY = dp(72f)
        lp.x = prefs.getInt(KEY_X, defaultX)
            .coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = prefs.getInt(KEY_Y, defaultY)
            .coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(v, lp) }
    }

    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        }
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private inner class OverlayView(context: Context) : View(context) {

        var mode = MODE_COUNTDOWN
        var endTime = 0L
        var totalSeconds = 1
        var label: String? = null

        private val density = resources.displayMetrics.density
        private fun px(value: Float) = value * density

        // 不加阴影：硬件加速下 Paint 的 shadowLayer 对图形无效，
        // 要生效只能整层转软件绘制，划不来
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(1.2f)
            color = 0x33FFFFFF
        }
        private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(3.5f)
            strokeCap = Paint.Cap.ROUND
            color = 0x28FFFFFF
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(3.5f)
            strokeCap = Paint.Cap.ROUND
            color = ACCENT
        }
        private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80FFFFFF.toInt()
        }
        private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(1.6f)
            strokeCap = Paint.Cap.ROUND
            color = 0x99FFFFFF.toInt()
        }
        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(2.6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ACCENT
        }

        private val arcRect = RectF()
        private val closeRect = RectF()
        private val checkPath = Path()

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        private val ticker = object : Runnable {
            override fun run() {
                invalidate()
                // 常驻气泡是静态画面（满环 + 勾），画一次就够，不用一直刷
                if (mode == MODE_PERSIST) return
                // 结束卡片在呼吸，刷新率高一点；气泡一秒一跳就够了
                postDelayed(this, if (mode == MODE_COUNTDOWN) 200L else 40L)
            }
        }

        fun startTicker() {
            removeCallbacks(ticker)
            post(ticker)
        }

        fun stopTicker() {
            removeCallbacks(ticker)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            when (mode) {
                MODE_DONE -> drawDone(canvas)
                MODE_PERSIST -> drawPersistent(canvas)
                else -> drawCountdown(canvas)
            }
        }

        private fun drawCountdown(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f

            canvas.drawCircle(cx, cy, radius - px(1f), bgPaint)
            canvas.drawCircle(cx, cy, radius - px(1f), borderPaint)

            val stroke = px(3.5f)
            val inset = stroke / 2f + px(1.5f)
            arcRect.set(
                cx - radius + inset,
                cy - radius + inset,
                cx + radius - inset,
                cy + radius - inset,
            )

            val remaining = remainingSeconds()
            val fraction = (remaining / totalSeconds.toFloat()).coerceIn(0f, 1f)
            val sweep = 360f * fraction

            canvas.drawArc(arcRect, -90f, 360f, false, ringBgPaint)
            if (sweep > 0f) canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)

            clockPaint.textSize = px(14f)
            canvas.drawText(clockOf(remaining), cx, cy + px(5f), clockPaint)
        }

        /**
         * 常驻气泡：满环 + 一个勾。
         *
         * 倒计时已经走完，再显示「0:00」没有意义，画个勾说明这轮休息过去了。
         * 画面是静态的，ticker 到这儿就停。
         */
        private fun drawPersistent(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f

            canvas.drawCircle(cx, cy, radius - px(1f), bgPaint)
            canvas.drawCircle(cx, cy, radius - px(1f), borderPaint)

            val stroke = px(3.5f)
            val inset = stroke / 2f + px(1.5f)
            arcRect.set(
                cx - radius + inset,
                cy - radius + inset,
                cx + radius - inset,
                cy + radius - inset,
            )
            canvas.drawArc(arcRect, -90f, 360f, false, ringPaint)

            checkPath.reset()
            checkPath.moveTo(cx - px(8f), cy + px(0.5f))
            checkPath.lineTo(cx - px(2.5f), cy + px(6f))
            checkPath.lineTo(cx + px(8.5f), cy - px(5.5f))
            canvas.drawPath(checkPath, checkPaint)
        }

        private fun drawDone(canvas: Canvas) {
            val rect = RectF(px(1f), px(1f), width - px(1f), height - px(1f))
            val corner = px(22f)

            canvas.drawRoundRect(rect, corner, corner, bgPaint)

            borderPaint.strokeWidth = px(1.2f)
            borderPaint.color = 0x33FFFFFF
            canvas.drawRoundRect(rect, corner, corner, borderPaint)

            // 边框呼吸：静止的卡片在满屏图标里很容易被当成壁纸的一部分
            val phase = (System.currentTimeMillis() % 1500L) / 1500f
            val pulse = (sin(phase * 2.0 * Math.PI).toFloat() + 1f) / 2f
            borderPaint.strokeWidth = px(1.6f)
            borderPaint.color = withAlpha(ACCENT, (0x50 + 0x8F * pulse).toInt())
            canvas.drawRoundRect(rect, corner, corner, borderPaint)

            titlePaint.textSize = px(21f)
            canvas.drawText("休息结束", px(20f), px(40f), titlePaint)

            bodyPaint.textSize = px(13f)
            val body = label?.takeIf { it.isNotBlank() } ?: "可以开始下一组了"
            canvas.drawText(fit(body, bodyPaint, width - px(56f)), px(20f), px(65f), bodyPaint)

            hintPaint.textSize = px(10.5f)
            val hint = if (persistent) "轻点回 App · 稍后收成小球" else "轻点回到 App"
            canvas.drawText(fit(hint, hintPaint, width - px(40f)), px(20f), px(87f), hintPaint)

            // 右上角关闭：不想回 App 也能直接关掉
            val cx = width - px(20f)
            val cy = px(20f)
            val half = px(5f)
            canvas.drawLine(cx - half, cy - half, cx + half, cy + half, closePaint)
            canvas.drawLine(cx + half, cy - half, cx - half, cy + half, closePaint)
            closeRect.set(cx - px(14f), cy - px(14f), cx + px(14f), cy + px(14f))
        }

        private fun remainingSeconds(): Float =
            ((endTime - System.currentTimeMillis()) / 1000f).coerceAtLeast(0f)

        private fun clockOf(seconds: Float): String {
            val total = ceil(seconds).toInt()
            return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }

        private fun fit(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > px(6f) || abs(dy) > px(6f))) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(this, lp) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        savePosition()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleTap(event.x, event.y)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun handleTap(x: Float, y: Float) {
            if (mode == MODE_DONE && closeRect.contains(x, y)) {
                // 常驻模式下 × 不能真把悬浮窗关掉，只是提前收成常驻气泡
                if (persistent) switchToPersistentBubble() else hide()
                return
            }
            openApp()
            // 非常驻模式点卡片就收掉；常驻卡片 12 秒后自己会收成气泡。
            // 常驻气泡（MODE_PERSIST）没有关闭入口，点它只会回 App
            if (mode == MODE_DONE && !persistent) hide()
        }
    }
}
