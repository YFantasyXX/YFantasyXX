package com.yfantasyxx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Dp
import com.yfantasyxx.ui.theme.Motion
import com.yfantasyxx.ui.theme.Radius
import com.yfantasyxx.ui.theme.Spacing
import top.yukonga.miuix.kmp.basic.Surface
import java.util.Locale

/**
 * 卡片内部的「凹槽」底色：输入框、迷你胶囊、进度条轨道。
 *
 * 不能直接用 surfaceContainer —— Card 本身就是 surfaceContainer，画上去等于没画
 * （模板编辑里「休息」「设为热身」曾经看起来像两段普通文字）；也不能用 surface，
 * 那是页面色，深色下比卡片还黑，会像挖了个洞。用 onSurface 的极低透明度叠一层，
 * 深浅两套主题下都能稳定看出边界，且不需要再记一套色值。
 */
@Composable
fun cardInsetColor(alpha: Float = 0.07f): Color =
    MiuixTheme.colorScheme.onSurface.copy(alpha = alpha)

/** 页面分组标题 */
@Composable
fun SectionHeader(    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 左边距必须是 16（= Spacing.Gutter），和卡片对齐。
            // 原来这里写 20，分组标题比下面的卡片多缩进 4dp，两行文字左边缘
            // 差一点点，看起来就像没对齐——这种「差 4dp」比差 20dp 更刺眼。
            .padding(horizontal = Spacing.Gutter, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        trailing?.invoke()
    }
}

/** 空状态 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = Icons.Outlined.Inbox,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "",
                    modifier = Modifier.size(23.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/**
 * 数字步进器：胶囊形外壳 + 圆形加减按钮，中间数字可直接点击输入。
 */
@Composable
fun NumberStepper(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 2.5f,
    unit: String = "",
    allowNegative: Boolean = false,
    /**
     * 行身份。列表回收行槽位时（组数增减、动作卡换序），
     * 输入态必须跟着「哪一组」走，而不是跟着屏幕上的格子走。
     *
     * 不给的话 remember 不按任何东西重置：被复用到的那一行会直接
     * 以编辑态出现、带着上一行的数字，挂载后还会抢焦点把键盘弹起来
     * —— 表现就是「勾完一组，页面自己滚到别处」。
     */
    rememberKey: Any? = null,
    /**
     * 小一号。递减段那种从属行用：24dp 圆钮 + 更窄的数字位 + 12.5sp。
     * 主行的加减号维持 30dp —— 训练时按得最多，缩小了不好按。
     */
    compact: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var editing by remember(rememberKey) { mutableStateOf(false) }
    var text by remember(rememberKey) { mutableStateOf("") }
    // 记录是否真正获得过焦点：刚显示时 onFocusChanged 会先报一次 false
    var hasFocused by remember(rememberKey) { mutableStateOf(false) }

    val displayText = if (value == value.toInt().toFloat()) {
        value.toInt().toString() + unit
    } else {
        String.format("%.1f%s", value, unit)
    }

    fun exitEditing() {
        editing = false
        hasFocused = false
        text = ""
        focusManager.clearFocus()
    }

    /** 提交输入，返回可继续加减的基准值 */
    fun commit(): Float {
        val parsed = text.toFloatOrNull()
        val valid = parsed != null && (allowNegative || parsed >= 0f)
        val result = parsed ?: value
        if (valid) onValueChange(result)
        exitEditing()
        return result
    }

    // 键盘弹出后把输入框滚进可视区。
    //
    // key 只用 editing，绝对不能带上 ime.bottom：键盘动画期间 insets
    // 每一帧都在变，写进 key 等于每帧重启协程 —— 每次重启都会重新发起
    // bringIntoView，列表被一路往上拽，用户看到的就是「页面位置移位」。
    // 现在只在「刚进入输入态」时等键盘动画走完滚一次，之后不再动。
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        kotlinx.coroutines.delay(260)
        runCatching { bringIntoView.bringIntoView() }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Control))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = if (compact) 2.dp else 3.dp, vertical = if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StepButton(
            icon = Icons.Filled.Remove,
            description = "减少",
            buttonSize = if (compact) 24.dp else 30.dp,
            iconSize = if (compact) 12.dp else 15.dp,
            onClick = {
                val base = if (editing) commit() else value
                val next = base - step
                onValueChange(if (next < 0f && !allowNegative) 0f else next)
            },
        )

        // 数字区：点击进入输入态
        Box(
            modifier = Modifier
                .width(if (compact) 46.dp else 58.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (!editing) {
                        Modifier.clickable {
                            text = if (value == value.toInt().toFloat()) {
                                value.toInt().toString()
                            } else {
                                String.format("%.1f", value)
                            }
                            hasFocused = false
                            editing = true
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (editing) {
                BasicTextField(
                    value = text,
                    onValueChange = { input ->
                        if (input.length <= 6 && input.all { it.isDigit() || it == '.' }) {
                            text = input
                        }
                    },
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = if (compact) 12.5.sp else 15.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .bringIntoViewRequester(bringIntoView)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                hasFocused = true
                            } else if (hasFocused) {
                                // 真正的失焦（点到别处）才提交
                                commit()
                            }
                        },
                )
                LaunchedEffect(Unit) {
                    // 输入框挂载后请求焦点
                    kotlinx.coroutines.delay(60)
                    runCatching { focusRequester.requestFocus() }
                }
            } else {
                Text(
                    text = displayText,
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = if (compact) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = if (compact) 3.dp else 5.dp),
                )
            }
        }

        StepButton(
            icon = Icons.Filled.Add,
            description = "增加",
            buttonSize = if (compact) 24.dp else 30.dp,
            iconSize = if (compact) 12.dp else 15.dp,
            onClick = {
                val base = if (editing) commit() else value
                onValueChange(base + step)
            },
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    buttonSize: androidx.compose.ui.unit.Dp = 30.dp,
    iconSize: androidx.compose.ui.unit.Dp = 15.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surface)
            // 按下时缩得更明显一点：加减号是训练时按得最多的按钮，
            // 30dp 的圆上水波纹几乎看不清，得靠尺寸变化
            .pressScale(pressedScale = 0.82f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(iconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 胶囊选择标签
 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 选中/取消原来是瞬间换色，一排标签连着点几个就像在闪。
    // 底色与文字各走一段过渡；按下再缩到 0.96 弹回，给一个「确实按到了」
    // 的实感——胶囊很小，只靠颜色变化反馈不够。
    val background by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
        animationSpec = Motion.snappy(),
        label = "chipBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = Motion.snappy(),
        label = "chipContent",
    )

    // 用 Box 显式裁剪成胶囊，避免依赖组件默认 shape
    Box(
        modifier = Modifier
            .pressScale(pressedScale = 0.94f)
            .clip(RoundedCornerShape(percent = Radius.Pill))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) {
                androidx.compose.ui.text.font.FontWeight.SemiBold
            } else {
                androidx.compose.ui.text.font.FontWeight.Normal
            },
            color = contentColor,
            maxLines = 1,
        )
    }
}

/* ───────────────────────── 设置页通用组件 ───────────────────────── */

/** 设置分组卡片：卡片本身不带内边距，由行自己控制，行与行之间用 [SettingsDivider] 隔开 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter)) {
        Column(modifier = Modifier.padding(vertical = 2.dp), content = content)
    }
}

/** 行内分隔线，默认从图标右侧开始，视觉上像一条被截断的细线 */
@Composable
fun SettingsDivider(startIndent: Dp = 58.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.dividerLine)
    )
}

/** 设置行左侧的圆角图标徽章 */
@Composable
fun SettingsIconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(Radius.Badge))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "",
            modifier = Modifier.size(17.dp),
            tint = tint,
        )
    }
}

/**
 * 设置行。
 *
 * 左侧图标 + 标题（可带副标题），右侧依次是当前值、自定义尾部（开关/步进器）、箭头。
 * 传入 [onClick] 时整行可点，用于打开选择面板或跳转。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 按下时整行轻微缩小。设置页是全应用点击最频繁的地方，
            // 原来只有水波纹，行高又大（水波纹几乎看不见），反馈非常弱。
            .then(
                if (onClick != null) {
                    Modifier
                        .pressScale(pressedScale = 0.985f)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = Spacing.Gutter, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIconBadge(icon = icon, tint = iconTint)
            Spacer(Modifier.width(Spacing.IconGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.main)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MiuixTheme.textStyles.paragraph,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "",
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
            )
        }
    }
}

/** 卡片内的说明文字 */
@Composable
fun SettingsHint(
    text: String,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 12.dp,
    startIndent: Dp = Spacing.Gutter,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = startIndent, end = 16.dp, top = top, bottom = bottom),
    )
}

/**
 * 整数输入框（重量/次数/RPE）
 */
@Composable
fun NumberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    TextField(
        value = value,
        onValueChange = { input ->
            // 仅允许数字和小数点
            if (input.all { it.isDigit() || it == '.' }) onValueChange(input)
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** 键值信息行 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        // 值靠右对齐，长文本（如链接）换行后仍贴右边
        Text(
            text = value,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** 统计小卡片 */
@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            // 窄屏（320dp）或大字体下不换行、不溢出：四张卡按权重平分宽度，
            // 文字超出就省略，整行高度不会跳
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            // 等宽数字（tnum）：不然 9→10 这种位数变化会让整张卡重新排版，
            // 一行四张卡时其余三张跟着左右挪，数字跳一下很显眼
            style = MiuixTheme.textStyles.title3.copy(fontFeatureSettings = "tnum"),
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 格式化容量显示，如 12.5k */
fun formatVolume(kg: Float): String = when {
    kg >= 100000 -> String.format(Locale.CHINA, "%.1fw", kg / 10000)
    // 一万到十万也是「万」，除的必须是 10000：以前这里写的 1000，
    // 50000 kg 会显示成 50.0w（等于说成 50 万），跨过 10 万又突然变小
    kg >= 10000 -> String.format(Locale.CHINA, "%.1fw", kg / 10000)
    kg >= 1000 -> String.format(Locale.CHINA, "%.1fk", kg / 1000)
    else -> kg.toInt().toString()
}

/**
 * 训练时长。
 *
 * 超过一小时写成「1 小时 12 分」而不是「72 分钟」——后者在列表里读起来
 * 还要自己换算一次。不足一分钟单独给一句话，避免出现「0 分钟」这种像没练过的结果。
 */
fun formatSessionDuration(millis: Long): String {
    val minutes = (millis / 60_000L).coerceAtLeast(0L)
    if (minutes < 1L) return "不到 1 分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return when {
        hours == 0L -> "$rest 分钟"
        rest == 0L -> "$hours 小时"
        else -> "$hours 小时 $rest 分"
    }
}

/**
 * 组内用时：两次休息之间的间隔（见 util/SetClock）。
 *
 * 不过 1 分钟直接给秒 —— 「45s」比「0 分钟」有意义；超过 1 小时退成 h:mm。
 * 0 表示没记录（还没做的组、v15 及更早的老记录），返回空串，
 * 由调用方决定是留白还是整格不画：不能显示成「0s」。
 */
fun formatSetDuration(millis: Long): String {
    if (millis <= 0L) return ""
    val s = (millis / 1000L).coerceAtLeast(1L)
    return when {
        s < 60L -> "${s}s"
        s < 3600L -> "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        else -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}"
    }
}

/**
 * 文字按钮（MIUIX 的 TextButton 只接受 text 参数，这里提供带点击态的通用版本）
 */
@Composable
fun ActionText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.button,
        color = if (enabled) color else MiuixTheme.colorScheme.disabledOnSurface,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
