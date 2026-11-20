/*
 * 底栏专用「液态玻璃」折射着色器。
 *
 * 光学模型移植自 QWEA0/Liquid-Glass-Android（MIT, Copyright (c) 2025-2026 pandadog），
 * 并按 Apple WWDC25「Meet Liquid Glass」与多个真机验证过的实现校准：
 *   - 逆幂透镜折射剖面（贴边弯折最剧烈，内侧留缓慢回落的尾巴）
 *   - 方向性菲涅尔弧光（随法线朝向光源变化，不是整圈固定白线）
 *   - 主题体色（底栏下方是密排文字列表，必须压低对比度）
 *
 * 【重要】模糊不在这个 shader 里做，交给系统的 RenderEffect 真高斯（见 SoftGlass.kt）。
 * vc163/164/165 连续三版都在 shader 里手写抽头模糊（9 → 13 抽头），
 * 但离散采样遇到细字必然重影：抽头间距和字笔画同量级时，每个抽头都是一份错位的字。
 * 真高斯（RenderEffect.createBlurEffect，双 pass separable）没有这个问题。
 *
 * 管线（在 SoftGlass.kt 的 effects 里）：
 *   调用顺序 blur() → 折射 shader；因为 backdrop 的 chain(old,new) 让新 effect 先执行，
 *   实际执行是 折射(原始背景) → 真高斯。shader 的 content 是原始背景，顺序正确。
 */
package com.yfantasyxx.ui.nav.liquid

import androidx.compose.ui.geometry.CornerRadius
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import com.yfantasyxx.ui.glass.material.GlassShape
import kotlin.math.min

/**
 * 在 backdrop 上做液态玻璃的**折射 + 光照**，模糊由调用方的 `blur()` 负责。
 *
 * 必须和 `blur()` 一起放在 effects 链里，`blur()` 在前、本函数在后：
 * backdrop 的 chain 让后加的 effect 先执行，于是本 shader 拿到原始背景做折射，
 * blur 再对折射结果做真高斯。
 *
 * @param shape 表面轮廓；同时决定裁剪与 SDF，两者必须同源
 * @param refractionPx 边缘最大折射位移（px），底栏建议 5~6dp * density
 * @param bezelPx 边缘弯曲带宽度（px），必须远小于控件短边，底栏建议 7dp * density
 * @param highlightAlpha 边缘弧光强度，建议 0.22~0.3
 * @param isDark 深色主题：高光更亮、体色更暗
 */
internal fun BackdropEffectScope.liquidRefraction(
    shape: GlassShape,
    refractionPx: Float,
    bezelPx: Float,
    highlightAlpha: Float,
    isDark: Boolean,
) {
    if (!isRuntimeShaderSupported()) return
    if (size.width <= 0f || size.height <= 0f) return

    // 折射把采样点沿法线内推，录制范围要往外扩一圈，否则边缘采到被拉长的像素。
    // blur() 自己也会把 padding 提到它的半径，两者取最大值即可。
    val grow = refractionPx + 6f
    if (padding < grow) padding = grow

    val radii = glassCornerRadii(shape)
    val w = size.width
    val h = size.height
    val pad = padding
    val b = maxOf(bezelPx, 1f)
    val dark = if (isDark) 1f else 0f
    // 着色器编译失败时不能让整条底栏崩掉：退回没有折射的普通背景
    runCatching {
        runtimeShaderEffect(
            key = "YFXNavLiquidGlass",
            shaderString = LIQUID_GLASS_SHADER,
            uniformShaderName = "content",
        ) {
            setFloatUniform("size", w, h)
            setFloatUniform("offset", -pad, -pad)
            setFloatUniform("layerSize", w + pad * 2f, h + pad * 2f)
            setFloatUniform("cornerRadii", radii)
            setFloatUniform("refraction", refractionPx)
            setFloatUniform("bezel", b)
            setFloatUniform("highlight", highlightAlpha)
            setFloatUniform("darkTheme", dark)
        }
    }
}

/** 从 GlassShape 取四角半径（px），顺序 TL / TR / BR / BL */
private fun BackdropEffectScope.glassCornerRadii(shape: GlassShape): FloatArray {
    val sizePx = size
    val maxRadius = min(sizePx.width, sizePx.height) / 2f
    fun px(cornerSize: androidx.compose.foundation.shape.CornerSize): Float =
        min(cornerSize.toPx(sizePx, this), maxRadius)
    return floatArrayOf(
        px(shape.topStart), px(shape.topEnd), px(shape.bottomEnd), px(shape.bottomStart)
    )
}

private const val LIQUID_GLASS_SHADER = """
uniform shader content;

uniform float2 size;        // 形状尺寸 px
uniform float2 offset;      // -padding：把放大后的录制坐标还原到形状坐标
uniform float2 layerSize;   // 含 padding 的录制层尺寸
uniform float4 cornerRadii; // TL TR BR BL
uniform float refraction;   // 边缘最大折射位移
uniform float bezel;        // 边缘弯曲带宽度
uniform float highlight;    // 弧光强度
uniform float darkTheme;

float radiusAt(float2 c) {
    if (c.x >= 0.0) {
        if (c.y <= 0.0) return cornerRadii.y;
        return cornerRadii.z;
    }
    if (c.y <= 0.0) return cornerRadii.x;
    return cornerRadii.w;
}

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float2 gradRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(p) * normalize(max(q, 0.0) + float2(0.0001));
    }
    float gx = step(q.y, q.x);
    return sign(p) * float2(gx, 1.0 - gx);
}

float2 safeN(float2 v) {
    float l = length(v);
    if (l > 0.0001) {
        return v / l;
    }
    return float2(0.0);
}

half4 sampleSafe(float2 p) {
    return content.eval(clamp(p, float2(0.5), layerSize - float2(0.5)));
}

half4 main(float2 coord) {
    float2 local = coord + offset;
    float2 halfSize = size * 0.5;
    float2 centered = local - halfSize;
    float r = radiusAt(local);

    float sd = sdRoundRect(centered, halfSize, r);   // 内部为负
    float2 normal = safeN(gradRoundRect(centered, halfSize, r));
    // 深度方向混一点向中心的量，让中段也有轻微放大（厚玻璃感）
    float2 grad = safeN(normal + safeN(centered) * 0.45);

    // 0 = 贴边, 1 = 距边 bezel 以上（内部平坦）
    float thickness = clamp(-sd / bezel, 0.0, 1.0);
    // 逆幂衰减剖面（iOS 透镜）：贴边 slope→1，内侧缓慢回落
    float p = 2.2;
    float g = pow(5.0, -p);
    float slope = (pow(1.0 + 4.0 * thickness, -p) - g) / (1.0 - g);
    float edgeW = 1.0 - thickness;

    // 沿【内法线】采样：边缘显示内侧背景的压缩镜像（iOS 的标志性观感）。
    // 只采样一次，模糊由外面的真高斯 RenderEffect 完成 —— 不再有离散抽头的重影。
    float2 refracted = coord - grad * (slope * refraction);
    half4 base = sampleSafe(refracted);

    // 方向性菲涅尔弧光：沿边缘铺成一道随光源变化的弧，不是整圈固定白线
    float lightAngle = -0.75;
    float2 lightDir = float2(cos(lightAngle), sin(lightAngle));
    float facing = dot(normal, -lightDir);
    float lobe = pow(abs(facing), 1.2);
    float hair = clamp(1.0 - clamp(-sd / 2.5, 0.0, 1.0), 0.0, 1.0);
    float spec = hair * lobe * highlight;
    float glow = pow(edgeW, 2.0) * max(facing, 0.0) * highlight * 0.28;
    float shade = pow(edgeW, 3.0) * pow(max(-facing, 0.0), 2.0) * 0.10;

    // 提饱和
    float luma = dot(base.rgb, half3(0.2126, 0.7152, 0.0722));
    half3 rgb = clamp(mix(half3(luma), base.rgb, 1.08), 0.0, 1.0);

    // 主题体色：底栏下方是密排小字，混一层实色把文字对比度压下去。
    float veil = mix(0.45, 0.42, darkTheme);
    half3 bodyColor = mix(half3(0.97), half3(0.10), darkTheme);
    rgb = mix(rgb, bodyColor, veil);

    rgb += half3(spec + glow) * mix(0.85, 1.0, darkTheme);
    rgb *= (1.0 - shade);

    return half4(rgb, 1.0);
}
"""
