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
          AND (:keyword = '' OR name LIKE '%' || :keyword || '%' OR primaryMuscle LIKE '%' || :keyword || '%')
        ORDER BY category, name
        """
    )
    fun observeFiltered(keyword: String, category: String?): Flow<List<Exercise>>

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
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkoutTemplate>>

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
        SELECT s.id AS id, s.name AS name, s.startTime AS startTime,
               (SELECT COUNT(*) FROM session_exercises se WHERE se.sessionId = s.id) AS exerciseCount,
               (SELECT COUNT(*) FROM set_logs sl
                    INNER JOIN session_exercises se2 ON sl.sessionExerciseId = se2.id
                    WHERE se2.sessionId = s.id AND sl.completed = 1) AS completedSetCount,
               (SELECT COUNT(*) FROM set_logs sl2
                    INNER JOIN session_exercises se3 ON sl2.sessionExerciseId = se3.id
                    WHERE se3.sessionId = s.id) AS plannedSetCount
        FROM sessions s WHERE s.endTime IS NULL ORDER BY s.startTime DESC LIMIT 1
        """
    )
    fun observeActiveSummary(): Flow<ActiveSessionSummary?>

    /** 历史列表摘要（已完成的） */
    @Query(
        """
        SELECT s.id AS id,
               CASE WHEN s.name = '' THEN '训练' ELSE s.name END AS name,
               s.templateId AS templateId,
               s.startTime AS startTime,
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

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清理多余的进行中草稿，只保留最近一个 */
    @Query(
        """
        DELETE FROM sessions WHERE endTime IS NULL AND id NOT IN (
            SELECT id FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1
        )
        """
    )
    suspend fun deleteStaleDrafts()

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
        WHERE se.exerciseId = :exerciseId AND s.endTime IS NOT NULL
        ORDER BY s.startTime DESC, sl.setNumber LIMIT :limit
        """
    )
    suspend fun recentSetsForExercise(exerciseId: Long, limit: Int): List<SetLog>

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
        TemplateExercise::class,
        WorkoutSession::class,
        SessionExercise::class,
        SetLog::class,
        BodyMetric::class,
    ],
    version = 3,
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
