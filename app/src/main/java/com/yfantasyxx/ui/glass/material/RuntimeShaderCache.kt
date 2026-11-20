// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.yfantasyxx.ui.glass.material

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized

/**
 * Cache for compiled [RuntimeShader] instances, avoiding recompilation each frame.
 */
interface RuntimeShaderCache {

    /**
     * Returns a cached [RuntimeShader] for the given [key], compiling from [string] if not yet cached.
     *
     * @param key The cache key identifying the compiled [RuntimeShader].
     * @param string The AGSL/SkSL shader source compiled on a cache miss.
     */
    fun obtainRuntimeShader(key: String, string: String): RuntimeShader
}

/**
 * CompositionLocal providing a shared [RuntimeShaderCache] within the composition tree.
 *
 * All backdrop effect scopes within the same composition share this cache,
 * avoiding redundant shader compilation while allowing the cache to be
 * garbage-collected when the composition is disposed.
 */
val LocalRuntimeShaderCache = staticCompositionLocalOf<RuntimeShaderCache> {
    DefaultRuntimeShaderCache()
}

@OptIn(InternalCoroutinesApi::class)
class DefaultRuntimeShaderCache : RuntimeShaderCache {

    private val lock = SynchronizedObject()

    /**
     * 每个 key 存**一组**实例，按调用顺序轮转取用。
     *
     * 原来是 `getOrPut` 一个 key 一份。问题在于：`RuntimeShader` 的 uniform 是
     * 对象状态，而 HWUI 的绘制是**异步执行**的——DisplayList 里记录的只是着色器引用，
     * 真正在 RenderThread 上跑的时候读的是那一刻的 uniform 值。同一帧里如果有两个
     * 表面共用一个 key（下拉菜单 + 弹层、两个叠着的对话框），后设置的一方会把
     * 前者的参数覆盖掉，表现是边缘光/阴影的参数错位：面板内部凭空出现一团光斑，
     * 或者某一边的描边整个消失。
     *
     * 这个现象很难复现（要看两处绘制是否落在同一帧），所以不做「每个调用点自己
     * 想办法错开」，直接从缓存层保证：同时存在的表面各自拿到不同实例。
     * 池子比同时绘制的玻璃面多一些即可，编译成本只在第一次付。
     */
    private val pools = mutableMapOf<String, Array<RuntimeShader?>>()
    private val cursors = mutableMapOf<String, Int>()

    /**
     * 注意 API 级别：`RuntimeShader` 是 33+，而 minSdk 是 32。
     *
     * 这里不做运行时判断，**判断在所有调用方**——`GlassOverlays` 与
     * `EdgeStrokeDrawing` 里每个调用点前面都有一句
     * `if (!isRuntimeShaderSupported()) return`，32 上根本走不到这儿。
     * lint 看不出跨文件的守卫，所以显式压掉这条。
     */
    @Suppress("NewApi")
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader = synchronized(lock) {
        val pool = pools.getOrPut(key) { arrayOfNulls<RuntimeShader>(POOL_SIZE) }
        val index = (cursors[key] ?: 0) % POOL_SIZE
        cursors[key] = index + 1
        // **必须是懒创建**：`RuntimeShader(String)` 在构造时就把 AGSL 编译成机器码，
        // 一次几毫秒。原来这里 `Array(POOL_SIZE) { RuntimeShader(string) }` 会
        // 在某个 key 第一次被画到的**那一帧**里连编 8 份——打开第一个玻璃菜单时
        // 就是一次明显的顿卡。改成用时才编，同时存在几个表面就编几份。
        pool[index] ?: RuntimeShader(string).also { pool[index] = it }
    }

    private companion object {
        /**
         * 池子大小。同一帧里可能同时画到的玻璃表面：底栏 / 下拉菜单 / 弹层 /
         * 弹层上的弹层。每种表面的 rim、edgeStroke、shadow 是三个不同的 key，
         * 各有一份池子，所以每个 key 真正并发的只有 1~2 个，4 已经有余量。
         */
        const val POOL_SIZE = 4
    }
}
