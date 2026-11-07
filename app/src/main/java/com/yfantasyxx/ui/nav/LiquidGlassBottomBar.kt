package com.yfantasyxx.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.yfantasyxx.ui.nav.liquid.DampedDragAnimation
import com.yfantasyxx.ui.nav.liquid.InnerShadow
import com.yfantasyxx.ui.nav.liquid.dither
import com.yfantasyxx.ui.nav.liquid.innerShadow
import com.yfantasyxx.ui.nav.liquid.lens
import com.yfantasyxx.ui.nav.liquid.pad
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏外框高度（不含上下留白） */
private val BarHeight = 64.dp

/** 药丸高度；比外框矮 8dp，四周留白 */
private val PillHeight = 56.dp

/** 药丸与底栏左右边缘的内缩 */
private val PillInset = 4.dp

/** 底栏与屏幕左右边缘的距离 */
private val BarEdge = 16.dp

/**
 * 按下时药丸的放大倍数。56 → 78，正好是「鼓起一格」的量。
 * 数值来自 iOS 26 的实测比例，改小会显得没力气。
 */
private const val PRESSED_SCALE = 78f / 56f

/**
 * 仿 iOS 26 的「液态玻璃」底栏。
 *
 * 和 HyperOS 风格的 [FloatingBottomBar] 的区别不只是透明度：
 *
 * 1. **真实采样背景**：[drawBackdrop] 抓取下方内容层做模糊 + 提饱和，不是靠半透明色块假装磨砂；
 * 2. **药丸是能捏起来的**：按住会等比放大（[PRESSED_SCALE]），边缘折射（[lens]）随按压增强，
 *    药丸的高光同时拉满——这是「液态」的来源，也是它和普通滑块最大的差别；
 * 3. **拖动带阻尼**：位置由 [DampedDragAnimation] 的弹簧驱动，药丸会慢手指一点点，
 *    甩到底栏边缘时整条玻璃还会被「拉住」（橡胶回弹）；
 * 4. **药丸是一块透明玻璃，不是一层内容**：它画在图标**下面**，只带极薄的雾白、
 *    边缘高光和内阴影，看不到任何重绘的图标或文字。拖动时是「玻璃在图标上滑过去」，
 *    图标始终留在原位，被压住时轻轻鼓一下。
 *
 * 图标与文字始终全部显示（iOS 的做法），切换时不会有文字突然出现/消失导致的宽度跳变。
 */
@Composable
fun LiquidGlassBottomBar(
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    backdrop: LayerBackdrop,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val capsule = CircleShape
    val accent = MiuixTheme.colorScheme.primary
    val idleTint = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val count = tabs.size
    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 每格宽度 / 底栏总宽，靠 onSizeChanged 拿；都为 0 时先不画药丸
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    val insetPx = with(density) { PillInset.toPx() }

    // 拖到两端之外时整条玻璃被拉动，位移用一个独立的 animatable 累加，松手弹回
    val rubberOffset = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 3.dp.toPx() }
    // 拖出边界时整条玻璃最多被拉走这么多，再多就假了
    val rubberLimitPx = with(density) { 24.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (rubberOffset.value / totalWidthPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    // 按下时的手指位置（相对底栏），轻点时用它算落在哪一格
    var tapX by remember { mutableFloatStateOf(0f) }
    val tabsState = rememberUpdatedState(tabs)
    val onSelectState = rememberUpdatedState(onSelect)

    val dampedDrag = remember(scope, count) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(count - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = PRESSED_SCALE,
            onDragStarted = { touchX ->
                tapX = touchX
                // 药丸**中心**对准手指，而不是吸附到最近的格子中心：
                // 吸附会让药丸相对手指固定偏移最多半格，看起来「总是偏一边」，
                // 从左往右扫的时候尤其明显。
                if (tabWidthPx > 0f) {
                    val target = (touchX - insetPx - tabWidthPx / 2f) / tabWidthPx
                    updateValue(target.coerceIn(0f, (count - 1).toFloat()))
                }
            },
            onDragStopped = { dragged ->
                val target = targetValue.roundToInt().coerceIn(0, count - 1)
                if (dragged) {
                    // 拖到别的格子：换页
                    if (currentIndex != target) {
                        currentIndex = target
                        onSelectState.value(tabsState.value[target].route)
                    } else if (abs(value - target) > 0.02f) {
                        animateToValue(target.toFloat())
                    }
                } else {
                    // 轻点：按按下位置算落在哪一格，直接切过去。
                    // 不看 selectable 自己的点击——那一路会被手势层吃掉 up，
                    // 而且点在两格交界时取整会飘。
                    val tapped = if (tabWidthPx > 0f) {
                        ((tapX - insetPx) / tabWidthPx).toInt().coerceIn(0, count - 1)
                    } else {
                        target
                    }
                    if (tapped != currentIndex) {
                        currentIndex = tapped
                        onSelectState.value(tabsState.value[tapped].route)
                    } else if (abs(value - tapped) > 0.02f) {
                        // 点的是当前页：只把药丸吸回格子中央
                        animateToValue(tapped.toFloat())
                    }
                }
                scope.launch { rubberOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { dragAmount ->
                if (tabWidthPx > 0f) {
                    val raw = targetValue + dragAmount / tabWidthPx
                    val clamped = raw.coerceIn(0f, (count - 1).toFloat())
                    updateValue(clamped)
                    // 只有已经顶到两端、还继续往外拖时，整条玻璃才被「拉住」。
                    // 之前是无条件累加，于是正常横拖时整条底栏会顺着拖动方向
                    // 偏出去最多 3dp，药丸跟着一起偏——手感上就是「总偏右」。
                    val overshoot = raw - clamped
                    if (overshoot != 0f) {
                        // 先把目标算出来再 launch：以前把加法写在协程里，
                        // 快速连续拖动时每个协程读到的都是「已经被改过」的值，
                        // 回弹位移会叠加，一次猛拖能偏出很远
                        val next = (rubberOffset.value + overshoot * tabWidthPx)
                            .coerceIn(-rubberLimitPx, rubberLimitPx)
                        scope.launch { rubberOffset.snapTo(next) }
                    }
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) currentIndex = selectedIndex
    }
    // 外部改选中项（点击 tab、代码里跳转）时药丸自己滑过去，并走一遍鼓起-落下
    LaunchedEffect(dampedDrag) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDrag.animateToValue(index.toFloat())
        }
    }

    // 边缘高光。原来用 Miuix 分叉里的 BloomStroke + 重力感应光源（手机倾斜时高光跟着走），
    // 上游 Kyant0/backdrop 只有 Plain / Default / Ambient 三种样式。
    //
    // 这里选 Plain 而不是 Default：Default 的 AGSL 会把 `style.color` 的 alpha
    // 强制拉成 1（`setColorUniform("color", color.copy(alpha = 1f))`），
    // 亮度只剩 `Highlight.alpha` 一档，照搬原来的色值会亮一大截；
    // Plain 是纯描边，最终亮度 = 颜色 alpha × Highlight.alpha，
    // 和原来的 BloomStroke 算法完全一致，静态观感不变，也不再随重力偏移。
    val edgeStyle = remember(isDark) {
        HighlightStyle.Plain(
            color = Color.White.copy(alpha = if (isDark) 0.10f else 0.16f),
        )
    }
    val pillHighlight = remember {
        Highlight(
            width = 1.dp,
            blurRadius = 2.dp,
            alpha = 1f,
            style = HighlightStyle.Plain(color = Color.White.copy(alpha = 0.12f)),
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
                .padding(horizontal = BarEdge, vertical = 10.dp)
                .height(BarHeight),
        ) {
            // ---------- 玻璃本体 ----------
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { translationX = panelOffset }
                    .shadow(
                        elevation = if (isDark) 20.dp else 16.dp,
                        shape = capsule,
                        ambientColor = Color.Black.copy(alpha = 0.14f),
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.30f else 0.20f),
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsule },
                        effects = {
                            blur(18.dp.toPx())
                            // 上游库的 blur 不再自动撑大离屏层，这里手动补回原来的录制范围
                            pad(18.dp.toPx())
                            // 提饱和是玻璃「通透」的关键，否则模糊后一片灰
                            colorControls(
                                brightness = if (isDark) -0.02f else 0.03f,
                                contrast = 1.04f,
                                saturation = 1.6f,
                            )
                            // 整条玻璃的边缘也做一点折射，才像一块有厚度的板
                            lens(
                                refractionHeight = 14.dp.toPx(),
                                refractionAmount = 10.dp.toPx(),
                            )
                            // 抖动放在最后：模糊之后再撒噪点才有消色带的作用
                            dither(0.006f)
                        },
                        // blurRadius 对应原来 BloomStroke 的 innerBlurRadius = 3dp，
                        // 不写的话默认是 width/2，描边会变得像一条硬线
                        highlight = {
                            Highlight(
                                width = 1.1.dp,
                                blurRadius = 3.dp,
                                alpha = 0.85f,
                                style = edgeStyle,
                            )
                        },
                    )
                    .clip(capsule),
            ) {
                // 玻璃底色：上下深浅不同的乳白（暗色主题下是烟灰）
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
            }

            // ---------- 药丸 ----------
            if (tabWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        // 位置在布局阶段算，读 value 不会引起每帧重组
                        .offset {
                            IntOffset(
                                (PillInset.toPx() + tabWidthPx * dampedDrag.value + panelOffset)
                                    .roundToInt(),
                                0,
                            )
                        }
                        .width(with(density) { tabWidthPx.toDp() })
                        .height(PillHeight),
                ) {
                    // 玻璃层：整块等比放大，溢出格子才是「捏起来」的样子
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = dampedDrag.scaleX
                                scaleY = dampedDrag.scaleY
                                // 甩得越快横向拉得越长，像液体被甩出去
                                val v = dampedDrag.velocity / 10f
                                scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                            }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { capsule },
                                effects = {
                                    val press = dampedDrag.pressProgress
                                    // 比底栏更清透：折射 + 高饱和，像一块凸起的玻璃
                                    colorControls(brightness = 0f, contrast = 1f, saturation = 1.35f)
                                    blur(4.dp.toPx())
                                    pad(4.dp.toPx())
                                    lens(
                                        refractionHeight = 10.dp.toPx() * press,
                                        refractionAmount = 14.dp.toPx() * press,
                                        depthEffect = true,
                                        chromaticAberration = 0.35f,
                                    )
                                },
                                // 高光常驻一点点，按下时再拉满：不加常驻量的话
                                // 松手后药丸会整个「消失」，只剩图标浮在底栏上
                                highlight = {
                                    pillHighlight.copy(
                                        alpha = 0.45f + 0.55f * dampedDrag.pressProgress,
                                    )
                                },
                                onDrawSurface = {
                                    // 只铺一层很薄的雾白，让玻璃球有存在感。
                                    // 原来是 0.58 的实白，透不出底下的东西，
                                    // 拖起来像拖着一块不透明的白板。
                                    drawRect(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(
                                                    alpha = if (isDark) 0.10f else 0.16f,
                                                ),
                                                Color.White.copy(
                                                    alpha = if (isDark) 0.03f else 0.04f,
                                                ),
                                            )
                                        )
                                    )
                                },
                            )
                            .innerShadow(capsule) {
                                val press = dampedDrag.pressProgress
                                // 常驻一点内阴影撑住球体的厚度，按下时加深
                                InnerShadow(
                                    radius = 3.dp + 7.dp * press,
                                    color = Color.Black.copy(alpha = 0.12f),
                                    alpha = 0.5f + 0.5f * press,
                                )
                            },
                    )

                    // 这里不再画图标。药丸现在绘制在图标层**下面**，
                    // 图标始终由底栏那层直接画在最上面：拖动时看到的是
                    // 「一块透明玻璃在图标上滑过」，而不是「把图标拖着走」。
                }
            }

            // ---------- 底层 tab 内容 ----------
            // 画在药丸之上，所以永远清晰、永远不会被玻璃挡住；
            // 药丸压过来时图标只是轻轻鼓一下（下面 graphicsLayer 里算的）。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = panelOffset }
                    .onSizeChanged { coords ->
                        totalWidthPx = coords.width.toFloat()
                        tabWidthPx = ((totalWidthPx - insetPx * 2) / count).coerceAtLeast(0f)
                    }
                    .padding(horizontal = PillInset),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    // 药丸是弹簧滑过去的（几百毫秒），图标与文字如果瞬间换色，
                    // 会先于药丸亮起来，看着像「点错了自己跳过去」。让颜色跟药丸
                    // 一起渐变，到达时才完全变亮。
                    val tabTint by androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) accent else idleTint,
                        animationSpec = com.yfantasyxx.ui.theme.Motion.smooth(),
                        label = "liquidTabTint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .graphicsLayer {
                                // 药丸离得越近，图标鼓得越多，像被玻璃吸住。
                                // 不用 alpha 淡出：图标在药丸上层，遮不住也没必要藏。
                                val pull = (1f - abs(index - dampedDrag.value) / 0.6f)
                                    .coerceIn(0f, 1f)
                                val s = 1f + 0.18f * pull * dampedDrag.pressProgress
                                scaleX = s
                                scaleY = s
                            }
                            .clip(RoundedCornerShape(percent = 50))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { if (!selected) onSelect(tab.route) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tabTint,
                                modifier = Modifier.size(21.dp),
                            )
                            TabBadgeDot(
                                visible = tab.badge,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 1.dp, y = (-1).dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            color = tabTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            // ---------- 手势层 ----------
            // 盖在最上面且不挡视觉：按住整条底栏任何位置都能拖药丸，
            // 只点不拖时事件仍会往下传给 tab 的 selectable
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(dampedDrag.modifier),
            )
        }
    }
}
