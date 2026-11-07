package com.yfantasyxx

import android.app.Application
import android.content.Context
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.data.prefs.SettingsRepository
import com.yfantasyxx.sync.BackupManager
import com.yfantasyxx.update.UpdateRepository
import com.yfantasyxx.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YFantasyXXApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志要最早装上：安全模式判定本身也可能出意外，得留痕
        CrashLogger.install(this)
        // 必须在任何界面、数据库、网络初始化之前：判断上一次是不是没活下来。
        // 这一步只读一个 SharedPreferences 布尔量，不会抛异常，也不会碰渲染。
        val safeMode = com.yfantasyxx.util.SafeModeGuard.recordLaunch(this)

        container = AppContainer(this)
        com.yfantasyxx.util.AppVisibility.install(this)
        // 读回测试工具上次拨的时间偏移（正式版没有入口，永远是 0）
        com.yfantasyxx.util.Clock.install(this)

        // 安全模式只保留日志能力：不建库、不灌内置动作库、不查更新。
        // 连 Room 打不开这类启动崩溃也能绕过去——用户至少能进到诊断界面。
        if (safeMode) return

        // 首次启动写入内置动作库；清理异常退出留下的重复草稿
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.workoutRepository.seedIfEmpty()
            container.workoutRepository.cleanupStaleDrafts()
            // 静默查一次新版本：内部会自己看开关和 6 小时节流，失败也不打扰用户
            container.updateRepository.autoCheck()
        }
    }
}

/**
 * 手动依赖容器（不引 Hilt，保持轻量）
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /**
     * 应用级协程作用域，活到进程结束。
     *
     * 设置流做成进程内单例（见 SettingsRepository.settings），必须有个
     * 生命周期够长的作用域来跑；用 viewModelScope 会在页面销毁时把流停掉。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var database: AppDatabase? = null

    fun db(): AppDatabase = database ?: synchronized(this) {
        database ?: createDatabase().also { database = it }
    }

    /** 建库：迁移链集中在一处，db() 与 rebuildDatabase() 才不会走偏 */
    private fun createDatabase(): AppDatabase = androidx.room.Room
        .databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
        )
        // 迁移链已验证到 v15；这里保留兜底只为「宁可少几列也别打不开」，
        // 参数 false = 只清 Room 自己管理的表，行为与旧无参版一致
        .fallbackToDestructiveMigration(false)
        .build()

    /** 恢复备份后需要重建数据库实例 */
    fun rebuildDatabase(): AppDatabase = synchronized(this) {
        database?.close()
        createDatabase().also { database = it }
    }

    /**
     * 仓库不再持有 DAO 实例，而是每次按当前库取。
     *
     * 恢复备份会 close() 旧库并重建，如果 DAO 是构造时缓存的字段，
     * 之后所有写入都会打在已关闭的库上（`attempt to re-open an already-closed object`）。
     */
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository { db() } }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext, appScope) }

    /**
     * 软件内更新：检查 GitHub Release、下载 APK、调起安装器。
     *
     * 必须挂在应用级作用域上：下载要能跨页面继续，页面销毁不能把进度带走。
     */
    val updateRepository: UpdateRepository by lazy {
        UpdateRepository(appContext, appScope, settingsRepository)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(
            context = appContext,
            dbProvider = { db() },
            // 恢复失败时旧库已经 close 过，必须在这里换一个能打开的实例，
            // 否则后面每个 DAO 调用都会抛 already-closed
            onDatabaseInvalidated = { rebuildDatabase() },
        )
    }
}
