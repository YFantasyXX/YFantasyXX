package com.yfantasyxx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yfantasyxx.ui.glass.DialogWindowGlass
import com.yfantasyxx.ui.glass.glassPanel
import com.yfantasyxx.ui.glass.isGlassDark
import com.yfantasyxx.ui.glass.material.GlassShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 重量刻度盘：长按重量数字弹出的圆形表盘。
 *
 * ## 为什么是「圆圈」而不是滑块
 *
 * 步进器的 ± 只能一次 2.5kg：70 调到 100 得点 12 下，还得盯着数字数。
 * 圆形表盘把整个量程摊在 360° 上，**0 在正上方、顺时针递增**，
 * 转一圈就是整个量程，一格 2.5kg —— 手指绕过去，重量跟着走，
 * 大跨度调整一次到位，小跨度也能靠刻度找回来。
 *
 * ## 交互约定
 *
 * - 手指按在环上任意位置 = 直接跳到那个角度（圆盘类控件的标准行为）；
 * - 之后绕着圆心拖动，读数连续跟随，跨过一格震一下（`TextHandleMove`）；
 * - 中间的数字区是**死区**：离圆心太近时角度噪声极大，按下去不动，
 *   否则手指抖一像素重量就跳几十公斤；
 * - 松手 / 点空白 / 「完成」都会提交一次。提交只在结束时发生一次——
 *   拖动过程中每帧写库会把 Room 打爆。
 *
 * ## 刻度
 *
 * 一圈 [max]/25 根主刻度（默认 250kg → 10 根），细刻度不画：
 * 100 格画出来就是一圈毛边，反而看不清当前位置。当前位置由
 * 主色圆弧 + 白色描边的滑块表示。
 */
@Composable
fun WeightDialDialog(
    initialValue: Float,
    onCommit: (Float) -> Unit,
    onDismiss: () -> Unit,
    step: Float = 2.5f,
    max: Float = 250f,
    unit: String = "kg",
) {
    val isDark = isGlassDark()
    val scheme = MiuixTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    val motion = rememberOverlayMotion(fromScale = 0.92f, toScale = 0.96f)
    LaunchedEffect(Unit) { motion.enter() }

    // 别把超过量程的重量「按回去」：用户本来就是 260kg，长按看一眼再取消，
    // 结果被夹到 250 就等于悄悄改了训练数据。超量程时把这一圈临时放大到能装下。
    //
    // 顺序很重要：dialMax 必须**先**算出来，初始值要按 dialMax 吸附。
    // 之前先按 max 吸附、再放大量程，260 一进来就已经被夹成 250 了，
    // 放大那一圈等于白做——打开又关掉，数据还是被改掉了。
    val dialMax = remember(initialValue, max) {
        if (initialValue <= max) max else (ceil(initialValue / 25f) * 25f)
    }
    var value by remember { mutableFloatStateOf(snapValue(initialValue, step, dialMax)) }

    // 死区半径：取盘面半径的 1/6 左右，正好盖住中间那两行字
    val deadZoneRatio = 0.17f

    fun commitAndDismiss() {
        onCommit(value)
        onDismiss()
    }

    Dialog(
        onDismissRequest = { commitAndDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
        DialogWindowGlass(radius = 48f, dim = 0.30f)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 遮罩自己画：窗口是全屏的，「点空白」在系统那边不一定判得出来。
            // 用 scrimFade 把 alpha 乘进颜色，不开全屏离屏图层。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scrimFade(motion, 0.34f)
                    .clickable(interactionSource = null, indication = null) { commitAndDismiss() },
            )

            val side = minOf(maxWidth - 40.dp, 300.dp)

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .overlayTransform(motion),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(side)
                        .glassPanel(
                            shape = GlassShape(cornerRadius = side / 2),
                            isDark = isDark,
                            // 体色要压得住：这层下面就是训练列表，0.9 以下
                            // 背后的组号和重量会透上来，和中间的大字糊在一起
                            bodyAlpha = 0.96f,
                        )
                        .pointerInput(dialMax, step) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val dialSide = minOf(size.width, size.height).toFloat()

                                // 按下去直接跳到这个角度
                                value = dialValueAt(
                                    position = down.position,
                                    size = size,
                                    max = dialMax,
                                    step = step,
                                    deadZone = dialSide * deadZoneRatio,
                                    fallback = value,
                                )
                                down.consume()
                                var lastStepIndex = stepIndexOf(value, step)

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (change.positionChanged()) {
                                        value = dialValueAt(
                                            position = change.position,
                                            size = size,
                                            max = dialMax,
                                            step = step,
                                            deadZone = dialSide * deadZoneRatio,
                                            fallback = value,
                                        )
                                        val now = stepIndexOf(value, step)
                                        if (now != lastStepIndex) {
                                            lastStepIndex = now
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    DialRing(value = value, max = dialMax, isDark = isDark)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = formatDialValue(value),
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = unit,
                                fontSize = 13.sp,
                                color = scheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(bottom = 9.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "绕圈拖动 · 每格 ${formatDialValue(step)}",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                GlassButton(onClick = { commitAndDismiss() }) {
                    Text(
                        text = "完成",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 表盘本体：轨道 + 进度弧 + 主刻度 + 滑块。全部 Canvas，无着色器。 */
@Composable
private fun DialRing(value: Float, max: Float, isDark: Boolean) {
    val scheme = MiuixTheme.colorScheme
    val trackColor = scheme.onSurface.copy(alpha = if (isDark) 0.14f else 0.10f)
    val tickColor = scheme.onSurface.copy(alpha = if (isDark) 0.22f else 0.16f)
    val progress = scheme.primary

    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 14.dp.toPx()
        // 环外侧还要留出刻度，所以整圈往里收 12dp
        val radius = size.minDimension / 2f - 12.dp.toPx() - stroke / 2f
        val c = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = trackColor, radius = radius, center = c, style = Stroke(stroke))

        val sweep = 360f * (value / max).coerceIn(0f, 1f)
        if (sweep > 0.05f) {
            drawArc(
                color = progress,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        // 主刻度：每 25kg 一根，画在环外侧
        val majors = (max / 25f).roundToInt().coerceAtLeast(1)
        val tickInner = radius + stroke / 2f + 3.dp.toPx()
        val tickOuter = tickInner + 5.dp.toPx()
        for (i in 0 until majors) {
            val rad = ((i * 360f / majors - 90f) * PI / 180f).toFloat()
            drawLine(
                color = tickColor,
                start = Offset(c.x + tickInner * cos(rad), c.y + tickInner * sin(rad)),
                end = Offset(c.x + tickOuter * cos(rad), c.y + tickOuter * sin(rad)),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // 滑块：白圈打底再叠主色，压在主色弧上才看得出「这是一颗可以抓的球」
        val thumbRad = ((sweep - 90f) * PI / 180f).toFloat()
        val thumb = Offset(
            c.x + radius * cos(thumbRad),
            c.y + radius * sin(thumbRad),
        )
        drawCircle(color = Color.White, radius = 11.dp.toPx(), center = thumb)
        drawCircle(color = progress, radius = 7.dp.toPx(), center = thumb)
    }
}

/** 吸附到 [step] 的整数倍并夹在 0..[max]。 */
private fun snapValue(v: Float, step: Float, max: Float): Float =
    ((v / step).roundToInt() * step).coerceIn(0f, max)

private fun stepIndexOf(v: Float, step: Float): Int = (v / step).roundToInt()

/**
 * 手指位置 → 重量。0 在正上方，顺时针递增一整圈到 [max]。
 *
 * 屏幕坐标 y 向下，`atan2(dy, dx)` 本身就是顺时针为正，所以只要补 90°，
 * 把「正上方」对到 0 就行。
 */
private fun dialValueAt(
    position: Offset,
    size: IntSize,
    max: Float,
    step: Float,
    deadZone: Float,
    fallback: Float,
): Float {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    // 死区：圆心附近角度不稳定，指尖在那里抖一下就是几十公斤
    if (hypot(dx, dy) < deadZone) return fallback

    val deg = atan2(dy, dx) * 180f / PI.toFloat()
    var angle = (deg + 90f) % 360f
    if (angle < 0f) angle += 360f
    return snapValue(angle / 360f * max, step, max)
}

private fun formatDialValue(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString()
    else String.format(java.util.Locale.US, "%.1f", v)
