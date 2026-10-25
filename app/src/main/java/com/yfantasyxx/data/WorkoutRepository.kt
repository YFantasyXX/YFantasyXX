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
import kotlinx.coroutines.flow.combine
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

    /**
     * 当前训练计划（循环制）的状态；没启用轮换时为 null。
     *
     * 三路合并：数据版本（练完 / 删记录后要重算）、文件夹设定（开关与休息天数）、
     * 模板列表（改名 / 排序后提示要跟着变）。只挂在数据版本上的话，
     * 用户在模板页把休息天数从 0 改成 1，首页会一直显示旧节奏直到重启。
     */
    fun observePlan(): Flow<TrainingPlan?> = combine(
        dataVersion,
        templateDao.observeFolders(),
        templateDao.observeAll(),
    ) { _, folders, templates ->
        resolvePlan(folders, templates)
    }

    /** PR 列表缓存；[invalidateStats] 时置空 */
    @Volatile
    private var prCache: List<ExercisePr>? = null

    /**
     * rep-max 曲线校准结果（key 是 `LiftPattern.value`）；[invalidateStats] 时置空。
     *
     * 算一次要扫全库 set_logs 做 GROUP BY，不能每次算 e1RM 都跑——
     * 而一次训练里真正会改变校准的时刻只有「结束训练 / 改重量 / 删除」这几次。
     */
    @Volatile
    private var calibrationCache: Map<Int, PatternCalibration>? = null

    private val prMutex = Mutex()
    private val calibrationMutex = Mutex()

    /** 写入 set_logs / sessions 之后调用 */
    private fun invalidateStats() {
        prCache = null
        calibrationCache = null
        dataVersion.value += 1
    }

    // ---------- 动作库 ----------

    fun observeExercises(
        keyword: String = "",
        category: String? = null,
        equipment: String? = null,
    ): Flow<List<Exercise>> = exerciseDao.observeFiltered(keyword.trim(), category, equipment)

    /** 动作库总条数，不受筛选影响；界面用来显示「共 N 个」 */
    fun observeExerciseCount(): Flow<Int> = exerciseDao.observeCount()

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
            // 新建时就定档：否则这条动作的 e1RM 会一直走七公式兜底，
            // 和同一部位的内置动作算出来的口径对不上
            pattern = LiftPattern.infer(name.trim(), category, equipment).value,
            note = note.trim(),
            isCustom = true,
        )
    )

    suspend fun updateExercise(exercise: Exercise) {
        // 只有「名字 / 分类 / 器械」真的改了才重算分档：
        // 把「我的动作 1」改成「杠铃硬拉」，曲线应该换成硬拉类；
        // 但改个默认休息时长也重算的话，会把种子数据里手工纠正过的档位
        // （比如「哑铃上拉」被显式标成拉类）冲掉
        val existing = exerciseDao.getById(exercise.id)
        val renamed = existing == null ||
            existing.name != exercise.name ||
            existing.category != exercise.category ||
            existing.equipment != exercise.equipment
        val pattern = if (renamed) {
            LiftPattern.infer(exercise.name, exercise.category, exercise.equipment)
        } else {
            LiftPattern.fromValue(exercise.pattern)
        }
        exerciseDao.update(exercise.copy(pattern = pattern.value))
    }

    suspend fun deleteCustomExercise(id: Long) = exerciseDao.deleteCustom(id)

    /** 首次启动写入种子数据；老版本升级补齐新增的内置动作 */
    suspend fun seedIfEmpty() = seedMissing()

    /**
     * 增量补齐动作库。
     *
     * 只用 `count() == 0` 判断的话，老用户永远拿不到后来新增的动作——
     * 库里有 57 条，count 不是 0，整段跳过。所以改成按名字对比：
     * 缺的插入，已有的顺手补上次要肌群（只补空值，不覆盖用户改过的）。
     *
     * 整个循环只查一次名字表，插入走同一事务，是幂等的：
     * 每次冷启动跑一遍，第二次就全是 no-op。
     */
    suspend fun seedMissing() {
        val existing = exerciseDao.allNames().toHashSet()
        val missing = ExerciseSeed.exercises.filter { it.name !in existing }
        // 内置动作库现在 775 条，首次启动要一次插完；逐条提交在低端机上要几百毫秒
        if (missing.isNotEmpty()) {
            runCatching {
                db.withTransaction { exerciseDao.insertAll(missing) }
            }.onFailure {
                // @Insert 默认 ABORT：775 条里有一条撞上约束，整批回滚，
                // 用户的动作库就永远停在旧条数，而且启动协程没有上游 catch，
                // 现场一点痕迹都不留。退化成逐条，能进多少进多少。
                missing.forEach { item ->
                    runCatching { exerciseDao.insert(item) }
                }
            }
        }
        // 只给「次肌群还是空」的老行回填。动作库到 775 条之后，无条件回填等于
        // 每次冷启动跑 775 次空 UPDATE——`backfillSecondaryMuscles` 自带
        // `secondaryMuscles = ''` 条件，第二遍起全是 no-op，但每条仍是一次事务。
        val blank = exerciseDao.namesWithBlankSecondary()
        if (blank.isEmpty()) return
        val byName = ExerciseSeed.exercises.associateBy { it.name }
        db.withTransaction {
            blank.forEach { name ->
                val seed = byName[name] ?: return@forEach
                if (seed.secondaryMuscles.isNotEmpty()) {
                    exerciseDao.backfillSecondaryMuscles(name, seed.secondaryMuscles)
                }
            }
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
                    // 旧文本列继续写，方便回滚到旧版本时仍能看到目标次数
                    targetReps = draft.range.label,
                    targetRepsMin = draft.range.min,
                    targetRepsMax = draft.range.max,
                    targetWeightKg = draft.targetWeightKg,
                    isWarmup = draft.isWarmup,
                    restSeconds = draft.restSeconds,
                    progression = draft.progression.value,
                    supersetGroup = draft.supersetGroup,
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

    suspend fun setFolderRotation(id: Long, rotation: Boolean) =
        templateDao.setFolderRotation(id, rotation)

    /**
     * 保存训练计划设定（是否作为计划 + 每轮休息天数）。
     *
     * 两个字段一起写：先勾「启用」再设休息要分两次写入的话，
     * 首页会先闪一下「没休息日」的旧状态。
     */
    suspend fun savePlanConfig(id: Long, enabled: Boolean, restDays: Int) = db.withTransaction {
        templateDao.setFolderRotation(id, enabled)
        templateDao.setFolderRestDays(id, restDays.coerceIn(0, TrainingCycle.MAX_REST_DAYS))
    }

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
        val range: RepRange,
        val targetWeightKg: Float? = null,
        val isWarmup: Boolean = false,
        val restSeconds: Int? = null,
        val progression: ProgressionMode = ProgressionMode.OFF,
        val supersetGroup: Int? = null,
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
            val range = item.templateExercise.let {
                it.targetRepsMin?.let { min -> it.targetRepsMax?.let { max -> RepRange(min, max) } }
            } ?: RepRange.parseOrDefault(item.templateExercise.targetReps)
            val seId = sessionExerciseDao.insert(
                SessionExercise(
                    sessionId = sessionId,
                    exerciseId = item.exercise.id,
                    sortOrder = item.templateExercise.sortOrder,
                    restSeconds = item.templateExercise.restSeconds,
                    plannedSets = item.templateExercise.targetSets,
                    plannedReps = range.label,
                    plannedRepsMin = range.min,
                    plannedRepsMax = range.max,
                    supersetGroup = item.templateExercise.supersetGroup,
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

    /**
     * 往一次训练里加动作。
     *
     * [completed] 只对「编辑已完成的训练」有意义：那时候补录的动作不是草稿，
     * 新加的组直接算作已完成，否则会以灰色「未完成」的样子混在历史里，
     * 既不进容量也不进 PR。
     */
    suspend fun addExerciseToSession(
        sessionId: Long,
        exerciseId: Long,
        targetSets: Int = 3,
        completed: Boolean = false,
    ): Long {
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
                    completed = completed,
                    weightKg = lastWeight,
                )
            }
        )
        // 未完成的组不进统计，只有补录成已完成的才需要刷新缓存
        if (completed) invalidateStats()
        return seId
    }

    suspend fun removeSessionExercise(id: Long) {
        sessionExerciseDao.delete(id)
        invalidateStats()
    }

    /**
     * 训练中替换动作（器械被占时最常用）。
     *
     * 只改 exerciseId：已完成的组、组数、排序、超级组编号全部保留，
     * 等于「这个位置换成另一个动作接着练」。重量沿用原值不改，
     * 因为换成同类动作时重量通常接近，改了反而要多调一次。
     */
    suspend fun replaceSessionExercise(sessionExerciseId: Long, newExerciseId: Long) {
        sessionExerciseDao.replaceExercise(sessionExerciseId, newExerciseId)
        invalidateStats()
    }

    /** 把某动作和它下面的动作组成超级组（原来没有组就新开一个编号） */
    suspend fun linkSuperset(sessionId: Long, sessionExerciseId: Long, nextId: Long): Int {
        val group = sessionExerciseDao.nextSupersetGroup(sessionId)
        sessionExerciseDao.setSupersetGroup(sessionExerciseId, group)
        sessionExerciseDao.setSupersetGroup(nextId, group)
        return group
    }

    /** 退出超级组；同组不足两人时把剩下的也清掉，避免留下孤零零的编号 */
    suspend fun unlinkSuperset(sessionId: Long, sessionExerciseId: Long) {
        val members = sessionExerciseDao.getForSession(sessionId)
        val group = members.firstOrNull { it.id == sessionExerciseId }?.supersetGroup ?: return
        val rest = members.filter { it.supersetGroup == group && it.id != sessionExerciseId }
        sessionExerciseDao.setSupersetGroup(sessionExerciseId, null)
        if (rest.size < 2) {
            rest.forEach { sessionExerciseDao.setSupersetGroup(it.id, null) }
        }
    }

    suspend fun addSet(
        sessionExerciseId: Long,
        weightKg: Float = 0f,
        reps: Int = 0,
        completed: Boolean = false,
    ): Long {
        val n = setLogDao.maxSetNumber(sessionExerciseId) + 1
        val id = setLogDao.insert(
            SetLog(
                sessionExerciseId = sessionExerciseId,
                setNumber = n,
                weightKg = weightKg,
                reps = reps,
                completed = completed,
            )
        )
        if (completed) invalidateStats()
        return id
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
        // 结束一次训练才推进计划：这才是「这一轮练到哪」的权威时刻，
        // 中途放弃的草稿不该让计划往前走一天
        sessionDao.getById(id)?.let { advancePlanAfter(it) }
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

    /**
     * 删除一条训练记录。
     *
     * 如果删掉的是计划里的训练，游标要跟着退回去——否则「删了刚才那次」之后
     * 计划还停在下一天，等于白练了一次。做法是把游标清空，让读取时按剩下的历史重推。
     */
    suspend fun deleteSession(id: Long) {
        val templateId = sessionDao.getById(id)?.templateId
        sessionDao.delete(id)
        if (templateId != null) {
            val templates = templateDao.observeAll().first()
            templateDao.observeFolders().first()
                .filter { it.rotation }
                .filter { folder -> templates.any { it.id == templateId && it.folderId == folder.id } }
                .forEach { folder -> resetPlanProgress(folder.id) }
        }
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
        // 循环外取一次：这里是全库聚合的热路径，别每组去拿锁
        val curves = calibration()
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
            val pattern = LiftPattern.fromValue(row.pattern)
            val e1rm = OneRepMax.estimate(
                weightKg = row.weightKg,
                reps = row.reps,
                curve = curves[pattern.value] ?: pattern,
            )
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

    /** 该动作最近一次已完成训练的工作组（进阶建议只参考这一次） */
    suspend fun lastSessionSets(exerciseId: Long): List<SetLog> =
        sessionDao.lastSessionSets(exerciseId)

    /**
     * 各分档的 rep-max 曲线校准结果。
     *
     * 只包含真的攒出样本的档；没出现的档由调用方回落到 [LiftPattern] 原始表。
     * 算一次后缓存，[invalidateStats] 时作废。
     */
    suspend fun calibration(): Map<Int, PatternCalibration> {
        calibrationCache?.let { return it }
        return calibrationMutex.withLock {
            calibrationCache ?: RepMaxCalibration.calibrate(sessionDao.repMaxPoints())
                .also { calibrationCache = it }
        }
    }

    /** 某一档的曲线：有个人校准就用校准版，否则用群体默认表 */
    suspend fun curveFor(pattern: LiftPattern): RepMaxCurve {
        if (pattern == LiftPattern.UNKNOWN) return pattern
        return calibration()[pattern.value] ?: pattern
    }

    /**
     * 动作详情页用的曲线 + 由来。
     *
     * 校准是后台生效的，界面上必须给出「这个数按什么算的」——
     * 同一个动作在旧版本和新版本之间数字变了，用户没法判断是 bug 还是校准。
     * 这里把 [PatternCalibration] 一并带出去，界面要画「群体 vs 你的」对比。
     */
    suspend fun curveHint(exerciseId: Long): CurveHint {
        val pattern = patternOf(exerciseId)
        if (pattern == LiftPattern.UNKNOWN) {
            return CurveHint(pattern, "通用公式（七公式融合）", pattern, null)
        }
        val calibration = calibration()[pattern.value] ?: return CurveHint(
            pattern,
            "${pattern.label} rep-max 曲线（群体均值）",
            pattern,
            null,
        )
        return CurveHint(
            curve = calibration,
            text = "${pattern.label} rep-max 曲线 · 个人校准 " +
                "${calibration.actionCount} 个动作 / ${calibration.setCount} 组",
            pattern = pattern,
            calibration = calibration,
        )
    }

    /**
     * 某动作的 e1RM 曲线。
     *
     * 一次训练一个点：取该次里估算 1RM 最高的那一组。
     * 如果按组画，4 组递减会变成一串锯齿，看不出趋势。
     */
    suspend fun e1rmHistory(exerciseId: Long): List<com.yfantasyxx.data.db.E1rmPoint> {
        // 曲线按动作查一次，不要每组去 join exercises
        val curve = curveFor(patternOf(exerciseId))
        val rows = sessionDao.e1rmRowsForExercise(exerciseId)
        return rows.groupBy { it.sessionId }
            .mapNotNull { (sessionId, sets) ->
                val best = sets.mapNotNull { row ->
                    OneRepMax.estimate(row.weightKg, row.reps, curve)?.let { row to it }
                }.maxByOrNull { it.second } ?: return@mapNotNull null
                com.yfantasyxx.data.db.E1rmPoint(
                    sessionId = sessionId,
                    startTime = best.first.startTime,
                    e1rm = best.second,
                    weightKg = best.first.weightKg,
                    reps = best.first.reps,
                )
            }
            .sortedBy { it.startTime }
    }

    /**
     * 窗口期内各肌群的有效组数。
     *
     * 折算规则：主动作肌群算 1 组，次要肌群算 0.5 组。
     * 直接按「每个动作只算主肌群」会严重低估上肢推拉类动作的负荷。
     */
    suspend fun muscleLoads(since: Long): List<com.yfantasyxx.data.db.MuscleLoad> {
        val rows = sessionDao.muscleSetRows(since)
        val sets = HashMap<String, Float>()
        val lastAt = HashMap<String, Long>()
        rows.forEach { row ->
            val primary = row.primaryMuscle.trim()
            if (primary.isNotEmpty()) {
                sets[primary] = (sets[primary] ?: 0f) + row.sets
                lastAt[primary] = maxOf(lastAt[primary] ?: 0L, row.lastTrainedAt)
            }
            row.secondaryMuscles.split(',', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { muscle ->
                    sets[muscle] = (sets[muscle] ?: 0f) + row.sets * 0.5f
                    lastAt[muscle] = maxOf(lastAt[muscle] ?: 0L, row.lastTrainedAt)
                }
        }
        return sets.map { (muscle, count) ->
            com.yfantasyxx.data.db.MuscleLoad(
                muscle = muscle,
                sets = count,
                lastTrainedAt = lastAt[muscle] ?: 0L,
            )
        }.sortedByDescending { it.sets }
    }

    /** 历史搜索：含该动作名的训练 id */
    suspend fun sessionIdsMatchingExercise(keyword: String): Set<Long> =
        if (keyword.isBlank()) emptySet() else sessionDao.sessionIdsMatchingExercise(keyword).toSet()

    /**
     * 计划轮换的当前状态。
     *
     * 只认一个计划：开了轮换的文件夹里，排在最前面的那个（文件夹内模板按 sortOrder）。
     * 以前是把所有轮换文件夹摊平成一个长循环，但「每轮休息几天」是文件夹级别的设定，
     * 摊平之后休息日该插在哪就没有意义了。
     *
     * **进度以游标为准**（[TemplateFolder.cycleNextTemplateId]），拿不到游标才按历史推一次。
     * 这样用户在训练之后改计划——重排顺序、加减模板、改休息天数、关掉再开——
     * 都不会让「今天该练哪个」跳变，更不会去动已经记下的训练时间。
     */
    suspend fun resolvePlan(
        // 默认值不能直接写成挂起调用（Kotlin 不允许），所以传 null 表示「自己查」
        folders: List<TemplateFolder>? = null,
        templates: List<WorkoutTemplate>? = null,
        now: Long = System.currentTimeMillis(),
    ): TrainingPlan? {
        val folderList = folders ?: templateDao.observeFolders().first()
        val templateList = templates ?: templateDao.observeAll().first()
        val folder = folderList
            .filter { it.rotation }
            .sortedBy { it.sortOrder }
            .firstOrNull { f -> templateList.any { it.folderId == f.id } }
            ?: return null
        val cycle = templateList.filter { it.folderId == folder.id }.sortedBy { it.sortOrder }
        if (cycle.isEmpty()) return null
        val ids = cycle.map { it.id }

        val last = sessionDao.lastSessionForTemplates(ids)
        val lastIndex = last?.let { l -> ids.indexOf(l.templateId) }?.takeIf { it >= 0 }

        val storedNext = folder.cycleNextTemplateId
        val nextIndex: Int
        val cycleEndedAt: Long?
        if (storedNext != null && ids.contains(storedNext)) {
            nextIndex = ids.indexOf(storedNext)
            cycleEndedAt = folder.cycleEndedAt
        } else {
            // 没游标：老数据升级上来，或游标指向的模板已被删——按历史补一次
            nextIndex = TrainingCycle.deriveNextIndex(ids, last?.templateId)
            // 上一次正好练到轮末时，把休息日接上，别让升级把休息吃掉
            cycleEndedAt = if (lastIndex != null && lastIndex == ids.lastIndex) {
                last?.startTime
            } else {
                null
            }
        }

        return TrainingCycle.state(
            folderId = folder.id,
            name = folder.name,
            templateIds = ids,
            dayNames = cycle.map { it.name },
            restDays = folder.restDays,
            nextDayIndex = nextIndex,
            cycleEndedAt = cycleEndedAt,
            lastDayIndex = lastIndex,
            lastTrainedAt = last?.startTime,
            now = now,
        )
    }

    /**
     * 结束训练后推进计划游标。
     *
     * 只有「这次练的模板属于某个启用中的计划」才动；顺带把本轮完成时刻记下来，
     * 休息天数从这一刻起算。写成 id 而不是下标，之后重排也不会错位。
     */
    suspend fun advancePlanAfter(session: WorkoutSession) {
        val templateId = session.templateId ?: return
        val folders = templateDao.observeFolders().first()
        if (folders.none { it.rotation }) return
        val templates = templateDao.observeAll().first()
        val folder = folders
            .filter { it.rotation }
            .sortedBy { it.sortOrder }
            .firstOrNull { f -> templates.any { it.id == templateId && it.folderId == f.id } }
            ?: return
        val cycle = templates.filter { it.folderId == folder.id }.sortedBy { it.sortOrder }
        val index = cycle.indexOfFirst { it.id == templateId }
        if (index < 0) return
        val atCycleEnd = index == cycle.lastIndex
        templateDao.setPlanProgress(
            id = folder.id,
            // 练完最后一个 → 回到第一个，并开始计休息
            nextTemplateId = cycle[if (atCycleEnd) 0 else index + 1].id,
            cycleEndedAt = if (atCycleEnd) {
                session.endTime ?: System.currentTimeMillis()
            } else {
                null
            },
        )
    }

    /**
     * 按训练历史重算计划进度（清掉游标，下次读取时重新推导）。
     *
     * 用在两种「改过历史」的场合：删掉了训练记录、或在计划里动了模板却想回到按历史算。
     * 清空而不是直接算好写回：读的时候那条路径已经处理了「老数据 / 缺游标」，
     * 两处各写一份推导逻辑早晚会分叉。
     */
    suspend fun resetPlanProgress(folderId: Long) = templateDao.setPlanProgress(
        id = folderId,
        nextTemplateId = null,
        cycleEndedAt = null,
    )

    fun observeSessionCount(since: Long): Flow<Int> = sessionDao.observeSessionCount(since)

    fun observeTotalVolume(since: Long): Flow<Float> = sessionDao.observeTotalVolume(since)

    /**
     * 某动作最近一次完成记录（作为"上次数据"参考）。
     *
     * 取「上一次训练」而不是「最近 5 组」：跨两次训练的数据混在一起，
     * 会出现「上次 60×8 / 60×8 / 62.5×6 / 60×8」这种看不出是哪次的组合，
     * 进阶判断「是否全部达到上限」也会被上一轮的组干扰。
     */
    suspend fun lastPerformance(exerciseId: Long): List<SetLog> =
        sessionDao.lastSessionSets(exerciseId)

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
        val curve = curveFor(patternOf(exerciseId))
        val sets = sessionDao.workingSetsForExercise(exerciseId)
        return sets.maxWithOrNull(
            compareBy<SetLog> { OneRepMax.estimate(it.weightKg, it.reps, curve) ?: -1f }
                .thenBy { it.weightKg * it.reps }
        )
    }

    /** 动作的 rep-max 分档；查不到（或没有该动作）时按通用档处理 */
    suspend fun patternOf(exerciseId: Long): LiftPattern =
        exerciseDao.getById(exerciseId)
            ?.let { LiftPattern.fromValue(it.pattern) }
            ?: LiftPattern.UNKNOWN

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
