package com.yfantasyxx.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfantasyxx.ui.theme.Motion
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
    // 原来是 `if (!visible) return`：红点「啪」地出现、「啪」地消失。
    // 底栏图标本来就在做填充/描边切换，再突然多一个 7dp 的点很扎眼；
    // 改成 150ms 淡入 + 从 0.4 弹回原大小，出现得柔和，消失得更快。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.standard(Motion.DURATION_FAST)) +
            scaleIn(animationSpec = Motion.snappy(), initialScale = 0.4f),
        exit = fadeOut(Motion.accelerate(Motion.DURATION_INSTANT)) +
            scaleOut(
                animationSpec = Motion.accelerate(Motion.DURATION_INSTANT),
                targetScale = 0.4f,
            ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
