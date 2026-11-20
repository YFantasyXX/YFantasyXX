package com.yfantasyxx.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.yfantasyxx.ui.components.pressScale
import com.yfantasyxx.ui.glass.material.GlassDefaults
import com.yfantasyxx.ui.glass.material.GlassEdgeStrokes
import com.yfantasyxx.ui.glass.material.GlassMaterials
import com.yfantasyxx.ui.glass.material.GlassShape
import com.yfantasyxx.ui.glass.softGlass
import com.yfantasyxx.ui.theme.Motion
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * ## 几何：药丸必须和胶囊同心
 *
 * 之前是横排（图标 + 右侧文字），药丸被文字撑到约 70dp ≈ 槽位宽度，
 * 而高度只有 34dp：横向缝隙 4dp、纵向缝隙 15dp，
 * 药丸半径 17dp 对不上外层胶囊的 32dp——两段圆弧一大一小、圆心也不重合，
 * 看着就是「小圆角塞在大圆角里」，怎么调都不像同心圆。
 *
 * 现在：
 *
 * - 文字挪到图标下面，药丸是竖向胶囊，宽度不再被文字撑开；
 * - 药丸铺满整个槽位，四周统一留 8dp；
 * - 药丸高 64 - 2×8 = 48dp、半径 24dp = 32 - 8，
 *   首尾两格的药丸圆心与外层胶囊的圆心**完全重合**，是严格的同心圆。
 *
 * ## 动效：一颗药丸滑过去，而不是原地交叉淡入淡出
 *
 * 之前每个 tab 各画一颗药丸，靠 alpha 淡入淡出切换。两个 alpha 一起动 =
 * 「旧的一点点消失、新的一点点出现」，中间那几帧两边都是半透明，
 * 看起来像闪了一下；再叠上按下整格缩 10%，就成了「生硬」的来源。
 *
 * 现在整个底栏只画**一颗**药丸，位置由 `tabPillX` 弹簧驱动，
 * 从上一个槽位滑到下一个槽位：
 *
 * - 位移用弹簧 [Motion.smooth]，能被连续点击打断并继承速度；
 * - 图标做「线框 → 实心」的交叉淡入，不是瞬间换图；
 * - 文字颜色延迟 130ms 才开始变白——药丸约 200ms 到位，
 *   文字要是同步变白，会有几十毫秒「白字在白底上」的空白期；
 * - 按压缩放到 0.92 且只作用于图标 + 文字（药丸是兄弟节点独立绘制），
 *   药丸不会跟着一起缩。
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
    // 底栏外层用全圆胶囊（半径=高度一半），两端弧度和选中药丸一致，
    // 否则 28dp 会比药丸的半圆"方"一圈，左右两端的圆角对不上。
    val shape = RoundedCornerShape(percent = 50)
    val surface = MiuixTheme.colorScheme.surface
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val primary = MiuixTheme.colorScheme.primary
    val activeTint = MiuixTheme.colorScheme.onPrimary
    val idleTint = MiuixTheme.colorScheme.onSurfaceVariantSummary

    // 手势导航（小横条）下 navigationBars 的 inset 常为 0，直接 navigationBarsPadding
    // 会让胶囊贴着屏幕底、压在小横条上。取一个 24dp 保底，三键导航时仍用系统 inset。
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(navInset, 24.dp)

    // -1 = 当前路由不在底栏里（详情页等），此时药丸隐藏
    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset)
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
                            Modifier.softGlass(
                                backdrop = backdrop,
                                shape = GlassShape(cornerRadius = 32.dp),
                                style = GlassDefaults.style(isDark),
                                material = GlassMaterials.puredThinGlass(isDark),
                                stroke = GlassEdgeStrokes.forTheme(isDark),
                                // 外面那层 Surface 已经用 shadowElevation 画过投影了。
                                // 玻璃再画一遍 = 每帧多一整块（比底栏还大一圈）的 AGSL 距离场，
                                // 而两道影子叠在纯黑背景上肉眼根本分辨不出来。
                                shadow = null,
                                // 边缘把背景掰弯：这是「液态」和「磨砂」的分界线。
                                // 只给底栏开，页面里的玻璃件一律平玻璃。
                                refraction = 5f,
                                isDark = isDark,
                                shaderKey = "bar",
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(shape)
                    // 四周统一 8dp：药丸 48dp 高、半径 24dp = 外层半径 32dp - 8dp，
                    // 首尾药丸的圆心与胶囊圆心重合（同心圆）。
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // 槽位等分：药丸宽度 = 槽位宽度，位置 = 槽位索引 × 槽位宽度。
                    // 两者由同一组约束算出，所以药丸永远严丝合缝落在一格上。
                    val slotWidth = maxWidth / tabs.size
                    val slotWidthPx = with(density) { slotWidth.toPx() }

                    // 注意：这里用 State 而不是 by，因为位置要在 offset 的 lambda 里
                    // 读 .value（只重绘、不重组）；写成 by 就变成立刻求值的 Float 了。
                    val pillX = animateFloatAsState(
                        targetValue = (if (selectedIndex >= 0) selectedIndex else 0).toFloat(),
                        // 位置变化走弹簧：中途再点别的 tab 能继承当前速度，
                        // 不会「从半路重新起步」。
                        animationSpec = Motion.smooth(),
                        label = "tabPillX",
                    )
                    val pillAlpha by animateFloatAsState(
                        targetValue = if (selectedIndex >= 0) 1f else 0f,
                        animationSpec = tween(Motion.DURATION_FAST),
                        label = "tabPillAlpha",
                    )

                    // 唯一的一颗药丸，先于所有 tab 绘制（= 在下层）
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((pillX.value * slotWidthPx).roundToInt(), 0) }
                            .width(slotWidth)
                            .fillMaxHeight()
                            // 半径写死 24dp（= 药丸高度 48dp 的一半）而不是 percent=50：
                            // percent 是按宽高各自取百分比，槽位约 67dp 宽时会算成
                            // rx 33.5 / ry 24 的椭圆角，两端就不是正圆了。
                            .background(
                                color = primary.copy(alpha = pillAlpha),
                                shape = RoundedCornerShape(24.dp),
                            ),
                    )

                    // 每个 tab 的中心点（相对这一行），拖动时用来找最近的一个
                    val centers = remember { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
                    val latestRoute = rememberUpdatedState(selectedRoute)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
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
                        // 每个槽位用 weight(1f) 等分整行宽度，位置由 weight 决定，
                        // 与子项宽度无关 → 药丸的 slotWidth 与这里的槽位严格相等。
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = index == selectedIndex

                            // 文字 / 图标颜色：选中的晚一点变白，等药丸滑到脚下。
                            // 不延迟的话，白字会先落到还没被药丸盖住的浅色底上，
                            // 那几十毫秒里等于「文字消失」。
                            val tint by animateColorAsState(
                                targetValue = if (selected) activeTint else idleTint,
                                animationSpec = tween(
                                    durationMillis = if (selected) 120 else 140,
                                    delayMillis = if (selected) 130 else 0,
                                ),
                                label = "tabTint",
                            )

                            // 线框 ↔ 实心图标的交叉淡入（也是 State，在 graphicsLayer 里读）
                            val iconFade = animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(Motion.DURATION_FAST),
                                label = "tabIconFade",
                            )

                            Box(
                                modifier = Modifier
                                    // 槽位宽度恒定：未选中时图标永远在槽位正中
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { coords ->
                                        centers[index] = coords.boundsInParent().center.x
                                    }
                                    // 按下时整格轻微缩小，给一个「确实按到了」的实感。
                                    // 只缩 8%（以前 10% 是整格连药丸一起缩，像被捏扁），
                                    // 而且药丸已经是兄弟节点，这里只影响图标和文字。
                                    .pressScale(pressedScale = 0.92f)
                                    // 不要用 clickable 自带的 indication：MIUIX 主题下它是一层
                                    // 覆盖整个格子（长方形）的涟漪，按下的那一瞬间会闪出一块
                                    // 长方形阴影。视觉反馈已经由 pressScale 提供，这里只留点击语义。
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                    ) { if (!selected) onSelect(tab.route) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box {
                                        Box(modifier = Modifier.size(22.dp)) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { alpha = 1f - iconFade.value },
                                                tint = tint,
                                            )
                                            Icon(
                                                imageVector = tab.selectedIcon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { alpha = iconFade.value },
                                                tint = tint,
                                            )
                                        }
                                        TabBadgeDot(
                                            visible = tab.badge,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 2.dp, y = (-1).dp),
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    // 文字常驻：以前只在选中时淡入，选中项因为有文字而被顶高，
                                    // 另外四个图标的高度就对不齐了；现在五个 tab 结构完全一致，
                                    // 图标永远在同一条水平线上。
                                    Text(
                                        text = tab.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tint,
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
