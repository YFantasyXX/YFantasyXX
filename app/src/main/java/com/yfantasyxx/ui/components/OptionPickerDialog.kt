package com.yfantasyxx.ui.components

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yfantasyxx.ui.glass.DialogWindowGlass
import com.yfantasyxx.ui.glass.GlassMenuHostState
import com.yfantasyxx.ui.glass.GlassMenuItem
import com.yfantasyxx.ui.glass.GlassMenuRequest
import com.yfantasyxx.ui.glass.LocalGlassMenuHost
import com.yfantasyxx.ui.glass.glassPanel
import com.yfantasyxx.ui.glass.isGlassDark
import com.yfantasyxx.ui.glass.material.GlassShape
import com.yfantasyxx.ui.theme.Radius
import com.yfantasyxx.ui.theme.Spacing
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选择面板里的一项 */
data class PickerOption<T>(
    val value: T,
    val label: String,
    val description: String? = null,
    val icon: ImageVector? = null,
)

/**
 * 单选面板。
 *
 * ## 两种弹出方式
 *
 * - **给了 [anchor]**（触发行在 root 里的坐标）：像下拉菜单一样**贴着那一行弹出来**，
 *   并且从 [touch]（手指落点）张开。设置页里点「深色模式」，菜单就在这行正下方、
 *   从手指的位置长出来；下面放不下会自动翻到行上方。
 * - **不给 anchor**：居中卡片。给那些没有明确触发点的场景留的后路。
 *
 * ## 贴行版为什么交给 App 层渲染
 *
 * 菜单要的是**真模糊**（背后内容被虚化，而不是让底下的文字直接透上来）。
 * 项目里的模糊来自 `layerBackdrop` 离屏采集，而玻璃必须在采集层**外面**，
 * 否则会把自己录进自己正在采样的那张图，HWUI 直接爆栈。
 *
 * 设置页在采集层内部，所以贴行版把「要显示什么」打包成 [GlassMenuRequest] 交给
 * [com.yfantasyxx.ui.glass.GlassMenuHost]——它在 App 根部、采集层之外，
 * 既拿得到背景，也和触发行处在同一个 ComposeView 里。后者是位置准不准的关键：
 * 上一版用 `Dialog`，弹层是独立窗口，它自己的 (0,0) 未必等于 Activity 窗口的 (0,0)
 * （取决于 ROM 给不给对话框加 `FLAG_LAYOUT_IN_SCREEN`），表现就是
 * 「点的位置和弹出的位置不同步」。
 *
 * 居中版留在本文件用 `Dialog`：它需要压暗整页，是真正的模态弹窗。
 */
@Composable
fun <T> OptionPickerDialog(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    /** 触发器在 root 里的坐标。给了就贴着它弹，不给就居中 */
    anchor: Rect? = null,
    /** 手指落点（root 坐标）。菜单从这里展开，也是水平居中的基准 */
    touch: Offset = Offset.Unspecified,
) {
    val host = LocalGlassMenuHost.current
    if (anchor != null && host != null) {
        HostedPickerMenu(
            host = host,
            title = title,
            options = options,
            selected = selected,
            onSelect = onSelect,
            onDismiss = onDismiss,
            anchor = anchor,
            touch = touch,
        )
    } else {
        CenteredPickerDialog(
            title = title,
            options = options,
            selected = selected,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    }
}

/* ───────────────────────── 贴行版：注册给 App 层的宿主 ───────────────────────── */

@Composable
private fun <T> HostedPickerMenu(
    host: GlassMenuHostState,
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    anchor: Rect,
    touch: Offset,
) {
    // 用最新回调，但请求对象本身要稳定：每次重组都新建一份的话，
    // DisposableEffect 会不停 show → 宿主不停重组
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val request = remember(title, options, selected, anchor, touch) {
        GlassMenuRequest(
            title = title,
            items = options.map {
                GlassMenuItem(
                    label = it.label,
                    description = it.description,
                    icon = it.icon,
                    selected = it.value == selected,
                )
            },
            anchor = anchor,
            touch = touch,
            onPick = { index ->
                currentOnSelect(options[index].value)
                currentOnDismiss()
            },
            onDismiss = { currentOnDismiss() },
        )
    }

    DisposableEffect(request) {
        host.show(request)
        onDispose { host.hide(request) }
    }
}

/* ───────────────────────────── 居中版（Dialog） ───────────────────────────── */

@Composable
private fun <T> CenteredPickerDialog(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isDark = isGlassDark()

    // 和下拉菜单共用同一套进出场（起点略小一点：居中弹层没有触发行可以「贴着长」）
    val motion = rememberOverlayMotion(fromScale = 0.92f, toScale = 0.96f)
    LaunchedEffect(Unit) { motion.enter() }

    // 退场：先把动画播完，再真正关掉。直接调 onDismiss 的话弹层会当场消失
    val dismissing = remember { mutableStateOf(false) }
    val dismissCallback = rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = remember {
        {
            if (!dismissing.value) {
                dismissing.value = true
                scope.launch {
                    // 等两个动画都播完。只等缩放的话淡出会被截断，
                    // 看起来是「咔」地消失（下拉菜单第一版就是这个毛病）
                    motion.exit()
                    dismissCallback.value()
                }
            }
        }
    }

    Dialog(
        // 返回键也走同一条退场动画，不能让平台直接把窗口拆掉
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 和 AppDialog 同一套：窗口模糊 + 玻璃面板，弹层之间不能长得不一样
        DialogWindowGlass(dim = 0.18f)

        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩：点空白处关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scrimFade(motion, baseAlpha = 0.34f)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { dismiss() })
                    }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    // 外边距放在最外层：这样卡片本身的宽度是 min(380dp, 屏宽-40dp)，
                    // 而不是先按屏宽算完再把卡片缩掉 40dp
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .overlayTransform(motion)
                    .glassPanel(shape = GlassShape(28.dp), isDark = isDark),
            ) {
                PickerRows(
                    title = title,
                    options = options,
                    selected = selected,
                    compact = false,
                    onPick = { value ->
                        onSelect(value)
                        dismiss()
                    },
                )
            }
        }
    }
}

/* ───────────────────────────── 居中版的内容 ───────────────────────────── */

/**
 * 标题 + 选项列表。
 *
 * 贴行版有自己的一份（在 [com.yfantasyxx.ui.glass.GlassMenuHost] 里，
 * 因为那个文件在 `ui.glass` 包、要独立于 components 编译），两份的排版参数
 * 保持一致：标题缩进 18dp、选项缩进 10dp、行内 12/11dp。
 */
@Composable
private fun <T> PickerRows(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    compact: Boolean,
    onPick: (T) -> Unit,
) {
    Text(
        text = title,
        // 必须显式给颜色：Compose 原生 Dialog 里的默认 content color 是黑色，
        // 暗色主题下黑字落在深色面板上等于隐形（标题会整条消失）
        style = if (compact) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.title4,
        color = if (compact) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(
            start = 18.dp,
            end = 18.dp,
            top = if (compact) 14.dp else 20.dp,
            bottom = if (compact) 8.dp else 10.dp,
        ),
    )

    Column(modifier = Modifier.padding(horizontal = 10.dp)) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    // 按下反馈放在最外层：整行（含底色）一起缩，
                    // 而不是只有文字缩、底色留在原地
                    .pressScale(pressedScale = 0.98f)
                    .clip(RoundedCornerShape(Radius.Control))
                    .background(
                        if (isSelected) {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .clickable { onPick(option.value) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                option.icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = "",
                        modifier = Modifier.size(22.dp),
                        tint = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    Spacer(Modifier.width(Spacing.IconGap))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = MiuixTheme.textStyles.paragraph,
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                    option.description?.let { desc ->
                        Spacer(Modifier.height(Spacing.XS))
                        Text(
                            text = desc,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (isSelected) {
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

    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
}
