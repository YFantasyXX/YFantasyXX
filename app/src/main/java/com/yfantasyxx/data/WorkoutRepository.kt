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
import com.yfantasyxx.data.seed.ExerciseSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

    // ---------- 动作库 ----------

    fun observeExercises(keyword: String = "", category: String? = null): Flow<List<Exercise>> =
        exerciseDao.observeFiltered(keyword.trim(), category)

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
    ): Long {
        val id = if (templateId == null) {
            templateDao.insert(WorkoutTemplate(name = name.trim(), note = note.trim()))
        } else {
            templateDao.update(WorkoutTemplate(id = templateId, name = name.trim(), note = note.trim()))
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
                )
            )
        }
        return id
    }

    suspend fun deleteTemplate(template: WorkoutTemplate) = templateDao.delete(template)

    data class TemplateExerciseDraft(
        val exerciseId: Long,
        val targetSets: Int,
        val targetReps: String,
    )

    // ---------- 训练进行 ----------

    fun observeActiveSession(): Flow<ActiveSessionSummary?> = sessionDao.observeActiveSummary()

    suspend fun getActiveDraft(): List<SessionExerciseWithSets>? =
        sessionDao.getActiveDraft()?.exercises

    /** 开始一次新训练（可从模板带入动作）；已有进行中的草稿时直接复用 */
    suspend fun startSession(templateId: Long? = null, name: String = ""): Long {
        val existing = sessionDao.getActiveDraft()
        if (existing != null) {
            // 复用草稿；若是空草稿且指定了模板，则把模板动作补进去
            if (templateId != null && existing.exercises.isEmpty()) {
                applyTemplate(existing.session.id, templateId)
            }
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
                )
            )
            setLogDao.insertAll(
                (1..item.templateExercise.targetSets).map { n ->
                    SetLog(
                        sessionExerciseId = seId,
                        setNumber = n,
                        completed = false,
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
        setLogDao.insertAll(
            (1..targetSets).map { n ->
                SetLog(sessionExerciseId = seId, setNumber = n, completed = false)
            }
        )
        return seId
    }

    suspend fun removeSessionExercise(id: Long) = sessionExerciseDao.delete(id)

    suspend fun addSet(sessionExerciseId: Long): Long {
        val n = setLogDao.maxSetNumber(sessionExerciseId) + 1
        return setLogDao.insert(SetLog(sessionExerciseId = sessionExerciseId, setNumber = n))
    }

    suspend fun updateSet(log: SetLog) = setLogDao.update(log)

    suspend fun deleteSet(id: Long) = setLogDao.delete(id)

    suspend fun getSet(id: Long): SetLog? = setLogDao.getById(id)

    /** 完成训练 */
    suspend fun finishSession(id: Long, name: String, note: String) =
        sessionDao.finish(id, System.currentTimeMillis(), name.trim(), note.trim())

    /** 放弃草稿 */
    suspend fun discardSession(id: Long) = sessionDao.delete(id)

    suspend fun getSessionDetails(id: Long): List<SessionExerciseWithSets>? =
        sessionDao.getWithDetails(id)?.exercises

    fun observeSessionDetails(id: Long): Flow<List<SessionExerciseWithSets>> =
        sessionDao.observeSession(id).map { it?.exercises ?: emptyList() }

    suspend fun getSession(id: Long): WorkoutSession? = sessionDao.getById(id)

    suspend fun updateSessionName(id: Long, name: String, note: String) =
        sessionDao.rename(id, name, note)

    // ---------- 历史 ----------

    fun observeHistory(): Flow<List<SessionSummary>> = sessionDao.observeSummaries()

    suspend fun deleteSession(id: Long) = sessionDao.delete(id)

    // ---------- 统计 ----------

    suspend fun volumeByWeek(since: Long, weekMs: Long = 7L * 24 * 3600 * 1000): List<BucketVolume> =
        sessionDao.volumeByWeek(since, weekMs)

    suspend fun volumeByCategory(since: Long): List<CategoryVolume> =
        sessionDao.volumeByCategory(since)

    fun observePersonalRecords(): Flow<List<ExercisePr>> = sessionDao.observePersonalRecords()

    fun observeSessionCount(since: Long): Flow<Int> = sessionDao.observeSessionCount(since)

    fun observeTotalVolume(since: Long): Flow<Float> = sessionDao.observeTotalVolume(since)

    /** 某动作最近一次完成记录（作为"上次数据"参考） */
    suspend fun lastPerformance(exerciseId: Long): List<SetLog> =
        sessionDao.recentSetsForExercise(exerciseId, limit = 5)

    // ---------- 身体数据 ----------

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
            details.exercises.forEach { item ->
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
