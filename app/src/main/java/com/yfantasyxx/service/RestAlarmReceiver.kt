package com.yfantasyxx.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.yfantasyxx.util.RestTimerStore

/**
 * 休息结束的精确闹钟：即使 App 在后台被系统冻结、前台服务不再执行，
 * 闹钟也会唤醒进程，弹出提醒并震动。
 */
class RestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = RestTimerStore.snapshot(context)
        // 还在计时中说明是提前唤醒，忽略
        if (snapshot.running) return

        RestNotifier.notifyRestFinished(context, snapshot.label)
        RestTimerStore.clear(context)
        RestAlarmScheduler.cancel(context)
        // 关闭倒计时通知与前台服务
        RestNotifier.cancelCountdown(context)
        runCatching {
            context.stopService(Intent(context, RestTimerService::class.java))
        }
    }
}

/** 设置/取消休息结束闹钟 */
object RestAlarmScheduler {

    private const val REQUEST_CODE = 2001

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(context: Context, endTime: Long) {
        runCatching {
            val am = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
            val pi = pendingIntent(context)
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pi)
            } else {
                // 未授予精确闹钟权限时退化为非精确闹钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pi)
            }
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val am = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
            am.cancel(pendingIntent(context))
        }
    }
}
