package com.yfantasyxx.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.isRuntimeShaderSupported
import com.yfantasyxx.ui.glass.material.EdgeStroke
import com.yfantasyxx.ui.glass.material.GlassColorBlendMode
import com.yfantasyxx.ui.glass.material.GlassDefaults
import com.yfantasyxx.ui.glass.material.GlassMaterial
import com.yfantasyxx.ui.glass.material.GlassShadow
import com.yfantasyxx.ui.glass.material.GlassShadows
import com.yfantasyxx.ui.glass.material.GlassShape
import com.yfantasyxx.ui.glass.material.GlassStyle
import com.yfantasyxx.ui.glass.material.drawGlassEdge
import com.yfantasyxx.ui.glass.material.drawGlassRim
import com.yfantasyxx.ui.glass.material.drawGlassShadow
import com.yfantasyxx.ui.nav.liquid.liquidRefraction

/**
 * HyperOS 4 的柔光玻璃。
 *
 * 和底栏原来那套「液态玻璃」是两条路线：
 * - 液态玻璃（[com.yfantasyxx.ui.nav.LiquidGlassBottomBar]）追求**折射**：边缘把背景掰弯、
 *   色散、按压时折射增强。贵，而且只敢用在底栏一块。
 * - 柔光玻璃**不做折射**，只做「模糊 + 环境色 + 边缘光」。光学上更接近磨砂亚克力，
 *   成本低一档，所以官方才敢把它铺到导航栏、顶栏、弹层、对话框上。
 *
 * 参数全部来自 [GlassStyle] / [GlassMaterial]（收编自 miuix-material），
 * 绘制原语也是它的：边缘描边、rim 高光、阴影都是原样调用，只有「背景从哪来」
 * 换成了项目已有的 kyant backdrop —— 不引入第二套离屏采集层。
 *
 * 折射默认关闭（对应官方的 `shading = false`），但留了一个开关：
 * 底栏是整屏唯一敢多花一趟着色器的玻璃面，用户要「更液态」时传 [refraction] 即可。
 *
 * @param backdrop 背后内容的采样源。调用方负责用 `layerBackdrop` 采集，
 *   且**采集层必须在玻璃外面**，否则会自己录自己。
 * @param shape 表面轮廓，同时决定裁剪、描边和阴影。
 * @param style 光学处理档位，默认跟当前明暗走。
 * @param material 体色层（模糊半径 + 最多三层颜色）。为空时只用 style 的模糊。
 * @param alpha 所有颜色层、描边、阴影的透明度乘数。
 * @param stroke 边缘描边；为空不画。
 * @param shadow 投影；为空不画。
 * @param enabled 关掉时只剩内容，不读背景。切换场景用这个，别把 alpha 设 0。
 */
fun Modifier.softGlass(
    backdrop: Backdrop,
    shape: GlassShape,
    style: GlassStyle = GlassDefaults.Style,
    material: GlassMaterial? = null,
    alpha: Float = 1f,
    stroke: EdgeStroke? = null,
    shadow: GlassShadow? = GlassShadows.Regular,
    enabled: Boolean = true,
    /**
     * 边缘折射强度（dp）。0 = 平玻璃，只做模糊；底栏建议 5~6dp。
     *
     * 折射用一次背景采样 + 系统真高斯，代价可控，但**只给底栏开**——
     * 页面里的玻璃件在采集层内部，不能读背景。API 32 上自动降级为素色卡片。
     */
    refraction: Float = 0f,
    /** 当前是否深色主题。液态玻璃的弧光亮度、体色深浅按它调。 */
    isDark: Boolean = false,
    /**
     * shader 实例键。
     *
     * 同一个 AGSL 程序被多个表面共用时，`setFloatUniform` 会互相覆盖——
     * HWUI 是异步渲染，真正画的时候 uniform 可能已经是另一个表面的值了。
     * 表现是边缘光/阴影跑到面板内部变成一团光斑。每类表面给一个不同的键，
     * 就等于各自持有一份 shader 实例。
     */
    shaderKey: String = "",
): Modifier {
    if (!enabled) return this

    // API 32 及以下没有 RuntimeShader：不读背景，直接铺一层近似的不透明底，
    // 保证文字可读。看起来是「素色卡片」而不是玻璃，但不会糊成一片。
    if (!isRuntimeShaderSupported()) {
        return softGlassFallback(shape, style, material, alpha)
    }

    return this
        .softGlassShadow(shape, shadow, alpha, shaderKey)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (refraction > 0f) {
                    // ===== 真高斯液态玻璃 =====
                    // 管线（注意调用顺序和执行顺序相反）：
                    //   调用：blur() → 折射 shader
                    //   执行：折射 shader（content = 原始背景）→ blur 真高斯
                    // backdrop 的 chain(old, new) 让**后加的 effect 先执行**，
                    // 所以折射 shader 写在后面，才能拿到未经模糊的原始背景去掰弯。
                    //
                    // vc163/164/165 把模糊写在 shader 里（9→13 抽头离散采样），
                    // 遇到底栏下方的细字必然重影：抽头间距和笔画同量级，
                    // 每个抽头都是一份错位的字。真高斯 RenderEffect 是双 pass
                    // separable 卷积，没有离散抽头，这才是根治。
                    with(density) {
                        // 模糊半径：真高斯可以开得比抽头 approximation 大得多，
                        // 6dp 足以把 14sp 的字糊成色块，同时保留圆角处的折射弯曲。
                        blur(6f.dp.toPx())
                        liquidRefraction(
                            shape = shape,
                            refractionPx = refraction.dp.toPx(),
                            bezelPx = 7.dp.toPx(),
                            highlightAlpha = 0.26f,
                            isDark = isDark,
                        )
                    }
                } else {
                    // ===== 平玻璃（页面内玻璃件、菜单）：只模糊 + 提饱和 =====
                    val radiusDp = material?.blurRadius
                        ?: (style.blur.small / GlassDefaults.SourceDensity).dp
                    blur((radiusDp.value * density).coerceAtLeast(1f))
                    colorControls(
                        brightness = style.blend.brightness * 0.35f,
                        contrast = 1f,
                        saturation = style.blend.saturation.coerceIn(1f, 2.5f),
                    )
                }
                Unit
            },
        )
        .drawBehind {
            // 液态玻璃的体色、弧光、暗边全在 shader 里了。这里再叠
            // puredThinGlass 三层色 + rim 描边 = 又变回磨砂板，
            // 所以整条 drawBehind 对液态玻璃直接跳过。
            if (refraction <= 0f) {
                if (material != null) drawMaterialLayers(shape, material, alpha)
                // 描边和 rim 合成一次着色器（两者都是沿轮廓的加法光，等价）
                if (stroke != null) {
                    drawGlassEdge(shape, stroke, style, style.inner.tint, alpha, shaderKey)
                } else {
                    drawGlassRim(shape, style, style.inner.tint, alpha, shaderKey)
                }
            }
        }
}

/** 只画投影，不画玻璃本体。和 [softGlass] 用同一个 shape。 */
fun Modifier.softGlassShadow(
    shape: GlassShape,
    shadow: GlassShadow? = GlassShadows.Regular,
    alpha: Float = 1f,
    key: String = "",
): Modifier = if (shadow == null || !isRuntimeShaderSupported()) {
    this
} else {
    this.drawBehind { drawGlassShadow(shape, shadow, alpha, key) }
}

/**
 * 材料的三层体色，按各自的混合模式叠在模糊后的背景上。
 *
 * 官方是在 AGSL 里一次算完的；这里用 `drawPath` + [BlendMode] 逐层叠，
 * 省掉一段 shader，观感差别主要在非常暗或非常亮的极端背景上。
 */
private fun DrawScope.drawMaterialLayers(
    shape: GlassShape,
    material: GlassMaterial,
    alpha: Float,
) {
    val path = shape.toPath(size, density)
    val layers = listOfNotNull(material.first, material.second, material.third)
    for (layer in layers) {
        val colorAlpha = (layer.color.alpha * alpha).coerceIn(0f, 1f)
        if (colorAlpha <= 0f) continue
        drawPath(
            path = path,
            color = layer.color,
            alpha = colorAlpha,
            blendMode = layer.mode.toBlendMode(),
        )
    }
}

/** 没有 RuntimeShader 时的兜底：一层低透明度的表面色，保可读性。 */
private fun Modifier.softGlassFallback(
    shape: GlassShape,
    style: GlassStyle,
    material: GlassMaterial?,
    alpha: Float,
): Modifier = drawBehind {
    val base = material?.first?.color ?: style.inner.tint
    // 兜底不追求通透，只要不是纯色块：0.82 的白/黑在深浅两套主题下都能托住文字
    val color: Color = if (base == Color.Unspecified) {
        Color.Gray.copy(alpha = 0.9f)
    } else {
        base
    }
    drawPath(
        path = shape.toPath(size, density),
        color = color,
        alpha = (0.82f * alpha).coerceIn(0f, 1f),
    )
}

/**
 * [GlassShape] 本身就是 [androidx.compose.ui.graphics.Shape]，转成 [Path] 供逐层填充。
 *
 * density 必须传当前屏幕真实的那个。[GlassShape] 的圆角是 `CornerSize(Dp)`，
 * `createOutline` 靠 density 把 dp 换成 px。早先这里图省事写了 `Density(1f)`，
 * 28dp 的圆角在 3x 屏上只画出 28px（≈9dp）：材质层和边缘光画出来比 `clip` 的
 * 裁剪区更「方」，四个角上的边缘光整段被裁掉，只在直边上留一条。
 */
internal fun GlassShape.toPath(size: Size, density: Float): Path {
    val outline = createOutline(size, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.Density(density))
    return when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
}

private fun GlassColorBlendMode.toBlendMode(): BlendMode = when (this) {
    GlassColorBlendMode.SrcOver -> BlendMode.SrcOver
    GlassColorBlendMode.PlusDarker -> BlendMode.Plus
    GlassColorBlendMode.PlusLighter -> BlendMode.Plus
    GlassColorBlendMode.SoftLight -> BlendMode.Softlight
    GlassColorBlendMode.HardLight -> BlendMode.Hardlight
    GlassColorBlendMode.Overlay -> BlendMode.Overlay
    GlassColorBlendMode.Luminosity -> BlendMode.Luminosity
    GlassColorBlendMode.ColorDodge -> BlendMode.ColorDodge
    GlassColorBlendMode.ColorBurn -> BlendMode.ColorBurn
}
