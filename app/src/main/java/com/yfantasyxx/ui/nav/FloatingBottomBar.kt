package com.yfantasyxx.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.yfantasyxx.ui.components.pressScale
import com.yfantasyxx.ui.nav.liquid.dither
import com.yfantasyxx.ui.nav.liquid.pad
import com.yfantasyxx.ui.theme.Motion
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/** 悬浮底栏的 tab 定义 */
data class FloatingTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
    /** 右上角的小红点：有新版本时「我的」用 */
    val badge: Boolean = false,
)

/**
 * 悬浮底栏：浮在内容之上、圆角胶囊 + 细描边 + 阴影。
 *
 * [glass] 开启时用 Kyant0/backdrop 采样并模糊下方内容（真毛玻璃效果）；
 * 关闭时使用不透明背景（默认），避免高刷屏上额外的 GPU 模糊开销。
 *
 * ## 布局为什么是「固定槽位」
 *
 * 上一版把选中项的文字用 `expandHorizontally` 在 Row 里展开，而 Row 用的是
 * `Arrangement.SpaceEvenly`：选中项一变宽，剩余空间就变了，**五个图标的
 * 位置全部重算**。切页签时能看见另外四个图标横着漂一下——这是最「拉垮」
 * 的一种抖，因为它不是动画，是布局跳变。
 *
 * 现在改成：
 *
 * - 五个槽位宽度**恒定**（`weight(1f)`），未选中项的图标永远在槽位正中；
 * - 胶囊和文字是一个整体，在槽位里居中，展开时**只在这一格内部**发生；
 * - 展开用弹簧而不是 tween：中途再点另一个 tab 时动画能续上速度，
 *   不会「重新开始」。
 *
 * 代价是选中项的图标会向左让出约 12dp 给文字（iOS 的选中态也是这么做的，
 * 文字从右边推出来）。但**其它四个图标纹丝不动**，这才是关键。
 */
@Composable
fun FloatingBottomBar(
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    backdrop: LayerBackdrop,
    glass: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val surface = MiuixTheme.colorScheme.surface
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // 吃掉落在底栏区域（含四周空隙）的点击，避免穿透到下层的列表项
            .pointerInput(Unit) {
                detectTapGestures(onTap = { })
            },
    ) {
    Surface(
        shape = shape,
        color = if (glass) Color.Transparent else surface,
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .height(64.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glass) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                // 轻微模糊 + 噪点抖动 + 提一点饱和度，液态玻璃的“通透感”
                                blur(12f)
                                pad(12f)
                                colorControls(
                                    brightness = 0.03f,
                                    contrast = 1.02f,
                                    saturation = 1.45f,
                                )
                                // 上游库没有内建抖动，这里补一层极细噪点压色带
                                dither(0.012f)
                            },
                            // 玻璃边缘的高光描边（iOS 液态玻璃的关键特征）。
                            // 用 Plain 而不是 Default：后者会把颜色 alpha 强制成 1，
                            // 只剩 Highlight.alpha 一个亮度旋钮，和原来的观感对不上
                            highlight = {
                                Highlight(
                                    width = 1.dp,
                                    alpha = 0.9f,
                                    style = HighlightStyle.Plain(
                                        color = Color.White.copy(
                                            alpha = if (isDark) 0.14f else 0.28f,
                                        ),
                                    ),
                                )
                            },
                            onDrawSurface = {
                                drawRect(color = surface.copy(alpha = 0.12f))
                            },
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(shape)
                .border(
                    width = 0.6.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f),
                    shape = shape,
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 每个 tab 的中心点（相对这一行），拖动时用来找最近的一个
            val centers = remember { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
            val latestRoute = rememberUpdatedState(selectedRoute)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(tabs.size) {
                        var lastX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { lastX = it.x },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                lastX = change.position.x
                            },
                            onDragEnd = {
                                val nearest = centers.minByOrNull { abs(it.value - lastX) }
                                    ?.key ?: return@detectHorizontalDragGestures
                                val route = tabs.getOrNull(nearest)?.route
                                if (route != null && route != latestRoute.value) onSelect(route)
                            },
                        )
                    },
                // 不用 SpaceEvenly：每个槽位用 weight(1f) 等分整行宽度，
                // 位置由 weight 决定，与子项宽度无关。展开选中项时
                // 其它槽位的位置一个像素都不会动。
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = tab.route == selectedRoute

                    // 胶囊底色的透明度单独动画：只动 alpha，RGB 保持不变。
                    // 直接从 Color.Transparent 过渡到 primary 的话，
                    // 中间值是「半透明的深色」，浅色主题下会看到一圈脏边。
                    val pillAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = Motion.smooth(),
                        label = "tabPillAlpha",
                    )

                    // 图标颜色跟着胶囊的 alpha 走，而不是跟着「选中」这个布尔量走。
                    //
                    // 如果图标在胶囊淡出时**瞬间**变成灰色，浅色主题下会出现
                    // 「白色图标浮在浅色背景上」的一瞬——白字在白底上等于消失。
                    // 让图标颜色跟着同一个 alpha 插值，它在胶囊变淡的同时
                    // 同步变灰，任何一帧都看得清。
                    val iconTint = lerp(
                        MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        MiuixTheme.colorScheme.onPrimary,
                        pillAlpha,
                    )

                    Box(
                        modifier = Modifier
                            // 槽位宽度恒定：未选中时图标永远在槽位正中，
                            // 展开选中态也不会推走任何兄弟节点
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                centers[index] = coords.boundsInParent().center.x
                            }
                            // 按下时整格轻微缩小，给一个「确实按到了」的实感
                            .pressScale(pressedScale = 0.9f)
                            .clickable { if (!selected) onSelect(tab.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 胶囊 + 图标 + 文字是一个整体，在槽位里居中。
                        // 文字展开时这一格变宽，但槽位宽度不变 → 别的格子不动。
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .background(
                                    MiuixTheme.colorScheme.primary.copy(alpha = pillAlpha)
                                )
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconTint,
                                )
                                TabBadgeDot(
                                    visible = tab.badge,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-1).dp),
                                )
                            }
                            AnimatedVisibility(
                                visible = selected,
                                // 弹簧：中途切到别的 tab 时能续上速度，
                                // tween 会从当前位置按新曲线重走，中间「拐一下」
                                enter = fadeIn(Motion.snappy()) +
                                    expandHorizontally(
                                        animationSpec = Motion.smooth(),
                                        expandFrom = Alignment.Start,
                                    ),
                                exit = fadeOut(Motion.snappy()) +
                                    shrinkHorizontally(
                                        animationSpec = Motion.smooth(),
                                        shrinkTowards = Alignment.Start,
                                    ),
                            ) {
                                Row {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = tab.label,
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        color = MiuixTheme.colorScheme.onPrimary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
