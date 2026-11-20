package com.yfantasyxx.ui.glass

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.yfantasyxx.ui.glass.material.EdgeStroke
import com.yfantasyxx.ui.glass.material.GlassDefaults
import com.yfantasyxx.ui.glass.material.GlassEdgeStrokes
import com.yfantasyxx.ui.glass.material.GlassShadow
import com.yfantasyxx.ui.glass.material.GlassShadows
import com.yfantasyxx.ui.glass.material.GlassShape
import com.yfantasyxx.ui.glass.material.drawGlassEdge
import com.yfantasyxx.ui.glass.material.drawGlassRim
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹层玻璃：栏和卡片之外的第三种用法。
 *
 * ## 为什么不能直接复用 [softGlass]
 *
 * 弹层跑在自己的 `Window` 里（Compose 的 `Dialog`），拿不到页面那套 `layerBackdrop`——
 * 采集层和被采集的内容不在同一个窗口，采出来是一张空图。所以这里换了个思路：
 *
 * - **模糊交给系统**：`Window.setBackgroundBlurRadius`（Android 12+）会在窗口下面
 *   把后面的内容虚化，成本全在 SurfaceFlinger，和页面渲染无关；
 * - **材质自己画**：体色 + 顶部高光 + 边缘一圈亮线 + 投影，就是 OS4 弹层那层
 *   「有厚度的亚克力」。
 *
 * 系统模糊在不少 ROM 上是被关掉的（`FLAG_BLUR_BEHIND` 不生效）。所以体色本身要做到
 * 0.9 以上：退化成一块近乎实心的卡片，也**不能**把背后的文字透上来——
 * 弹层里两行文字和背后的页面文字叠在一起，比失去玻璃感糟糕得多。
 */
fun Modifier.glassPanel(
    shape: GlassShape,
    isDark: Boolean,
    alpha: Float = 1f,
    stroke: EdgeStroke? = GlassEdgeStrokes.forTheme(isDark),
    shadow: GlassShadow? = GlassShadows.ExtraHigh,
    shaderKey: String = "panel",
    /**
     * 体色不透明度。为空时按主题取默认（深 0.90 / 浅 0.92）。
     *
     * 下拉菜单要压得住背景里的文字，否则行与行会糊成一片；对话框是模态的、
     * 身下有遮罩，可以透一点。
     */
    bodyAlpha: Float? = null,
): Modifier = this
    // 投影贴在裁剪前面：先画外面那圈，再裁出圆角画本体
    .softGlassShadow(shape, shadow, alpha, shaderKey)
    .clip(shape)
    .drawBehind { drawPanel(shape, isDark, alpha, stroke, shaderKey, bodyAlpha) }

private fun DrawScope.drawPanel(
    shape: GlassShape,
    isDark: Boolean,
    alpha: Float,
    stroke: EdgeStroke?,
    shaderKey: String,
    bodyAlpha: Float?,
) {
    val path = shape.toPath(size, density)

    // 体色。深色主题下比卡片（#242424）略亮一点：弹层是「浮起来」的，
    // 比身后的卡片还暗就成了挖洞。浅色主题下白得发灰，纯白会和页面糊成一片。
    //
    // 不透明度不能再低了。0.78 时背后的东西会透上来——有氧结束弹窗就是
    // 「面板文字 + 背后秒表大字」叠在一起（用户截图原话「被挡住了」）。
    // 系统窗口模糊在不少 ROM 上是关的，不能指望它把背后糊掉，所以体色自己
    // 得做到 0.9 以上：这个厚度下只剩一点色温，读文字不受干扰。
    val body = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF4F4F4)
    val opacity = bodyAlpha ?: if (isDark) 0.90f else 0.92f
    drawPath(path = path, color = body, alpha = opacity * alpha)

    // 顶部高光：光从上方来，上沿比下沿亮，玻璃才有厚度。
    // 浅色主题下这层几乎看不见（白叠白），主要靠下面那条暗边勾轮廓。
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = (if (isDark) 0.10f else 0.60f) * alpha),
            0.45f to Color.Transparent,
            1f to Color.Black.copy(alpha = (if (isDark) 0.12f else 0.03f) * alpha),
        ),
    )

    // 边缘：官方那套 shader 描边 + rim 高光合**一次**画完。
    // 两段都是 Plus 加法光，先描边后 rim 与一次算完等价；分开画等于每个弹层
    // 每帧多跑一趟全尺寸 AGSL（底栏 + 菜单 + 对话框同屏时就是三趟白烧）。
    val glassStyle = GlassDefaults.style(isDark)
    if (stroke != null) {
        drawGlassEdge(shape, stroke, glassStyle, body, alpha, shaderKey)
    } else {
        drawGlassRim(shape, glassStyle, body, alpha, shaderKey)
    }

    // 浅色主题里白色 rim 在浅底上等于没画，补一圈极淡的暗边，不然弹层边界靠猜。
    // 放在最后：这条线存在的意义就是「不管亮光怎么叠，轮廓都得看得见」。
    if (!isDark) {
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.085f * alpha),
            style = Stroke(width = 0.9.dp.toPx()),
        )
    }
}

/**
 * 给当前这个 `Dialog` 的窗口开背景模糊。
 *
 * 放在 Dialog 内容的**最外层**调用一次即可。`DialogWindowProvider` 就是 Compose
 * 给 Dialog 内层塞的那个 `DialogLayout`——`LocalView.current.parent` 拿到的就是它，
 * 不是 `Dialog` 对象本身（往上一层才是 DecorView）。
 *
 * @param radius 模糊半径，dp。官方弹层用 60dp，量级对得上就够了。
 * @param dim 遮罩浓度。系统默认 0.32；这里取 0.28——比默认略轻，但比早先的
 *   0.18 重得多。0.18 时背后几乎没压暗，配合面板透光就是「两张纸叠在一起」。
 *   模糊不生效的 ROM 上，这层 dim 是唯一能压住背景的东西，不能太客气。
 */
@Composable
fun DialogWindowGlass(radius: Float = 56f, dim: Float = 0.28f) {
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { radius.dp.roundToPx() }

    DisposableEffect(view) {
        val window = runCatching {
            (view.parent as? DialogWindowProvider)?.window
        }.getOrNull()

        // 必须等窗口 attach 完再设置：窗口还没 addView 时 setBackgroundBlurRadius
        // 会被记在 params 里但 SurfaceFlinger 那边不会重建 layer
        view.post {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.setBackgroundBlurRadius(radiusPx)
                    window.setDimAmount(dim)
                }
            }
        }
        onDispose { }
    }
}

/** 当前主题是不是深色：和底栏、玻璃件用的同一个判据（表面色的亮度）。 */
@Composable
fun isGlassDark(): Boolean = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
