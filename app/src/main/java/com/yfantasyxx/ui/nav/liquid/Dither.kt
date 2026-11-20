package com.yfantasyxx.ui.nav.liquid

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported

/**
 * 给玻璃表面撒一层极细的噪点，作用是把 8 位色深下的色带打散。
 *
 * 模糊之后是一大片接近纯色的渐变，这种区域最容易看出「台阶」。
 * 上游库没有提供抖动（原来的 Miuix 分叉里叫 `noiseDither`），
 * 这里自己补一段 AGSL，走的是同一个 [BackdropEffectScope]。
 *
 * 噪声只跟像素坐标有关，不掺时间项：同一块玻璃每帧拿到的噪点完全一样，
 * 所以不会「闪」，看起来就是一层磨砂质感。
 *
 * @param amount 抖动幅度，`0.006` 约等于 ±0.003（±0.3% 亮度），
 *   肉眼看不出噪点，只够把渐变里的台阶磨平。
 */
internal fun BackdropEffectScope.dither(amount: Float) {
    if (!isRuntimeShaderSupported()) return
    if (amount <= 0f) return

    // 着色器编译失败时不能让整条底栏跟着崩：退回「没有抖动」，玻璃照常显示
    runCatching {
        runtimeShaderEffect(
            key = "YFXDither",
            shaderString = DITHER_SHADER,
            uniformShaderName = "content",
        ) {
            setFloatUniform("amount", amount)
        }
    }
}

/**
 * 把离屏层的录制范围向四周扩大 [px] 像素。
 *
 * 上游库的 `blur` 在 [androidx.compose.ui.graphics.TileMode.Clamp]（默认值）下
 * 不设 padding，模糊会直接采样到层的边缘；原来的 Miuix 分叉里 `blur`
 * 会把 padding 顶到模糊半径。手动补回来，边缘才和以前一样采到真实背景，
 * 而不是被拉长的边缘像素。
 */
internal fun BackdropEffectScope.pad(px: Float) {
    if (px > padding) {
        padding = px
    }
}

private const val DITHER_SHADER = """
uniform shader content;
uniform float amount;

// 稳定的哈希：同一个像素永远拿到同一个值，避免噪点逐帧闪烁
float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 coord) {
    half4 color = content.eval(coord);
    float noise = (hash(coord) - 0.5) * amount;
    // 逐通道相加，避免 half3 + float3 的隐式类型转换在部分驱动上编不过
    return half4(color.r + noise, color.g + noise, color.b + noise, color.a);
}
"""
