package com.yfantasyxx.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yfantasyxx.ui.components.overlayTransform
import com.yfantasyxx.ui.components.pressScale
import com.yfantasyxx.ui.components.rememberOverlayMotion
import com.yfantasyxx.ui.components.scrimFade
import com.yfantasyxx.ui.glass.material.GlassShape
import com.yfantasyxx.ui.glass.material.GlassShadows
import com.yfantasyxx.ui.theme.Radius
import com.yfantasyxx.ui.theme.Spacing
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用级下拉菜单宿主。
 *
 * ## 为什么不放在页面里
 *
 * 菜单要用 [softGlass] 采样页面背景（真模糊）。采样源是 App 层的
 * `layerBackdrop` 离屏图层，而**玻璃必须在采集层外面**——否则它会被录进
 * 自己正在采样的那张图里，HWUI 在 `prepareTree` 上无限递归，直接 SIGSEGV
 * （底栏踩过一次，见 [com.yfantasyxx.ui.YFantasyXXApp] 里的长注释）。
 *
 * 设置页在采集层**内部**，所以菜单只能提到 App 层渲染。顺带解决了两件事：
 * - 坐标：宿主和触发行在同一个 ComposeView 里，`boundsInRoot()` 拿来就能用，
 *   不用再猜弹层窗口的原点在哪；
 * - 手感：没有新窗口要创建、要抢焦点，点下去那一帧就开始动。
 */
class GlassMenuHostState {

    /** 当前要显示的那一份。为空即「该收起来了」 */
    internal var request by mutableStateOf<GlassMenuRequest?>(null)
        private set

    /**
     * 最后一份非空请求。
     *
     * 收起来的时候 [request] 先变 null，但退场动画还要有内容可画，
     * 所以这份要留着；等下一份请求来了再覆盖。
     */
    internal var retained by mutableStateOf<GlassMenuRequest?>(null)
        private set

    fun show(request: GlassMenuRequest) {
        this.request = request
        retained = request
    }

    /** 只有当前显示的仍是 [request] 时才清，避免新菜单被上一次的 onDispose 误伤 */
    fun hide(request: GlassMenuRequest) {
        if (this.request === request) this.request = null
    }
}

/** 菜单里的一行，已经是纯数据：宿主层不认识业务类型 */
@Immutable
class GlassMenuItem(
    val label: String,
    val description: String? = null,
    val icon: ImageVector? = null,
    val selected: Boolean = false,
)

/**
 * 一次下拉请求。
 *
 * @param anchor 触发行在 root 里的坐标
 * @param touch 手指落点在 root 里的坐标；菜单以它为中心展开
 */
class GlassMenuRequest(
    val title: String,
    val items: List<GlassMenuItem>,
    val anchor: Rect,
    val touch: Offset,
    val onPick: (Int) -> Unit,
    val onDismiss: () -> Unit,
)

val LocalGlassMenuHost = staticCompositionLocalOf<GlassMenuHostState?> { null }

/**
 * 下拉菜单宿主。放在 App 的根 Box 里、**采集层和底栏之后**——
 * 画在最上层，且不会被录进 backdrop。
 */
@Composable
fun GlassMenuHost(
    state: GlassMenuHostState,
) {
    val request = state.request
    // 退场期间 request 已经是 null，内容从 retained 里取
    val content = state.retained ?: return
    val visible = request != null
    val isDark = isGlassDark()

    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // 宽度跟着触发行走，夹在 200~320dp
    val widthDp = with(density) { content.anchor.width.toDp() }.coerceIn(200.dp, 320.dp)
    val widthPx = with(density) { widthDp.roundToPx() }
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }

    val origin = menuOrigin(content.anchor, content.touch, cardSize, windowSize, widthPx, gapPx, marginPx)
    val expandUp = cardSize.height > 0 && origin.y + cardSize.height <= content.anchor.top.roundToInt()

    // 缩放原点横向跟着手指，纵向按展开方向贴边：从下往上弹时从底边长出来
    val transformOrigin = run {
        val fx = if (content.touch != Offset.Unspecified && cardSize.width > 0) {
            ((content.touch.x - origin.x) / cardSize.width).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        TransformOrigin(fx, if (expandUp) 1f else 0f)
    }

    val motion = rememberOverlayMotion(fromScale = 0.90f, toScale = 0.94f)

    // 退场动画播完就得把整棵子树摘掉。
    //
    // 只靠 alpha=0 是不行的：那个铺满全屏的遮罩 Box 还在组合里，
    // 它的 pointerInput 会把页面上所有点击都吃掉——表现是「关掉菜单之后
    // 整个设置页点不动了」。收起那一帧看不出来，过一会儿才会发现点啥都没反应。
    var mounted by remember { mutableStateOf(visible) }

    // 只跟 visible 走，**不要**把 content 放进 key：换一份菜单（同一行为、内容不同）
    // 不该重新播一遍进场动画，否则菜单会当场缩回去再长出来。
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            motion.enter()
        } else if (mounted) {
            // 没显示过就没必要播一遍退场（冷启动第一帧 visible 本来就是 false）
            motion.exit()
            mounted = false
        }
    }

    // 返回键先收菜单。
    // 没有这一条时，菜单开着按返回会直接把整个设置页退掉，菜单跟着页面一起消失，
    // 用户会以为刚才那一下点错了。放在 mounted 判断之前，收起来的那段时间也有效。
    BackHandler(enabled = visible) { content.onDismiss() }

    if (!mounted) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 极淡的一层：下拉选择不是模态打断，压黑了反而像报错弹窗。
        // 但得有这一层——不然「点一下别处就收起」这件事没有视觉契约
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 直接画带 alpha 的矩形，不给全屏开离屏图层
                .scrimFade(motion, baseAlpha = 0.06f)
                .pointerInput(content) {
                    detectTapGestures(onTap = { content.onDismiss() })
                }
        )

        Column(
            modifier = Modifier
                .offset { origin }
                .width(widthDp)
                .onSizeChanged { cardSize = it }
                .overlayTransform(motion, transformOrigin)
                // 自绘玻璃，不采背景。
                //
                // 试过 `softGlass`（真模糊），但 `drawBackdrop` 在这种浮层上采样是错位的：
                // 菜单右侧会凭空出现一团蓝光——那是页面里的蓝色图标被采到、放大、提饱和的结果。
                // 排除了 offset / 绘制层平移 / 动画层 / shader 实例共用四种可能，都还在，
                // 是库在这类浮层上的坐标映射问题。
                //
                // 菜单身下就是纯色页面，模糊与否差别本就很小。这里用确定的自绘材质：
                // 体色 + 顶部高光 + 边缘光 + 柔光阴影，观感和真模糊差不太多。
                .glassPanel(
                    shape = GlassShape(22.dp),
                    isDark = isDark,
                    shadow = GlassShadows.Regular,
                    shaderKey = "menu",
                    // 比对话框实一档：菜单底下直接压着文字，透太多会糊成一片
                    bodyAlpha = 0.96f,
                ),
        ) {
            Text(
                text = content.title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
            )

            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                content.items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .pressScale(pressedScale = 0.98f)
                            .clip(RoundedCornerShape(Radius.Control))
                            .background(
                                if (item.selected) {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { content.onPick(index) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.icon?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = "",
                                modifier = Modifier.size(22.dp),
                                tint = if (item.selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                            Spacer(Modifier.width(Spacing.IconGap))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.label,
                                style = MiuixTheme.textStyles.paragraph,
                                color = if (item.selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                            item.description?.let { desc ->
                                Spacer(Modifier.height(Spacing.XS))
                                Text(
                                    text = desc,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        if (item.selected) {
                            Spacer(Modifier.width(Spacing.L))
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已选中",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 面板左上角的窗口坐标。
 *
 * 横向以 [touch] 为中心：点哪个位置，菜单就在哪个位置张开——这是「同步」的关键。
 * 按行左边缘对齐的话，点行尾的箭头会看到菜单从很远的左边冒出来。
 */
private fun menuOrigin(
    anchor: Rect,
    touch: Offset,
    card: IntSize,
    window: IntSize,
    widthPx: Int,
    gapPx: Int,
    marginPx: Int,
): IntOffset {
    val pivotX = if (touch != Offset.Unspecified) touch.x else anchor.center.x
    val maxX = (window.width - widthPx - marginPx).coerceAtLeast(marginPx)
    val x = (pivotX - widthPx / 2f).roundToInt().coerceIn(marginPx, maxX)

    val below = anchor.bottom.roundToInt() + gapPx
    val above = anchor.top.roundToInt() - gapPx - card.height
    val y = when {
        // 高度还没量出来：先按「在下面」摆，量完这一帧会纠正
        card.height <= 0 -> below
        // 下面放得下：顺着阅读方向往下长
        below + card.height <= window.height - marginPx -> below
        // 下面放不下、上面放得下：翻上去
        above >= marginPx -> above
        // 两边都放不下：贴着底边，至少整块可见
        else -> (window.height - card.height - marginPx).coerceAtLeast(marginPx)
    }
    return IntOffset(x, y)
}
