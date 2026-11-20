package com.yfantasyxx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OS4 那一版的玻璃按钮。
 *
 * ## 表面只有两层
 *
 * 一块同色系渐变（上略亮、下略沉）+ 一条 1dp 的边缘亮线。就这两层，
 * 不用 `background` 再垫一层底——渐变的上下两端已经把整块盖住了，
 * 多铺一层等于每帧多一次全尺寸填充，而且底色的边界会从半透明渐变的缝里透出来。
 *
 * ## 为什么不是「上白下黑」
 *
 * 上一版照玻璃的思路在顶部叠了 26% 白：蓝底上等于往颜色里兑白，
 * 上半截直接洗成粉蓝，整块像一张褪色的渐变纸。**提亮必须同色系**——
 * 把 RGB 一起乘一个略大于 1 的系数，饱和度和色相都不动，只是亮了半档；
 * 底部乘小于 1 的系数同理。这样在深色背景上仍然是一块实心主色按钮，
 * 只是多了一层几乎看不出的弧面。
 *
 * @param subtle 次要按钮：不填主色，只铺一层 5~9% 的表面色。取消 / 稍后再说这类用。
 * @param content 内容走 `RowScope`，和 MIUIX 的按钮签名一致（图标 + 文字横排）。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtle: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val scheme = MiuixTheme.colorScheme
    val base = scheme.primary

    val contentColor = when {
        !enabled -> scheme.disabledOnSurface
        subtle -> scheme.onSurface
        else -> scheme.onPrimary
    }

    // 禁用时整块压到 0.4：仍然看得出是个按钮，但一眼知道按不动。
    // 不放在 Modifier.alpha() 里，那样会额外开一层离屏图层。
    val opacity = if (enabled) 1f else 0.4f

    // 三段式同色渐变：上沿最亮、中段回到本色、下沿压暗。
    //
    // 第一段就是「受光的那道亮边」——但它是同一个颜色提亮，不是往里兑白。
    // 兑白会同时抬高 RGB 三个通道里的最小值，蓝就变成粉蓝，也就是上一版难看的原因。
    val stops: List<Pair<Float, Color>> = if (subtle) {
        val tint = scheme.onSurface
        listOf(
            0f to tint.copy(alpha = 0.12f * opacity),
            0.38f to tint.copy(alpha = 0.07f * opacity),
            1f to tint.copy(alpha = 0.04f * opacity),
        )
    } else {
        listOf(
            0f to base.shade(1.18f).copy(alpha = opacity),
            0.36f to base.shade(1.01f).copy(alpha = opacity),
            1f to base.shade(0.84f).copy(alpha = opacity),
        )
    }
    val rimColor = if (subtle) {
        scheme.onSurface.copy(alpha = 0.13f * opacity)
    } else {
        Color.White.copy(alpha = 0.26f * opacity)
    }

    // 渐变对象在组合期建一次就够了：Brush 的坐标是「相对绘制区域」的，
    // 拿到尺寸是绘制那一刻的事。写在 drawBehind 里会变成每帧新建一个对象。
    val fill = Brush.verticalGradient(*stops.toTypedArray())

    Box(
        modifier = modifier
            .pressScale(pressedScale = 0.96f, enabled = enabled)
            .clip(shape)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(brush = fill, cornerRadius = radius)
                // 边缘亮线：深色背景上唯一的轮廓。
                // 玻璃感全在这条线和上面那道同色高光上，不靠半透明
                drawRoundRect(
                    color = rimColor,
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .clickable(enabled = enabled, onClick = onClick)
            // 42dp 是 MIUIX 按钮的高度；不能再低，训练页单手操作时按不准
            .heightIn(min = 42.dp)
            .padding(horizontal = 22.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 文字和图标都靠 LocalContentColor 取色：调用点大多只写了 Text("确定")，
        // 没给 color，原先是 MIUIX Button 内部提供的 onPrimary
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

/**
 * 同色系提亮/压暗：RGB 等比缩放，色相和饱和度不变。
 *
 * 和 `Color.lerp(this, Color.White, t)` 的区别是那个会**降低饱和度**——
 * 蓝底上兑白就是粉蓝，也就是上一版按钮难看的原因。
 */
private fun Color.shade(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)
