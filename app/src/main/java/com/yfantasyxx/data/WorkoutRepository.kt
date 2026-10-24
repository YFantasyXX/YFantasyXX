package com.yfantasyxx.data

import com.yfantasyxx.data.db.ActiveSessionSummary
import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.data.db.BodyMetric
import com.yfantasyxx.data.db.BucketVolume
import com.yfantasyxx.data.db.CategoryVolume
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.ExercisePr
import com.yfantasyxx.data.db.SessionExercise
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.data.db.TemplateExercise
import com.yfantasyxx.data.db.TemplateWithExercises
import com.yfantasyxx.data.db.WorkoutSession
import com.yfantasyxx.data.db.WorkoutTemplate
import androidx.room.withTransaction
import com.yfantasyxx.data.seed.ExerciseSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yfantasyxx.data.db.TemplateFolder

/**
 * 统一数据仓库：动作库 / 模板 / 训练记录 / 身体数据
 */
class WorkoutRepository(private val db: AppDatabase) {

    private val exerciseDao = db.exerciseDao()
    private val templateDao = db.templateDao()
    private val sessionDao = db.sessionDao()
    private val sessionExerciseDao = db.sessionExerciseDao()
    private val setLogDao = db.setLogDao()
    private val bodyMetricDao = db.bodyMetricDao()

    // ---------- 统计数据的失效信号 ----------

    /**
     * 数据版本号：任何「会改变统计结果」的写入都把它 +1。
     *
     * 统计页据此重算，而不是让 PR 去 observe 整张 set_logs 表——
     * 后者意味着训练中每填一次重量都要重跑一遍全历史聚合，
     * 而一次训练里真正会改变 PR 的时刻只有「勾选完成 / 删除 / 结束训练」这几次。
     */
    private val dataVersion = MutableStateFlow(0)

    fun observeDataVersion(): StateFlow<Int> = dataVersion

    /** PR 列表缓存；[invalidateStats] 时置空 */
    @Volatile
    private var prCache: List<ExercisePr>? = null

    private val prMutex = Mutex()

    /** 写入 set_logs / sessions 之后调用 */
    private fun invalidateStats() {
        prCache = null
        dataVersion.value += 1
    }

    // ---------- 动作库 ----------

    fun observeExercises(
        keyword: String = "",
        category: String? = null,
        equipment: String? = null,
    ): Flow<List<Exercise>> = exerciseDao.observeFiltered(keyword.trim(), category, equipment)

    /** 数据库里实际存在的分类 / 器械，供筛选条动态展示 */
    fun observeExerciseCategories(): Flow<List<String>> = exerciseDao.observeCategories()

    fun observeExerciseEquipments(category: String? = null): Flow<List<String>> =
        exerciseDao.observeEquipments(category)

    suspend fun getExercise(id: Long): Exercise? = exerciseDao.getById(id)

    suspend fun addCustomExercise(
        name: String,
        category: String,
        equipment: String,
        muscle: String,
        note: String = "",
    ): Long = exerciseDao.insert(
        Exercise(
            name = name.trim(),
            category = category,
            equipment = equipment,
            primaryMuscle = muscle.trim(),
            note = note.trim(),
            isCustom = true,
        )
    )

    suspend fun updateExercise(exercise: Exercise) = exerciseDao.update(exercise)

    suspend fun deleteCustomExercise(id: Long) = exerciseDao.deleteCustom(id)

    /** 首次启动写入种子数据 */
    suspend fun seedIfEmpty() {
        if (exerciseDao.count() == 0) {
            exerciseDao.insertAll(ExerciseSeed.exercises)
        }
    }

    /** 启动清理：历史版本异常退出可能留下多个未完成草稿 */
    /** 清理多余草稿（含其动作与组），只保留最近一个 */
    suspend fun cleanupStaleDrafts() {
        sessionDao.deleteStaleDraftSets()
        sessionDao.deleteStaleDraftExercises()
        sessionDao.deleteStaleDraftSessions()
        invalidateStats()
    }

    // ---------- 模板 ----------

    fun observeTemplates(): Flow<List<WorkoutTemplate>> = templateDao.observeAll()

    fun observeTemplatesWithExercises(): Flow<List<TemplateWithExercises>> =
        templateDao.observeAllWithExercises()

    suspend fun getTemplate(id: Long): TemplateWithExercises? = templateDao.getWithExercises(id)

    /** 新建/更新模板及其动作条目 */
    suspend fun saveTemplate(
        templateId: Long?,
        name: String,
        note: String,
        items: List<TemplateExerciseDraft>,
        folderId: Long? = null,
    ): Long {
        val id = if (templateId == null) {
            templateDao.insert(
                WorkoutTemplate(
                    name = name.trim(),
                    note = note.trim(),
                    folderId = folderId,
                    sortOrder = 0,
                )
            )
        } else {
            val current = templateDao.getWithExercises(templateId)?.template
            templateDao.update(
                WorkoutTemplate(
                    id = templateId,
                    name = name.trim(),
                    note = note.trim(),
                    folderId = current?.folderId ?: folderId,
                    sortOrder = current?.sortOrder ?: 0,
                )
            )
            templateId
        }
        templateDao.deleteExercisesOfTemplate(id)
        items.forEachIndexed { index, draft ->
            templateDao.insertExercise(
                TemplateExercise(
                    templateId = id,
                    exerciseId = draft.exerciseId,
                    sortOrder = index,
                    targetSets = draft.targetSets,
                    targetReps = draft.targetReps,
                    targetWeightKg = draft.targetWeightKg,
                    isWarmup = draft.isWarmup,
                    restSeconds = draft.restSeconds,
                )
            )
        }
        return id
    }

    suspend fun deleteTemplate(template: WorkoutTemplate) = templateDao.delete(template)

    // ---------- 模板文件夹 ----------

    fun observeFolders(): Flow<List<TemplateFolder>> = templateDao.observeFolders()

    /** 首页快捷开始用：排除隐藏文件夹里的模板 */
    fun observeHomeTemplates(): Flow<List<WorkoutTemplate>> = templateDao.observeHomeTemplates()

    suspend fun setFolderShowOnHome(id: Long, show: Boolean) =
        templateDao.setFolderShowOnHome(id, show)

    suspend fun createFolder(name: String): Long =
        templateDao.insertFolder(TemplateFolder(name = name.trim()))

    suspend fun renameFolder(id: Long, name: String) =
        templateDao.renameFolder(id, name.trim())

    suspend fun deleteFolder(id: Long) {
        templateDao.clearFolder(id)
        templateDao.deleteFolder(id)
    }

    /** 把模板移动到指定文件夹的指定位置 */
    suspend fun moveTemplate(templateId: Long, folderId: Long?, order: Int) =
        templateDao.updateTemplatePlacement(templateId, folderId, order)

    /** 批量重排某分组内的模板顺序 */
    suspend fun reorderTemplates(folderId: Long?, orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            templateDao.updateTemplatePlacement(id, folderId, index)
        }
    }

    data class TemplateExerciseDraft(
        val exerciseId: Long,
        val targetSets: Int,
        val targetReps: String,
        val targetWeightKg: Float? = null,
        val isWarmup: Boolean = false,
        val restSeconds: Int? = null,
    )

    // ---------- 训练进行 ----------

    fun observeActiveSession(): Flow<ActiveSessionSummary?> = sessionDao.observeActiveSummary()

    suspend fun getActiveDraft(): List<SessionExerciseWithSets>? =
        sessionDao.getActiveDraft()?.exercises?.sortedBySessionOrder()

    /** 进行中草稿的 session id（草稿可能还没有任何动作） */
    suspend fun getActiveSessionId(): Long? =
        sessionDao.getActiveDraft()?.session?.id

    /** 开始一次新训练（可从模板带入动作）；已有进行中的草稿时直接复用 */
    suspend fun startSession(templateId: Long? = null, name: String = ""): Long {
        val existing = sessionDao.getActiveDraft()
        if (existing != null) {
            val hasCompleted = existing.exercises.any { item -> item.sets.any { it.completed } }
            val sameTemplate = existing.session.templateId == templateId
            if (hasCompleted || sameTemplate || templateId == null) {
                // 继续上次未完成的训练
                if (templateId != null && existing.exercises.isEmpty()) {
                    applyTemplate(existing.session.id, templateId)
                }
                return existing.session.id
            }
            // 上次只是打开没练，且这次换了个模板：清空旧内容按新模板重建
            existing.exercises.forEach { removeSessionExercise(it.sessionExercise.id) }
            sessionDao.setTemplateId(existing.session.id, templateId)
            applyTemplate(existing.session.id, templateId)
            return existing.session.id
        }
        val sessionId = sessionDao.insert(
            WorkoutSession(
                name = name,
                templateId = templateId,
                startTime = System.currentTimeMillis(),
                endTime = null,
            )
        )
        if (templateId != null) applyTemplate(sessionId, templateId)
        return sessionId
    }

    /** 把模板中的动作（含目标组数）写入训练 */
    private suspend fun applyTemplate(sessionId: Long, templateId: Long) {
        val tpl = templateDao.getWithExercises(templateId) ?: return
        tpl.items.forEach { item ->
            val seId = sessionExerciseDao.insert(
                SessionExercise(
                    sessionId = sessionId,
                    exerciseId = item.exercise.id,
                    sortOrder = item.templateExercise.sortOrder,
                    restSeconds = item.templateExercise.restSeconds,
                    plannedSets = item.templateExercise.targetSets,
                    plannedReps = item.templateExercise.targetReps,
                )
            )
            // 重量优先用模板设定，其次沿用该动作上次的表现
            val weight = item.templateExercise.targetWeightKg
                ?: lastPerformance(item.exercise.id).firstOrNull()?.weightKg
                ?: 0f
            setLogDao.insertAll(
                (1..item.templateExercise.targetSets).map { n ->
                    SetLog(
                        sessionExerciseId = seId,
                        setNumber = n,
                        completed = false,
                        weightKg = weight,
                        isWarmup = item.templateExercise.isWarmup,
                    )
                }
            )
        }
    }

    suspend fun addExerciseToSession(sessionId: Long, exerciseId: Long, targetSets: Int = 3): Long {
        val order = sessionExerciseDao.nextSortOrder(sessionId)
        val seId = sessionExerciseDao.insert(
            SessionExercise(sessionId = sessionId, exerciseId = exerciseId, sortOrder = order)
        )
        // 沿用该动作上次的重量，省得每次从 0 开始调
        val lastWeight = lastPerformance(exerciseId).firstOrNull()?.weightKg ?: 0f
        setLogDao.insertAll(
            (1..targetSets).map { n ->
                SetLog(
                    sessionExerciseId = seId,
                    setNumber = n,
                    completed = false,
                    weightKg = lastWeight,
                )
            }
        )
        return seId
    }

    suspend fun removeSessionExercise(id: Long) {
        sessionExerciseDao.delete(id)
        invalidateStats()
    }

    suspend fun addSet(sessionExerciseId: Long): Long {
        val n = setLogDao.maxSetNumber(sessionExerciseId) + 1
        return setLogDao.insert(SetLog(sessionExerciseId = sessionExerciseId, setNumber = n))
    }

    suspend fun updateSet(log: SetLog) {
        setLogDao.update(log)
        // 重量 / 次数 / 是否完成都走这里，是 PR 失效最主要的入口
        invalidateStats()
    }

    suspend fun deleteSet(id: Long) {
        setLogDao.delete(id)
        invalidateStats()
    }

    /** 删除某一组并把剩余组重新编号，避免出现 1、2、4 这样的空缺 */
    suspend fun deleteSetAndRenumber(id: Long) {
        val target = setLogDao.getById(id) ?: return
        val sessionExerciseId = target.sessionExerciseId
        setLogDao.delete(id)
        val remaining = setLogDao.getForSessionExercise(sessionExerciseId).sortedBy { it.setNumber }
        remaining.forEachIndexed { index, item ->
            if (item.setNumber != index + 1) {
                setLogDao.update(item.copy(setNumber = index + 1))
            }
        }
        invalidateStats()
    }

    suspend fun getSet(id: Long): SetLog? = setLogDao.getById(id)

    /** 完成训练 */
    /** 第一组完成时记录实际开始计时时刻 */
    suspend fun markSessionStarted(id: Long, time: Long = System.currentTimeMillis()) =
        sessionDao.markStarted(id, time)

    suspend fun finishSession(id: Long, name: String, note: String) {
        sessionDao.finish(id, System.currentTimeMillis(), name.trim(), note.trim())
        // endTime 从 null 变成有值，周容量 / 分类容量的统计口径会跟着变
        invalidateStats()
    }

    /** 放弃草稿 */
    suspend fun discardSession(id: Long) {
        sessionDao.delete(id)
        invalidateStats()
    }

    suspend fun getSessionDetails(id: Long): List<SessionExerciseWithSets>? =
        sessionDao.getWithDetails(id)?.exercises?.sortedBySessionOrder()

    fun observeSessionDetails(id: Long): Flow<List<SessionExerciseWithSets>> =
        sessionDao.observeSession(id).map {
            it?.exercises?.sortedBySessionOrder() ?: emptyList()
        }

    suspend fun getSession(id: Long): WorkoutSession? = sessionDao.getById(id)

    suspend fun updateSessionName(id: Long, name: String, note: String) =
        sessionDao.rename(id, name, note)

    // ---------- 历史 ----------

    fun observeHistory(): Flow<List<SessionSummary>> = sessionDao.observeSummaries()

    suspend fun deleteSession(id: Long) {
        sessionDao.delete(id)
        invalidateStats()
    }

    // ---------- 统计 ----------

    suspend fun volumeByWeek(since: Long, weekMs: Long = 7L * 24 * 3600 * 1000): List<BucketVolume> =
        sessionDao.volumeByWeek(since, weekMs)

    suspend fun volumeByCategory(since: Long): List<CategoryVolume> =
        sessionDao.volumeByCategory(since)

    /**
     * 各动作 PR，按估算 1RM 从高到低。
     *
     * 计算一次后缓存，直到 [invalidateStats] 把缓存置空。
     * [refresh] 为 true 时强制重算。
     */
    suspend fun personalRecords(refresh: Boolean = false): List<ExercisePr> = prMutex.withLock {
        if (!refresh) prCache?.let { return@withLock it }
        computePersonalRecords().also { prCache = it }
    }

    private suspend fun computePersonalRecords(): List<ExercisePr> {
        val rows = sessionDao.workingSetsForPr()
        val acc = HashMap<Long, PrAcc>()
        rows.forEach { row ->
            val item = acc.getOrPut(row.exerciseId) {
                PrAcc(row.exerciseId, row.exerciseName)
            }
            val volume = row.weightKg * row.reps
            if (volume > item.bestVolume) {
                item.bestVolume = volume
                item.volumeWeight = row.weightKg
                item.volumeReps = row.reps
            }
            val e1rm = OneRepMax.estimate(row.weightKg, row.reps)
            if (e1rm != null && e1rm > item.estOneRm) {
                item.estOneRm = e1rm
                item.bestWeight = row.weightKg
                item.bestReps = row.reps
            }
        }
        return acc.values
            .filter { it.bestVolume > 0f }
            .map { it.toPr() }
            .sortedWith(
                compareByDescending<ExercisePr> { it.estOneRm ?: -1f }
                    .thenByDescending { it.bestVolume }
            )
    }

    /** 聚合中间态：一趟扫完同时维护「最高 e1RM」和「最大容量」两个口径 */
    private class PrAcc(val exerciseId: Long, val exerciseName: String) {
        var estOneRm = 0f
        var bestWeight = 0f
        var bestReps = 0
        var bestVolume = 0f
        var volumeWeight = 0f
        var volumeReps = 0

        fun toPr(): ExercisePr {
            val hasE1rm = estOneRm > 0f
            return ExercisePr(
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                estOneRm = if (hasE1rm) estOneRm else null,
                // 没有可估算的组（全是 12 次以上）时，用最大容量那组兜底展示
                bestWeightKg = if (hasE1rm) bestWeight else volumeWeight,
                bestReps = if (hasE1rm) bestReps else volumeReps,
                bestVolume = bestVolume,
            )
        }
    }

    fun observeSessionCount(since: Long): Flow<Int> = sessionDao.observeSessionCount(since)

    fun observeTotalVolume(since: Long): Flow<Float> = sessionDao.observeTotalVolume(since)

    /** 某动作最近一次完成记录（作为"上次数据"参考） */
    suspend fun lastPerformance(exerciseId: Long): List<SetLog> =
        sessionDao.recentSetsForExercise(exerciseId, limit = 5)

    // ---------- 身体数据 ----------

    /** 训练内拖动排序：按给定顺序重写 sortOrder */
    suspend fun reorderSessionExercises(sessionId: Long, orderedIds: List<Long>) = db.withTransaction {
        // 单事务写入：否则逐条 UPDATE 会触发 Flow 发射中间态，覆盖本地排序
        orderedIds.forEachIndexed { index, id ->
            sessionExerciseDao.updateSortOrder(id, index)
        }
    }

    /**
     * 该动作历史最佳一组（用于动作库 PR 展示）。
     *
     * 按估算 1RM 挑，不再按重量挑：`ORDER BY weightKg DESC` 会让
     * 100 kg × 1 赢过 95 kg × 8，可后者显然更强。
     * 算不出 1RM（次数 > 12）的组排在后面，只有全都不算不出来时才轮到它们。
     */
    suspend fun bestSetFor(exerciseId: Long): SetLog? {
        val sets = sessionDao.workingSetsForExercise(exerciseId)
        return sets.maxWithOrNull(
            compareBy<SetLog> { OneRepMax.estimate(it.weightKg, it.reps) ?: -1f }
                .thenBy { it.weightKg * it.reps }
        )
    }

    /** 训练动作（可能来自模板）指定的休息时长 */
    suspend fun sessionRestSeconds(sessionExerciseId: Long): Int? =
        sessionExerciseDao.restSecondsOf(sessionExerciseId)

    /** 动作自定义的休息时长（null 表示用全局默认） */
    suspend fun restSecondsFor(exerciseId: Long): Int? = exerciseDao.getById(exerciseId)?.restSeconds

    fun observeBodyMetrics(): Flow<List<BodyMetric>> = bodyMetricDao.observeAll()

    fun observeLatestWeight(): Flow<Float?> =
        bodyMetricDao.observeAll().map { list -> list.firstOrNull()?.weightKg }

    suspend fun saveWeight(date: Long, weightKg: Float, bodyFat: Float? = null) {
        bodyMetricDao.upsert(BodyMetric(date = date, weightKg = weightKg, bodyFatPercent = bodyFat))
    }

    suspend fun deleteBodyMetric(id: Long) = bodyMetricDao.delete(id)

    // ---------- CSV 导出 ----------

    /** 导出全部训练记录为 CSV 文本 */
    suspend fun buildCsvExport(): String {
        val sb = StringBuilder()
        sb.append("date,exercise,category,sets,reps,weight_kg,volume_kg\n")
        sessionDao.observeSummaries().first().forEach { summary ->
            val details = sessionDao.getWithDetails(summary.id) ?: return@forEach
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(summary.startTime))
            details.exercises.sortedBySessionOrder().forEach { item ->
                item.sets.filter { it.completed }.sortedBy { it.setNumber }.forEach { set ->
                    sb.append(
                        "%s,%s,%s,%d,%d,%.1f,%.1f\n".format(
                            date,
                            escapeCsv(item.exercise.name),
                            item.exercise.category,
                            set.setNumber,
                            set.reps,
                            set.weightKg,
                            set.weightKg * set.reps,
                        )
                    )
                }
            }
        }
        return sb.toString()
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}

/**
 * Room 的 [androidx.room.Relation] 子查询是 `WHERE x IN (...)`，【没有 ORDER BY】，
 * 返回顺序由 SQLite 决定（实际就是插入顺序/rowid）。
 * 所以 sortOrder 虽然写进了库，读取时却没人理会——拖动排序会被立刻“还原”。
 * 统一在内存里按 sortOrder 排一遍，sortOrder 才真正生效。
 */
private fun List<SessionExerciseWithSets>.sortedBySessionOrder(): List<SessionExerciseWithSets> =
    sortedWith(
        compareBy({ it.sessionExercise.sortOrder }, { it.sessionExercise.id })
    )
