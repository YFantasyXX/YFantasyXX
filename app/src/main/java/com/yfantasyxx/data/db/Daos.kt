package com.yfantasyxx.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE archived = 0 ORDER BY category, name")
    fun observeAll(): Flow<List<Exercise>>

    @Query(
        """
        SELECT * FROM exercises
        WHERE archived = 0
          AND (:category IS NULL OR category = :category)
          AND (:equipment IS NULL OR equipment = :equipment)
          AND (:keyword = '' OR name LIKE '%' || :keyword || '%' OR primaryMuscle LIKE '%' || :keyword || '%')
        ORDER BY category, name
        """
    )
    fun observeFiltered(
        keyword: String,
        category: String?,
        equipment: String?,
    ): Flow<List<Exercise>>

    /** 数据库中实际存在的分类（用于筛选条动态生成） */
    @Query(
        "SELECT DISTINCT category FROM exercises WHERE archived = 0 AND category != '' ORDER BY category"
    )
    fun observeCategories(): Flow<List<String>>

    /** 数据库中实际存在的器械；传入分类时只返回该分类下的器械 */
    @Query(
        """
        SELECT DISTINCT equipment FROM exercises
        WHERE archived = 0 AND equipment != ''
          AND (:category IS NULL OR category = :category)
        ORDER BY equipment
        """
    )
    fun observeEquipments(category: String?): Flow<List<String>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): Exercise?

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Insert
    suspend fun insert(exercise: Exercise): Long

    @Insert
    suspend fun insertAll(exercises: List<Exercise>)

    @Update
    suspend fun update(exercise: Exercise)

    @Query("DELETE FROM exercises WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustom(id: Long)

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Exercise>
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY sortOrder, createdAt DESC")
    fun observeAll(): Flow<List<WorkoutTemplate>>

    @Query("SELECT * FROM template_folders ORDER BY sortOrder, id")
    fun observeFolders(): Flow<List<com.yfantasyxx.data.db.TemplateFolder>>

    @Insert
    suspend fun insertFolder(folder: com.yfantasyxx.data.db.TemplateFolder): Long

    @Update
    suspend fun updateFolder(folder: com.yfantasyxx.data.db.TemplateFolder)

    @Query("UPDATE template_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("DELETE FROM template_folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("UPDATE templates SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("UPDATE templates SET folderId = :folderId, sortOrder = :order WHERE id = :id")
    suspend fun updateTemplatePlacement(id: Long, folderId: Long?, order: Int)

    /** 首页只显示：未分组模板 + 勾选了「显示在首页」的文件夹内模板 */
    @Query(
        """
        SELECT t.* FROM templates t
        WHERE t.folderId IS NULL
           OR EXISTS (
               SELECT 1 FROM template_folders f
               WHERE f.id = t.folderId AND f.showOnHome = 1
           )
        ORDER BY t.sortOrder, t.createdAt DESC
        """
    )
    fun observeHomeTemplates(): Flow<List<WorkoutTemplate>>

    @Query("UPDATE template_folders SET showOnHome = :show WHERE id = :id")
    suspend fun setFolderShowOnHome(id: Long, show: Boolean)

    @Transaction
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun observeAllWithExercises(): Flow<List<TemplateWithExercises>>

    @Transaction
    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun getWithExercises(id: Long): TemplateWithExercises?

    @Insert
    suspend fun insert(template: WorkoutTemplate): Long

    @Update
    suspend fun update(template: WorkoutTemplate)

    @Delete
    suspend fun delete(template: WorkoutTemplate)

    @Insert
    suspend fun insertExercise(item: TemplateExercise): Long

    @Update
    suspend fun updateExercise(item: TemplateExercise)

    @Query("DELETE FROM template_exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("DELETE FROM template_exercises WHERE templateId = :templateId")
    suspend fun deleteExercisesOfTemplate(templateId: Long)
}

@Dao
interface SessionDao {
    /** 进行中的草稿（最近一个） */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, COALESCE(s.activeStartedAt, s.startTime) AS startTime,
               (SELECT COUNT(*) FROM session_exercises se WHERE se.sessionId = s.id) AS exerciseCount,
               (SELECT COUNT(*) FROM set_logs sl
                    INNER JOIN session_exercises se2 ON sl.sessionExerciseId = se2.id
                    WHERE se2.sessionId = s.id AND sl.completed = 1) AS completedSetCount,
               (SELECT COUNT(*) FROM set_logs sl2
                    INNER JOIN session_exercises se3 ON sl2.sessionExerciseId = se3.id
                    WHERE se3.sessionId = s.id) AS plannedSetCount
        FROM sessions s
        WHERE s.endTime IS NULL
          -- 必须至少完成过一组才算「训练进行中」，只打开没练不显示
          AND EXISTS (
              SELECT 1 FROM set_logs slx
              INNER JOIN session_exercises sex ON slx.sessionExerciseId = sex.id
              WHERE sex.sessionId = s.id AND slx.completed = 1
          )
        ORDER BY s.startTime DESC LIMIT 1
        """
    )
    fun observeActiveSummary(): Flow<ActiveSessionSummary?>

    /** 历史列表摘要（已完成的） */
    @Query(
        """
        SELECT s.id AS id,
               CASE WHEN s.name = '' THEN '训练' ELSE s.name END AS name,
               s.templateId AS templateId,
               COALESCE(s.activeStartedAt, s.startTime) AS startTime,
               s.endTime AS endTime,
               (SELECT COUNT(DISTINCT se.exerciseId) FROM session_exercises se WHERE se.sessionId = s.id) AS exerciseCount,
               (SELECT COUNT(*) FROM set_logs sl
                    INNER JOIN session_exercises se2 ON sl.sessionExerciseId = se2.id
                    WHERE se2.sessionId = s.id AND sl.completed = 1) AS setCount,
               COALESCE((SELECT SUM(sl2.weightKg * sl2.reps) FROM set_logs sl2
                    INNER JOIN session_exercises se3 ON sl2.sessionExerciseId = se3.id
                    WHERE se3.sessionId = s.id AND sl2.completed = 1 AND sl2.isWarmup = 0), 0) AS totalVolume
        FROM sessions s
        WHERE s.endTime IS NOT NULL
        ORDER BY s.startTime DESC
        LIMIT 200
        """
    )
    fun observeSummaries(): Flow<List<SessionSummary>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getWithDetails(id: Long): SessionWithDetails?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveDraft(): SessionWithDetails?

    @Insert
    suspend fun insert(session: WorkoutSession): Long

    @Update
    suspend fun update(session: WorkoutSession)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): WorkoutSession?

    @Query("UPDATE sessions SET name = :name, note = :note WHERE id = :id")
    suspend fun rename(id: Long, name: String, note: String)

    @Query("UPDATE sessions SET endTime = :endTime, name = :name, note = :note WHERE id = :id")
    suspend fun finish(id: Long, endTime: Long, name: String, note: String)

    @Query("UPDATE sessions SET templateId = :templateId WHERE id = :id")
    suspend fun setTemplateId(id: Long, templateId: Long?)

    @Query("UPDATE sessions SET activeStartedAt = :time WHERE id = :id AND activeStartedAt IS NULL")
    suspend fun markStarted(id: Long, time: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清理多余的进行中草稿，只保留最近一个（级联删除其动作与组，避免孤儿数据） */
    @Query(
        """
        DELETE FROM set_logs WHERE sessionExerciseId IN (
            SELECT se.id FROM session_exercises se WHERE se.sessionId IN (
                SELECT id FROM sessions WHERE endTime IS NULL AND id NOT IN (
                    SELECT id FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1
                )
            )
        )
        """
    )
    suspend fun deleteStaleDraftSets()

    @Query(
        """
        DELETE FROM session_exercises WHERE sessionId IN (
            SELECT id FROM sessions WHERE endTime IS NULL AND id NOT IN (
                SELECT id FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1
            )
        )
        """
    )
    suspend fun deleteStaleDraftExercises()

    @Query(
        """
        DELETE FROM sessions WHERE endTime IS NULL AND id NOT IN (
            SELECT id FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1
        )
        """
    )
    suspend fun deleteStaleDraftSessions()

    // ---- 统计 ----

    /** 近 N 天每周训练容量（毫秒时间戳起） */
    @Query(
        """
        SELECT (s.startTime / :weekMs) * :weekMs AS bucket, SUM(sl.weightKg * sl.reps) AS volume
        FROM sessions s
        INNER JOIN session_exercises se ON se.sessionId = s.id
        INNER JOIN set_logs sl ON sl.sessionExerciseId = se.id
        WHERE s.endTime IS NOT NULL AND sl.completed = 1 AND sl.isWarmup = 0
          AND s.startTime >= :since
        GROUP BY bucket ORDER BY bucket
        """
    )
    suspend fun volumeByWeek(since: Long, weekMs: Long): List<BucketVolume>

    /** 近 N 天各分类容量 */
    @Query(
        """
        SELECT e.category AS category, SUM(sl.weightKg * sl.reps) AS volume
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE s.endTime IS NOT NULL AND sl.completed = 1 AND sl.isWarmup = 0
          AND s.startTime >= :since
        GROUP BY e.category ORDER BY volume DESC
        """
    )
    suspend fun volumeByCategory(since: Long): List<CategoryVolume>

    /** 每个动作的 PR */
    @Query(
        """
        SELECT e.id AS exerciseId, e.name AS exerciseName,
               MAX(sl.weightKg) AS bestWeightKg,
               MAX(sl.reps) AS bestReps,
               MAX(sl.weightKg * sl.reps) AS bestVolume
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE sl.completed = 1 AND sl.isWarmup = 0 AND sl.reps > 0
        GROUP BY e.id, e.name
        HAVING MAX(sl.weightKg * sl.reps) > 0
        ORDER BY bestVolume DESC
        """
    )
    fun observePersonalRecords(): Flow<List<ExercisePr>>

    /** 某动作最近一次训练的数据（历史参考） */
    @Query(
        """
        SELECT * FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE se.exerciseId = :exerciseId AND s.endTime IS NOT NULL AND sl.isPr = 0
        ORDER BY s.startTime DESC, sl.setNumber LIMIT :limit
        """
    )
    suspend fun recentSetsForExercise(exerciseId: Long, limit: Int): List<SetLog>

    /** 该动作历史最佳一组（重量优先，含 PR 组，排除热身） */
    @Query(
        """
        SELECT * FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE se.exerciseId = :exerciseId AND sl.completed = 1
          AND sl.isWarmup = 0 AND sl.reps > 0 AND s.endTime IS NOT NULL
        ORDER BY sl.weightKg DESC, sl.reps DESC LIMIT 1
        """
    )
    suspend fun bestSetFor(exerciseId: Long): SetLog?

    /** 近 N 天训练次数 */
    @Query("SELECT COUNT(*) FROM sessions WHERE endTime IS NOT NULL AND startTime >= :since")
    fun observeSessionCount(since: Long): Flow<Int>

    /** 近 N 天总容量 */
    @Query(
        """
        SELECT COALESCE(SUM(sl.weightKg * sl.reps), 0) FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE s.endTime IS NOT NULL AND sl.completed = 1 AND sl.isWarmup = 0 AND s.startTime >= :since
        """
    )
    fun observeTotalVolume(since: Long): Flow<Float>
}

data class BucketVolume(
    val bucket: Long,
    val volume: Float,
)

@Dao
interface SessionExerciseDao {
    @Insert
    suspend fun insert(item: SessionExercise): Long

    @Update
    suspend fun update(item: SessionExercise)

    @Query("DELETE FROM session_exercises WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session_exercises WHERE sessionId = :sessionId ORDER BY sortOrder, id")
    suspend fun getForSession(sessionId: Long): List<SessionExercise>

    @Query("SELECT restSeconds FROM session_exercises WHERE id = :id")
    suspend fun restSecondsOf(id: Long): Int?

    @Query("UPDATE session_exercises SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM session_exercises WHERE sessionId = :sessionId")
    suspend fun nextSortOrder(sessionId: Long): Int
}

@Dao
interface SetLogDao {
    @Insert
    suspend fun insert(log: SetLog): Long

    @Insert
    suspend fun insertAll(logs: List<SetLog>)

    @Update
    suspend fun update(log: SetLog)

    @Query("DELETE FROM set_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM set_logs WHERE sessionExerciseId = :sessionExerciseId ORDER BY setNumber")
    suspend fun getForSessionExercise(sessionExerciseId: Long): List<SetLog>

    @Query("SELECT * FROM set_logs WHERE id = :id")
    suspend fun getById(id: Long): SetLog?

    @Query("SELECT COALESCE(MAX(setNumber), 0) FROM set_logs WHERE sessionExerciseId = :sessionExerciseId")
    suspend fun maxSetNumber(sessionExerciseId: Long): Int
}

@Dao
interface BodyMetricDao {
    @Query("SELECT * FROM body_metrics ORDER BY date DESC")
    fun observeAll(): Flow<List<BodyMetric>>

    @Query("SELECT * FROM body_metrics WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: Long): BodyMetric?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: BodyMetric): Long

    @Upsert
    suspend fun upsert(metric: BodyMetric)

    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        Exercise::class,
        WorkoutTemplate::class,
        TemplateFolder::class,
        TemplateExercise::class,
        WorkoutSession::class,
        SessionExercise::class,
        SetLog::class,
        BodyMetric::class,
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun templateDao(): TemplateDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionExerciseDao(): SessionExerciseDao
    abstract fun setLogDao(): SetLogDao
    abstract fun bodyMetricDao(): BodyMetricDao

    companion object {
        const val NAME = "yfantasyxx.db"

        /** v8 -> v9：训练动作记录模板计划的组数 */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN plannedSets INTEGER")
            }
        }

        /** v7 -> v8：文件夹可控制是否在首页展示其模板 */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE template_folders ADD COLUMN showOnHome INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /** v6 -> v7：组可标记为 PR 极限组 */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE set_logs ADD COLUMN isPr INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 -> v6：模板动作与训练动作支持独立休息时长 */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE template_exercises ADD COLUMN restSeconds INTEGER")
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN restSeconds INTEGER")
            }
        }

        /** v4 -> v5：模板支持文件夹分类与自定义排序 */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS template_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("ALTER TABLE templates ADD COLUMN folderId INTEGER")
                db.execSQL("ALTER TABLE templates ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 -> v4：训练记录增加实际开始计时时刻 */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN activeStartedAt INTEGER")
            }
        }

        /** v2 -> v3：模板动作支持目标重量与热身标记 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE template_exercises ADD COLUMN targetWeightKg REAL")
                db.execSQL(
                    "ALTER TABLE template_exercises ADD COLUMN isWarmup INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v1 -> v2：动作支持自定义休息时长 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN restSeconds INTEGER")
            }
        }
    }
}
