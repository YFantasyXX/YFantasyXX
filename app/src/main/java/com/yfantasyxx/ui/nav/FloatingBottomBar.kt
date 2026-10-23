package com.yfantasyxx.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 悬浮底栏的 tab 定义 */
data class FloatingTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

/**
 * 悬浮底栏：浮在内容之上、圆角胶囊 + 细描边 + 阴影。
 *
 * [glass] 开启时用 miuix-blur 采样并模糊下方内容（真毛玻璃效果）；
 * 关闭时使用不透明背景（默认），避免高刷屏上额外的 GPU 模糊开销。
 */
@Composable
fun FloatingBottomBar(
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    backdrop: Backdrop,
    glass: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val surface = MiuixTheme.colorScheme.surface
    val blurRadiusPx = with(LocalDensity.current) { 16.dp.toPx() }

    Surface(
        shape = shape,
        color = if (glass) Color.Transparent else surface,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
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
                                blur(blurRadiusPx)
                            },
                            onDrawSurface = {
                                drawRect(color = surface.copy(alpha = 0.42f))
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
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = tab.route == selectedRoute
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(MiuixTheme.colorScheme.primary)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { if (!selected) onSelect(tab.route) }
                            .padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                        if (selected) {
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
