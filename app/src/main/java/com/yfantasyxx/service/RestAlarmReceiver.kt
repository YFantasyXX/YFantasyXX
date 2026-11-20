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
        if (snapshot.endTime == 0L) return
        val now = System.currentTimeMillis()
        // 提前唤醒（四舍五入到整秒时可能早 1 秒）：把闹钟重设一次，
        // 不能直接 return —— 那样这一轮提醒就彻底没了
        if (now < snapshot.endTime) {
            RestAlarmScheduler.schedule(context, snapshot.endTime)
            return
        }
        // 只有「晚到半天以上」才当成残留闹钟。
        // 以前卡的是 2 分钟：没有精确闹钟权限时走的是 setAndAllowWhileIdle，
        // Doze 下晚几分钟是常态，于是提醒被整条丢掉，用户什么都没听到
        if (now - snapshot.endTime > 12 * 60 * 60 * 1000L) return

        RestTimerStore.clear(context)
        RestAlarmScheduler.cancel(context)
        // 关掉倒计时通知
        RestNotifier.cancelCountdown(context)

        // 交给服务去弹：悬浮窗、FGS 通知、声音都在那边统一处理，
        // 这样「App 被杀掉」和「App 还在跑」两条路径的表现才一致。
        // 精确闹钟广播给了临时白名单，后台起前台服务是允许的。
        val started = runCatching {
            RestTimerService.showFinished(context, snapshot.label)
        }.isSuccess
        // 起不来（厂商限制等）就退回只有通知，至少别什么都不响
        if (!started) RestNotifier.notifyRestFinished(context, snapshot.label)
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
