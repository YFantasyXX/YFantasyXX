package com.yfantasyxx.ui.dev

import com.yfantasyxx.BuildConfig

/**
 * 测试工具的开关。
 *
 * lite 是一个 buildType（`initWith(release)` + `.debug` 包名），所以这里判断
 * `BUILD_TYPE` 而不是 FLAVOR——本项目没有 productFlavors。
 *
 * `BuildConfig.BUILD_TYPE` 是编译期常量，正式版（release）里
 * `if (DevTools.enabled)` 会被折叠成 `false`，整段入口跟着被 R8 删掉，
 * 用户永远看不到这个页面。
 */
object DevTools {
    val enabled: Boolean = BuildConfig.BUILD_TYPE == "lite"
}
