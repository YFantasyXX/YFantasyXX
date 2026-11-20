package com.yfantasyxx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfantasyxx.ui.glass.isGlassDark
import com.yfantasyxx.ui.theme.Motion
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 玻璃开关。
 *
 * MIUIX 的 `Switch` 是一条纯色胶囊加一个白球，和周围那圈玻璃卡片不是一个材质。
 * 这里把它换成同一套语言：**同色系渐变轨道 + 1dp 边缘亮线 + 白瓷滑块**。
 *
 * ## 为什么不用 `softGlass`
 *
 * 设置页整体在 `layerBackdrop` 采集层**里面**。玻璃件如果去 `drawBackdrop` 采样，
 * 采到的就是「正在录制自己的那张离屏图」——HWUI 在 prepareTree 上无限递归，
 * vc131 那次 SIGSEGV 就是这么来的。所以页面内的玻璃一律**自绘**：
 * 靠颜色层次和边缘光表达厚度，不读背景。
 *
 * 好处是这玩意一个 `RuntimeShader` 都不用，纯 Canvas 画两个圆角矩形 + 一个圆，
 * 比 MIUIX 原版多不了多少。
 *
 * @param checked 是否打开
 * @param onCheckedChange 切换回调
 * @param enabled 关掉时整块降到 0.4，仍然看得出是个开关
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    // 不写 `by`：状态要留在 State 里，只在绘制/布局阶段读 value，
    // 否则滑块每帧都会触发一次重组
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.snappy(),
        label = "glassSwitch",
    )

    val trackW: Dp = 50.dp
    val trackH: Dp = 30.dp
    val thumbD: Dp = 24.dp
    val inset: Dp = (trackH - thumbD) / 2
    val travel: Dp = trackW - thumbD - inset * 2f

    val opacity = if (enabled) 1f else 0.4f

    // 开态：主色玻璃，两段同色系提亮压暗。往白里兑会让蓝变粉，
    // 这里只缩放 RGB，亮的是同一个颜色
    val onTop = scheme.primary.shadeSameHue(1.16f)
    val onBottom = scheme.primary.shadeSameHue(0.84f)

    // 关态分深浅两套。
    //
    // 深色主题里 `onSurface` 本身就是白色，拿它兑 15% / 7% 当轨道 = 一层薄白雾，
    // 卡片底色透上来还是灰的，和 24dp 的纯白滑块糊成一片 —— 用户原话「关掉就看不清楚，
    // 全是白的」。所以深色下换思路：轨道画成比卡片更暗的**凹槽**（页面底色 + 弱白边勾轮廓），
    // 滑块降成中灰。开=亮蓝凸起，关=黑色凹陷，不看颜色也能分辨。
    val isDark = isGlassDark()
    val offTop: Color
    val offBottom: Color
    val offStroke: Color
    val thumbOffTop: Color
    val thumbOffBottom: Color
    if (isDark) {
        offTop = scheme.surface
        // 底边提一点亮：内凹的受光方向和凸起相反
        offBottom = lerp(scheme.surface, scheme.onSurface, 0.10f)
        offStroke = Color.White.copy(alpha = 0.20f)
        thumbOffTop = Color(0xFFDCDCDC)
        thumbOffBottom = Color(0xFF9A9A9A)
    } else {
        offTop = scheme.onSurface.copy(alpha = 0.15f)
        offBottom = scheme.onSurface.copy(alpha = 0.07f)
        offStroke = scheme.onSurface.copy(alpha = 0.12f)
        thumbOffTop = Color.White
        thumbOffBottom = Color(0xFFE9E9E9)
    }

    Box(
        modifier = modifier
            .size(width = trackW, height = trackH)
            .clip(CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .drawBehind {
                val p = progress.value
                val topColor = lerp(offTop, onTop, p)
                val bottomColor = lerp(offBottom, onBottom, p)
                // 乘，不能直接 copy(alpha = opacity)：关态轨道本身就是半透明的
                // （浅色 0.15），覆盖成 0.4 会让「禁用」反而变得更实
                val top = topColor.copy(alpha = topColor.alpha * opacity)
                val bottom = bottomColor.copy(alpha = bottomColor.alpha * opacity)
                val radius = CornerRadius(size.height / 2f)

                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(top, bottom)),
                    cornerRadius = radius,
                )
                // 边缘亮线。关着的时候也画一条弱一点的：
                // 灰轨道在灰卡片上几乎没有边界，没有这条线就看不出是个控件
                val strokeColor = lerp(offStroke, Color.White.copy(alpha = 0.28f), p)
                drawRoundRect(
                    color = strokeColor.copy(alpha = strokeColor.alpha * opacity),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx()),
                )
            },
        // 垂直居中、水平贴左：滑块本身只负责横向移动，
        // 上下那 3dp 的边距由这里对齐，别写进 offset 里
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                // 位置在布局阶段算：lambda 版的 offset 读 State 只重排不重组
                .offset {
                    IntOffset((inset + travel * progress.value).roundToPx(), 0)
                }
                .size(thumbD)
                .drawBehind {
                    // 滑块颜色也跟着进度走：关态（深色是中灰）→ 开态（白瓷）。
                    // 只改颜色不改透明度，opacity 是整块禁用用的
                    val p = progress.value
                    drawCircle(
                        brush = Brush.verticalGradient(
                            listOf(
                                lerp(thumbOffTop, Color.White, p).copy(alpha = opacity),
                                lerp(thumbOffBottom, Color(0xFFE9E9E9), p).copy(alpha = opacity),
                            )
                        ),
                    )
                },
        )
    }
}

/** 同色系提亮/压暗：RGB 等比缩放，色相和饱和度不变。 */
private fun Color.shadeSameHue(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)
