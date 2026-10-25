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

    /**
     * 动作库总条数（不受搜索 / 筛选影响）。
     *
     * 界面上必须能直接看到这个数：动作库是「按名字差集增量补齐」的，
     * 一旦某次补齐没跑成，用户的库会停在旧条数而没有任何提示——
     * 只靠滑动列表是感觉不出来的。
     */
    @Query("SELECT COUNT(*) FROM exercises WHERE archived = 0")
    fun observeCount(): Flow<Int>

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

    /** 已有动作名（用于增量补齐内置动作库，按名字去重） */
    @Query("SELECT name FROM exercises")
    suspend fun allNames(): List<String>

    /**
     * 次肌群还是空的动作名。
     *
     * 回填只该对这些行跑：动作库扩到 775 条后，无条件回填等于每次冷启动
     * 执行 775 次 no-op UPDATE，每条一次事务。
     */
    @Query("SELECT name FROM exercises WHERE secondaryMuscles = '' AND isCustom = 0")
    suspend fun namesWithBlankSecondary(): List<String>

    /**
     * 老版本升级后补次要肌群：只动「内置动作且当前为空」的行，
     * 用户自己改过的一律不覆盖。
     */
    @Query(
        """
        UPDATE exercises SET secondaryMuscles = :secondary
        WHERE name = :name AND isCustom = 0 AND secondaryMuscles = ''
        """
    )
    suspend fun backfillSecondaryMuscles(name: String, secondary: String)
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

    @Query("UPDATE template_folders SET rotation = :rotation WHERE id = :id")
    suspend fun setFolderRotation(id: Long, rotation: Boolean)

    @Query("UPDATE template_folders SET restDays = :restDays WHERE id = :id")
    suspend fun setFolderRestDays(id: Long, restDays: Int)

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

    /**
     * PR 计算的原始行。
     *
     * 不在这里做聚合：1RM 是多条公式的加权融合（见 OneRepMax），SQL 表达不出来。
     * 也不做成 Flow：原来的写法是「任意一组数据变更就重跑整个历史的三表 join」，
     * 训练中每填一次重量都会触发。现在是一次性查询，由仓库层缓存 + 显式失效。
     */
    @Query(
        """
        SELECT e.id AS exerciseId, e.name AS exerciseName, e.pattern AS pattern,
               sl.weightKg AS weightKg, sl.reps AS reps
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE sl.completed = 1 AND sl.isWarmup = 0 AND sl.reps > 0
        """
    )
    suspend fun workingSetsForPr(): List<PrSetRow>

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

    /**
     * 该动作所有已完成工作组（含 PR 组，排除热身）。
     *
     * 不在 SQL 里排序：最佳组要按估算 1RM 挑，
     * 而 `ORDER BY weightKg DESC` 会让 100 kg × 1 赢过 95 kg × 8。
     */
    @Query(
        """
        SELECT * FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE se.exerciseId = :exerciseId AND sl.completed = 1
          AND sl.isWarmup = 0 AND sl.reps > 0 AND s.endTime IS NOT NULL
        """
    )
    suspend fun workingSetsForExercise(exerciseId: Long): List<SetLog>

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

    // ---- 历史搜索 / 进阶 / 热力图 ----

    /** 含有匹配动作名的训练 id（历史页按动作筛选） */
    @Query(
        """
        SELECT DISTINCT se.sessionId FROM session_exercises se
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE e.name LIKE '%' || :keyword || '%'
        """
    )
    suspend fun sessionIdsMatchingExercise(keyword: String): List<Long>

    /**
     * 某动作的 e1RM 原始行，按时间升序。
     *
     * 不在这里做聚合：1RM 要按这条曲线查表换算（见 `RepMaxCurve`），
     * 曲线不覆盖时还要回落到七公式加权融合，SQL 表达不出来；
     * 也不在这里按组聚合，一次训练里的高低起伏由调用方取最好一组。
     */
    @Query(
        """
        SELECT s.id AS sessionId, COALESCE(s.activeStartedAt, s.startTime) AS startTime,
               sl.weightKg AS weightKg, sl.reps AS reps
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE se.exerciseId = :exerciseId AND sl.completed = 1 AND sl.isWarmup = 0
          AND sl.reps > 0 AND s.endTime IS NOT NULL
        ORDER BY s.startTime
        """
    )
    suspend fun e1rmRowsForExercise(exerciseId: Long): List<E1rmRow>

    /**
     * 全库「每动作 × 每次数」的最重一组，供 rep-max 曲线校准使用。
     *
     * 在 SQL 里就按 (动作, 次数) 聚合，不把全表拉回 Kotlin 再分桶：
     * 练了两年的人 set_logs 上万行，逐行映射的开销远大于 GROUP BY；
     * 每动作每次数只出一行，几百行就到顶。
     */
    @Query(
        """
        SELECT e.id AS exerciseId, e.pattern AS pattern, sl.reps AS reps,
               MAX(sl.weightKg) AS weightKg, COUNT(*) AS setCount
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE sl.completed = 1 AND sl.isWarmup = 0 AND sl.weightKg > 0
          AND sl.reps >= 1 AND sl.reps <= 12 AND s.endTime IS NOT NULL
        GROUP BY e.id, e.pattern, sl.reps
        """
    )
    suspend fun repMaxPoints(): List<RepMaxRow>

    /**
     * 最近一次「已完成」训练里某动作的工作组。
     * 进阶建议只看上一次，看得太多反而会把中断前的旧记录算进来。
     */
    @Query(
        """
        SELECT sl.* FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        WHERE se.exerciseId = :exerciseId AND s.endTime IS NOT NULL AND sl.completed = 1
          AND sl.isWarmup = 0
          AND s.startTime = (
              SELECT MAX(s2.startTime) FROM sessions s2
              INNER JOIN session_exercises se2 ON se2.sessionId = s2.id
              WHERE se2.exerciseId = :exerciseId AND s2.endTime IS NOT NULL
          )
        ORDER BY sl.setNumber
        """
    )
    suspend fun lastSessionSets(exerciseId: Long): List<SetLog>

    /** 窗口期内每个动作练了多少组、最后一次是什么时候（热力图用） */
    @Query(
        """
        SELECT e.primaryMuscle AS primaryMuscle, e.secondaryMuscles AS secondaryMuscles,
               COUNT(*) AS sets, MAX(s.startTime) AS lastTrainedAt
        FROM set_logs sl
        INNER JOIN session_exercises se ON sl.sessionExerciseId = se.id
        INNER JOIN sessions s ON se.sessionId = s.id
        INNER JOIN exercises e ON se.exerciseId = e.id
        WHERE s.endTime IS NOT NULL AND sl.completed = 1 AND sl.isWarmup = 0
          AND s.startTime >= :since
        GROUP BY se.exerciseId
        """
    )
    suspend fun muscleSetRows(since: Long): List<MuscleSetRow>

    /**
     * 某个训练计划（= 一个轮换文件夹里的模板集合）最近一次练到哪。
     *
     * 只在这个集合里找，回的是「模板 id + 开始时间」两列：
     * 前者用来定位练到第几个，后者用来算离那天过了几天（休息日靠它推）。
     */
    @Query(
        """
        SELECT templateId, startTime FROM sessions
        WHERE endTime IS NOT NULL AND templateId IN (:templateIds)
        ORDER BY startTime DESC LIMIT 1
        """
    )
    suspend fun lastSessionForTemplates(templateIds: List<Long>): LastPlanSession?
}

/** 计划推算用的最小投影：练的哪个模板、什么时候练的 */
data class LastPlanSession(
    val templateId: Long,
    val startTime: Long,
)

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

    /**
     * 训练中换动作：只改 exerciseId。
     *
     * 组记录、组号、排序、超级组编号全部保留 —— 这正是「器械被占」时想要的效果：
     * 换成同类动作，已经打过的勾不用重打。
     */
    @Query("UPDATE session_exercises SET exerciseId = :exerciseId WHERE id = :id")
    suspend fun replaceExercise(id: Long, exerciseId: Long)

    @Query("UPDATE session_exercises SET supersetGroup = :group WHERE id = :id")
    suspend fun setSupersetGroup(id: Long, group: Int?)

    /** 新超级组编号：当前最大值 +1，同一个训练内不重复 */
    @Query("SELECT COALESCE(MAX(supersetGroup), 0) + 1 FROM session_exercises WHERE sessionId = :sessionId")
    suspend fun nextSupersetGroup(sessionId: Long): Int

    @Query("SELECT * FROM session_exercises WHERE sessionId = :sessionId ORDER BY sortOrder, id")
    fun observeForSession(sessionId: Long): kotlinx.coroutines.flow.Flow<List<SessionExercise>>
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
    version = 14,
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

        /**
         * v11 -> v12：
         * - 动作补次要肌群（热力图折算用）
         * - 模板/训练动作把「目标次数」从文本拆成 min/max 两列（进阶判断要用整数比较）
         * - 进阶策略、超级组编号
         * - 模板文件夹可标记为「轮换计划」
         *
         * 旧文本列保留（Room 不允许悄悄少列），迁移时把 `8-12` 解析后回填到新列。
         * 解析分两步做：先收集，再写回 —— 一边遍历游标一边 UPDATE 同一张表
         * 在部分设备上会让游标错位。
         */
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE exercises ADD COLUMN secondaryMuscles TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE template_folders ADD COLUMN rotation INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE template_exercises ADD COLUMN targetRepsMin INTEGER")
                db.execSQL("ALTER TABLE template_exercises ADD COLUMN targetRepsMax INTEGER")
                db.execSQL(
                    "ALTER TABLE template_exercises ADD COLUMN progression INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE template_exercises ADD COLUMN supersetGroup INTEGER")
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN plannedRepsMin INTEGER")
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN plannedRepsMax INTEGER")
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN supersetGroup INTEGER")

                backfillRepRange(
                    db, "template_exercises", "targetReps", "targetRepsMin", "targetRepsMax"
                )
                backfillRepRange(
                    db, "session_exercises", "plannedReps", "plannedRepsMin", "plannedRepsMax"
                )
            }
        }

        /**
         * v12 -> v13：`exercises.pattern` —— rep-max 曲线的分档，见 `LiftPattern`。
         *
         * 同一档次数下不同动作离 1RM 的距离不一样（硬拉最远、孤立动作最近），
         * 之前全库共用一条曲线，硬拉 10RM 会被低估十几个百分点。
         *
         * 回填按「动作名关键词 → 分类兜底」推断，和新建自定义动作走同一套规则
         * （[com.yfantasyxx.data.LiftPattern.infer]），不另写一份。
         * 同样是先收集再写回：边遍历游标边 UPDATE 同一张表会让游标错位。
         */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN pattern INTEGER NOT NULL DEFAULT 0")

                val inferred = mutableListOf<Pair<Long, Int>>()
                db.query("SELECT id, name, category, equipment FROM exercises").use { cursor ->
                    while (cursor.moveToNext()) {
                        val pattern = com.yfantasyxx.data.LiftPattern
                            .infer(cursor.getString(1), cursor.getString(2), cursor.getString(3))
                        if (pattern != com.yfantasyxx.data.LiftPattern.UNKNOWN) {
                            inferred += cursor.getLong(0) to pattern.value
                        }
                    }
                }
                // pattern.value 是枚举里的常量整数，拼进 SQL 不存在注入面
                inferred.forEach { (id, value) ->
                    db.execSQL("UPDATE exercises SET pattern = $value WHERE id = $id")
                }
            }
        }

        /**
         * v13 -> v14：训练计划增加「每轮休息天数」。
         *
         * 默认 0 = 不插休息日，也就是老行为（A→B→C→A 一路循环）。
         * 已有用户里开过轮换的人不会被塞进休息日，要休息自己设。
         */
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE template_folders ADD COLUMN restDays INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** 把旧的自由文本次数解析成 min/max（`8-12` → 8,12；`10` → 10,10） */
        private fun backfillRepRange(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
            source: String,
            minColumn: String,
            maxColumn: String,
        ) {
            val parsed = mutableListOf<Pair<Long, com.yfantasyxx.data.RepRange>>()
            db.query("SELECT id, $source FROM $table WHERE $source IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val range = com.yfantasyxx.data.RepRange.parse(cursor.getString(1))
                        ?: continue
                    parsed += id to range
                }
            }
            // 值来自正则匹配出的整数，直接拼进 SQL 是安全的（不存在注入面）
            parsed.forEach { (id, range) ->
                db.execSQL(
                    "UPDATE $table SET $minColumn = ${range.min}, $maxColumn = ${range.max} WHERE id = $id"
                )
            }
        }

        /**
         * v10 -> v11：sessions 的 startTime / endTime 建索引。
         * 统计查询全部按 startTime 范围过滤，草稿查询按 endTime IS NULL 过滤，
         * 没有索引时两者都是全表扫描。索引名必须是 Room 的默认命名格式
         * （index_<表>_<列>），否则启动时 schema 校验会报 IllegalStateException。
         */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_startTime` ON `sessions` (`startTime`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_endTime` ON `sessions` (`endTime`)"
                )
            }
        }

        /** v9 -> v10：训练动作记录模板设定的目标次数 */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_exercises ADD COLUMN plannedReps TEXT")
            }
        }

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
