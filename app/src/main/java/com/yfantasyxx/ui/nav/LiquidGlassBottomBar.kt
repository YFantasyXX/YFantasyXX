package com.yfantasyxx.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.highlight.rememberTiltLight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏外框高度（不含上下留白） */
private val BarHeight = 64.dp

/** 药丸相对外框的内缩，决定留白 */
private val BarInset = 5.dp

/**
 * 仿 iOS 的「液态玻璃」底栏。
 *
 * 与 HyperOS 风格的 [FloatingBottomBar] 的区别不只是透明度：
 *
 * 1. **真实采样背景**：用 miuix-blur 抓取下方内容层做模糊 + 提饱和，
 *    而不是靠半透明色块假装磨砂；
 * 2. **液态高光**：玻璃边缘用 [BloomStroke] 双峰高光着色，光源随手机倾斜
 *    轻微移动（[rememberTiltLight]），晃动手机时边缘亮部会跟着走；
 * 3. **滑动的玻璃药丸**：选中指示是一块独立玻璃，切换时用弹簧滑过去，
 *    并带一点「拉伸」——速度越快横向拉得越长，像液体被甩过去。
 *
 * 图标与文字始终全部显示（iOS 的做法），选中项只换颜色，所以切换时
 * 不会有文字突然出现/消失导致的宽度跳变。
 */
@Composable
fun LiquidGlassBottomBar(
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    backdrop: Backdrop,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val capsule = RoundedCornerShape(percent = 50)
    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    // 药丸位置用连续值表示（0f = 第一个 tab，1.5f = 第一与第二之间）
    val pill = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        pill.animateTo(
            targetValue = selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // 速度决定拉伸：甩得越快，药丸横向越长，松手后回弹
    val stretch = (abs(pill.velocity) * 0.02f).coerceIn(0f, 0.3f)

    // 边缘高光：光源跟着手机倾斜走，白色高光在玻璃边缘扫过
    val tiltLight = rememberTiltLight(
        basePosition = LightPosition(0.5f, 0.28f, -0.55f),
        intensity = if (isDark) 0.7f else 0.55f,
        sensitivity = 0.035f,
    )
    val edgeStyle = remember(isDark, tiltLight) {
        BloomStroke(
            color = Color.White.copy(alpha = if (isDark) 0.10f else 0.16f),
            innerBlurRadius = 3.dp,
            primaryLight = tiltLight,
            // dualPeak 模式下副光源应静音，由主光源产生一对 180° 对称的镜面高光
            secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), intensity = 0f),
            dualPeak = true,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // 吃掉落在底栏区域（含四周留白）的点击，避免穿透到下面的列表
            .pointerInput(Unit) { detectTapGestures(onTap = { }) },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(BarHeight)
                .shadow(
                    elevation = if (isDark) 20.dp else 16.dp,
                    shape = capsule,
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.30f else 0.20f),
                )
                .textureBlur(
                    backdrop = backdrop,
                    shape = capsule,
                    blurRadiusX = 18f,
                    blurRadiusY = 18f,
                    noiseCoefficient = 0.006f,
                    colors = BlurColors(
                        brightness = if (isDark) -0.02f else 0.03f,
                        contrast = 1.04f,
                        // 提饱和是玻璃「通透」的关键，否则模糊后一片灰
                        saturation = 1.6f,
                    ),
                    highlight = Highlight(width = 1.1.dp, alpha = 0.85f, style = edgeStyle),
                )
                .clip(capsule),
        ) {
            // 玻璃本体：上下深浅不同的乳白（暗色主题下是烟灰），叠在模糊之后
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isDark) {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF2A2A2E).copy(alpha = 0.62f),
                                    Color(0xFF141416).copy(alpha = 0.52f),
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.38f),
                                    Color.White.copy(alpha = 0.16f),
                                )
                            )
                        }
                    ),
            )

            // 顶部一道横向镜面反光，模拟玻璃上沿的亮边
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.86f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (isDark) 0.18f else 0.65f),
                                Color.Transparent,
                            )
                        )
                    ),
            )

            // 底部内阴影，让玻璃看起来有厚度
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = if (isDark) 0.22f else 0.05f),
                            )
                        )
                    ),
            )

            val slot = (maxWidth - BarInset * 2) / tabs.size
            val baseWidth = (slot - 4.dp).coerceAtLeast(0.dp)
            val extra = baseWidth * stretch
            val pillWidth = baseWidth + extra
            // 拉伸时向两侧同时展开，视觉上「从中间抻开」
            val pillX = BarInset + slot * pill.value + 2.dp - extra / 2
            val pillShape = RoundedCornerShape(percent = 50)
            val scope = rememberCoroutineScope()
            // 拖动时的落点（松手才真的切页，避免手指还没抬起来页面就跳走）
            val dragTarget = remember { mutableIntStateOf(selectedIndex) }
            val latestSelected = rememberUpdatedState(selectedIndex)
            val slotPx = with(LocalDensity.current) { slot.toPx() }
            val settle = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

            // 选中药丸：本身就是一小块玻璃，比底栏更亮，带独立投影
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = pillX)
                    .width(pillWidth)
                    .height(BarHeight - 10.dp)
                    .shadow(
                        elevation = 5.dp,
                        shape = pillShape,
                        ambientColor = Color.Black.copy(alpha = 0.10f),
                        spotColor = Color.Black.copy(alpha = 0.16f),
                    )
                    .clip(pillShape)
                    .background(
                        if (isDark) {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.20f),
                                    Color.White.copy(alpha = 0.11f),
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.92f),
                                    Color.White.copy(alpha = 0.72f),
                                )
                            )
                        }
                    )
                    .border(
                        width = 0.7.dp,
                        color = Color.White.copy(alpha = if (isDark) 0.22f else 0.95f),
                        shape = pillShape,
                    ),
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = BarInset)
                    // 横向拖过底栏就切换：药丸跟手走，松手停在最近的一个 tab
                    .pointerInput(tabs.size, slotPx) {
                        fun indexAt(x: Float): Int =
                            (x / slotPx).toInt().coerceIn(0, tabs.lastIndex)

                        fun fractionAt(x: Float): Float =
                            (x / slotPx - 0.5f).coerceIn(0f, tabs.lastIndex.toFloat())

                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragTarget.intValue = indexAt(offset.x)
                                scope.launch { pill.snapTo(fractionAt(offset.x)) }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragTarget.intValue = indexAt(change.position.x)
                                scope.launch { pill.snapTo(fractionAt(change.position.x)) }
                            },
                            onDragEnd = {
                                val target = dragTarget.intValue
                                if (target != latestSelected.value) onSelect(tabs[target].route)
                                scope.launch { pill.animateTo(target.toFloat(), settle) }
                            },
                            onDragCancel = {
                                scope.launch {
                                    pill.animateTo(latestSelected.value.toFloat(), settle)
                                }
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val tint by animateColorAsState(
                        targetValue = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        animationSpec = tween(durationMillis = 220),
                        label = "tab-tint",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                        label = "tab-scale",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(percent = 50))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { if (!selected) onSelect(tab.route) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier
                                .size(21.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
