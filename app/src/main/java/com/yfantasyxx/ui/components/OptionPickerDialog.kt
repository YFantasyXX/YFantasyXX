package com.yfantasyxx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yfantasyxx.ui.theme.Motion
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
 * 居中单选卡片。
 *
 * ## 为什么不再从底部滑出
 *
 * 上一版是 iOS 那种底部抽屉：面板 `translationY` 从屏幕下方 320dp 处滑上来。
 * 问题是这类选项只有 2~3 个、每项一句话，走一趟「屏幕底 → 屏幕中 → 再回原处」
 * 的路径比选项本身的内容还长；而且这个应用里其它所有弹窗（模板编辑、备份列表、
 * 更新提示……）都是居中的，只有设置里的选择器从底下冒出来，两套视觉语言混在
 * 一个页面里，点开的一瞬间最跳的就是它。
 *
 * 改成居中卡片之后：
 *
 * - **不再有任何垂直位移**。卡片只从 0.88 缩到 1.0（弹簧，带一点点过冲），
 *   遮罩同步淡入。缩放是「聚焦」，位移是「搬运」——选择这种不改变上下文的
 *   操作，用聚焦更合适；
 * - **退场也有动画**。旧版只做了进场，关闭时窗口直接消失，弹窗是「啪」地没了。
 *   现在退场反向播完再关窗，节奏才对得上；
 * - 选中项用主色浅底 + 主色文字 + 勾，一眼能看出当前值；
 * - 每一项按下都有缩放反馈（[pressScale]），和全站其它可点区域一致。
 *
 * ## 尺寸
 *
 * 卡片最大 380dp、左右各留 20dp 外边距：手机上约占屏宽的九成，平板上不会
 * 拉成一条横幅。高度跟随内容，选项多也只长到需要的高度。
 */
@Composable
fun <T> OptionPickerDialog(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 进场：缩放走弹簧（能被中途打断、能续上速度），透明度走 tween（没有速度可言）
    val scaleAnim = remember { Animatable(0.88f) }
    val alphaAnim = remember { Animatable(0f) }

    // 退场：先把动画播完，再真正关掉 Dialog。
    // 直接调 onDismiss 的话窗口会当场消失，动画等于没播。
    val dismissing = remember { mutableStateOf(false) }
    val dismissCallback = rememberUpdatedState(onDismiss)

    val dismiss: () -> Unit = remember {
        {
            if (!dismissing.value) {
                dismissing.value = true
                scope.launch {
                    launch { alphaAnim.animateTo(0f, Motion.accelerate(Motion.DURATION_FAST)) }
                    scaleAnim.animateTo(0.92f, Motion.accelerate(Motion.DURATION_FAST))
                    dismissCallback.value()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        launch { alphaAnim.animateTo(1f, Motion.standard(Motion.DURATION_FAST)) }
        scaleAnim.animateTo(1f, Motion.smooth())
    }

    Dialog(
        // 返回键也走同一条退场动画，不能让平台直接把窗口拆掉
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩：点空白处关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = alphaAnim.value }
                    .background(Color.Black.copy(alpha = 0.34f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { dismiss() })
                    }
            )

            val shape = RoundedCornerShape(28.dp)

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    // 外边距放在最外层：这样卡片本身的宽度是 min(380dp, 屏宽-40dp)，
                    // 而不是先按屏宽算完再把卡片缩掉 40dp
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = alphaAnim.value
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                    }
                    .shadow(
                        elevation = 18.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.30f),
                    )
                    .clip(shape)
                    .background(MiuixTheme.colorScheme.surface)
                    // 暗色主题下卡片和遮罩后的页面颜色接近，补一道极淡的描边把边界勾出来
                    .border(
                        width = 0.6.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f),
                        shape = shape,
                    ),
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    // 必须显式给颜色：Compose 原生 Dialog 里的默认 content color 是黑色，
                    // 暗色主题下黑字落在深色卡片上等于隐形（标题会整条消失）
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 20.dp,
                        bottom = 10.dp,
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
                                .clickable {
                                    onSelect(option.value)
                                    dismiss()
                                }
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

                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
