package com.yfantasyxx.ui.components

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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 页面分组标题 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
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
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // 记录是否真正获得过焦点：刚显示时 onFocusChanged 会先报一次 false
    var hasFocused by remember { mutableStateOf(false) }

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
        val result = if (valid) parsed!! else value
        if (valid) onValueChange(result)
        exitEditing()
        return result
    }

    // 键盘弹出（IME insets 变大）后再滚动，确保不被挡住
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottom, editing) {
        if (editing && imeBottom > 0) {
            kotlinx.coroutines.delay(120)
            runCatching { bringIntoView.bringIntoView() }
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StepButton(
            icon = Icons.Filled.Remove,
            description = "减少",
            onClick = {
                val base = if (editing) commit() else value
                val next = base - step
                onValueChange(if (next < 0f && !allowNegative) 0f else next)
            },
        )

        // 数字区：点击进入输入态
        Box(
            modifier = Modifier
                .width(58.dp)
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
                        fontSize = 15.sp,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = 5.dp),
                )
            }
        }

        StepButton(
            icon = Icons.Filled.Add,
            description = "增加",
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
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 分段选择器：圆角胶囊容器 + 选中态浮起卡片。
 */
@Composable
fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (selected) {
                            Modifier.background(MiuixTheme.colorScheme.surface)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                )
            }
        }
    }
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
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.primary,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 格式化容量显示，如 12.5k */
fun formatVolume(kg: Float): String = when {
    kg >= 100000 -> String.format("%.1fw", kg / 10000)
    kg >= 10000 -> String.format("%.1fw", kg / 1000)
    kg >= 1000 -> String.format("%.1fk", kg / 1000)
    else -> kg.toInt().toString()
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
