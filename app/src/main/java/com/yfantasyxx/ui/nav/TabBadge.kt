package com.yfantasyxx.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏 tab 上的小红点（目前只有「我的」用：发现有新版本）。
 *
 * 两种底栏共用一个：尺寸、颜色、位置必须一致，否则切换底栏样式时
 * 红点会跳一下。
 */
@Composable
fun TabBadgeDot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.error,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}
