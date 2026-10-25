package com.yfantasyxx.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * App 是否在前台。
 *
 * 休息悬浮窗只在这种情况下才有意义——人已经在训练页里的话，
 * 屏幕上本来就有休息倒计时条，再盖一层悬浮窗纯属碍事。
 *
 * 用 Activity 的 started/stopped 计数而不是 [ProcessLifecycleOwner]，
 * 是为了少引一个 lifecycle-process 依赖，也避免首次启动的时序问题。
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    @Volatile
    var foreground: Boolean = false
        private set

    private var startedCount = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
        foreground = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) foreground = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
