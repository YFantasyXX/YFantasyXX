package com.yfantasyxx

import android.app.Application
import android.content.Context
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.data.prefs.SettingsRepository
import com.yfantasyxx.sync.BackupManager
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
        container = AppContainer(this)
        CrashLogger.install(this)
        // 首次启动写入内置动作库；清理异常退出留下的重复草稿
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.workoutRepository.seedIfEmpty()
            container.workoutRepository.cleanupStaleDrafts()
        }
    }
}

/**
 * 手动依赖容器（不引 Hilt，保持轻量）
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    @Volatile
    private var database: AppDatabase? = null

    fun db(): AppDatabase = database ?: synchronized(this) {
        database ?: androidx.room.Room
            .databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
            .also { database = it }
    }

    /** 恢复备份后需要重建数据库实例 */
    fun rebuildDatabase(): AppDatabase = synchronized(this) {
        database?.close()
        database = androidx.room.Room
            .databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
        database!!
    }

    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(db()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val backupManager: BackupManager by lazy { BackupManager(appContext) { db() } }
}
