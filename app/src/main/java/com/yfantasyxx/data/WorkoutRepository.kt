package com.yfantasyxx.data

import com.yfantasyxx.data.db.ActiveSessionSummary
import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.data.db.BodyMetric
import com.yfantasyxx.data.db.BucketVolume
import com.yfantasyxx.data.db.CategoryVolume
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.ExercisePr
import com.yfantasyxx.data.db.PlanDay
import com.yfantasyxx.data.db.PlanEntity
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
    private val planDao = db.planDao()

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
     * 当前激活的训练计划状态；没开计划时为 null。
     *
     * 四路合并：数据版本（练完 / 删记录后要重算）、计划本身（游标）、
     * 计划里的天（增删排序）、模板列表（改名后提示要跟着变）。
     */
    fun observeActivePlan(): Flow<TrainingPlan?> = combine(
        dataVersion,
        planDao.observePlans(),
        planDao.observeDays(),
        templateDao.observeAll(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        resolveActivePlan(
            plans = values[1] as List<PlanEntity>,
            days = values[2] as List<PlanDay>,
            templates = values[3] as List<WorkoutTemplate>,
        )
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

    /**
     * 通知「数据变了」。
     *
     * 正常写入都会自己调 [invalidateStats]；只有 lite 的测试工具会绕过仓库直接写库
     * （为了伪造历史时间戳），写完之后必须从这里补一次，否则首页和统计不会重新取数。
     */
    fun notifyDataChanged() = invalidateStats()

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

    // ---------- 训练计划 ----------

    fun observePlans(): Flow<List<PlanEntity>> = planDao.observePlans()

    fun observePlanDays(): Flow<List<PlanDay>> = planDao.observeDays()

    /** 新建计划；[activate] 时顺手关掉别的计划（同时只能开一个） */
    suspend fun createPlan(
        name: String,
        days: List<Long?>,
        activate: Boolean = true,
    ): Long = db.withTransaction {
        if (activate) planDao.deactivateAll()
        val planId = planDao.insertPlan(
            PlanEntity(
                name = name.trim().ifBlank { "训练计划" },
                isActive = activate,
                nextIndex = 0,
                dayStartedAt = TrainingCycle.startOfDay(System.currentTimeMillis()),
            )
        )
        days.forEachIndexed { index, templateId ->
            planDao.insertDay(PlanDay(planId = planId, position = index, templateId = templateId))
        }
        planId
    }

    /**
     * 保存计划（改名 + 整体替换每天）。
     *
     * 替换的是「天」而不是模板本身，所以改计划不会碰训练记录。
     * 游标按**今天该练的模板**搬过去：把休息日挪到前面、或者加一天，
     * 今天该练的东西不会跟着下标漂走。
     */
    suspend fun updatePlan(planId: Long, name: String, days: List<Long?>) = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction
        val oldDays = planDao.daysOf(planId).sortedBy { it.position }
        val oldSlots = oldDays.map { PlanDaySlot(it.templateId, "") }
        val currentIndex = TrainingCycle.currentIndex(plan.nextIndex, plan.dayStartedAt, oldSlots)
        val todayTemplate = oldDays.getOrNull(currentIndex)?.templateId

        planDao.renamePlan(planId, name.trim().ifBlank { plan.name })
        planDao.deleteDays(planId)
        days.forEachIndexed { index, templateId ->
            planDao.insertDay(PlanDay(planId = planId, position = index, templateId = templateId))
        }

        val newIndex = if (days.isEmpty()) {
            0
        } else if (todayTemplate != null) {
            days.indexOfFirst { it == todayTemplate }.takeIf { it >= 0 } ?: 0
        } else {
            plan.nextIndex.coerceIn(0, days.lastIndex)
        }
        planDao.setProgress(planId, newIndex, plan.dayStartedAt)
    }

    suspend fun deletePlan(planId: Long) = planDao.deletePlan(planId)

    /**
     * 开启 / 关闭计划。
     *
     * 同时只能开一个：开新的会把别的关掉。开启时进度从第 1 天重新算——
     * 「打开一个计划」的语义就是从头开始照着练，而不是接着上次某个半截状态。
     */
    suspend fun setActivePlan(planId: Long?) = db.withTransaction {
        val current = planDao.activePlan()
        if (current?.id == planId) return@withTransaction
        planDao.deactivateAll()
        if (planId != null) {
            planDao.activate(planId)
            planDao.setProgress(
                id = planId,
                nextIndex = 0,
                dayStartedAt = TrainingCycle.startOfDay(System.currentTimeMillis()),
            )
        }
    }

    /** 回到第 1 天（按计划重新开始） */
    suspend fun resetPlanProgress(planId: Long) = planDao.setProgress(
        id = planId,
        nextIndex = 0,
        dayStartedAt = TrainingCycle.startOfDay(System.currentTimeMillis()),
    )

    /**
     * 休息日再加一天。
     *
     * 休息日默认占满一个自然日自动流过；想多休一天就调这个：
     * 把这段休息的起点推到明天零点，休息就顺延一天（想休几天点几次）。
     *
     * 只动**进度**，不动计划结构（哪几天、什么模板），所以不会把计划排乱。
     * 不是休息日时什么都不做——训练日本来就停着等你练，不需要加休。
     */
    suspend fun extendRestDay(planId: Long) = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction
        val days = planDao.daysOf(planId).sortedBy { it.position }
        if (days.isEmpty()) return@withTransaction
        val slots = days.map { PlanDaySlot(it.templateId, "") }
        val cur = TrainingCycle.cursor(plan.nextIndex, plan.dayStartedAt, slots)
        if (slots.getOrNull(cur.index)?.isRest != true) return@withTransaction
        val now = System.currentTimeMillis()
        // 从「今天」起算往后推一天：已经过去的时间不管，
        // 否则连点两次反而会让休息越点越短
        val base = maxOf(cur.dayStartedAt, TrainingCycle.startOfDay(now))
        planDao.setProgress(
            id = planId,
            nextIndex = plan.nextIndex,
            dayStartedAt = TrainingCycle.startOfNextDay(base),
        )
    }

    /**
     * 跳过今天这一天：不算完成训练，直接进下一天。
     *
     * 漏练**不需要**点任何东西——训练日会一直停在这一天等你，练完才往前。
     * 这个按钮只给「这次主动不练了」用（比如这周临时有事，放弃这一轮）。
     */
    suspend fun skipPlanDay(planId: Long) = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction
        val days = planDao.daysOf(planId).sortedBy { it.position }
        if (days.isEmpty()) return@withTransaction
        val slots = days.map { PlanDaySlot(it.templateId, "") }
        val cur = TrainingCycle.cursor(plan.nextIndex, plan.dayStartedAt, slots)
        val next = (cur.index + 1) % days.size
        val now = System.currentTimeMillis()
        // 下一格是休息日：休息从今天就开始（今天已经换到它了）。
        // 下一格是训练日：它不按时间走，起点无所谓。
        val startedAt = if (slots[next].isRest) {
            TrainingCycle.startOfDay(now)
        } else {
            TrainingCycle.startOfNextDay(now)
        }
        planDao.setProgress(id = planId, nextIndex = next, dayStartedAt = startedAt)
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

    /**
     * 只改「完成」这一列。
     *
     * 打勾时不能用整行 [updateSet]：界面手里的那行快照可能还没拿到刚填的重量，
     * 整行写回会把重量/次数覆盖成旧值（「打勾后数值归零」就是这么来的）。
     */
    suspend fun setSetCompleted(id: Long, completed: Boolean) {
        setLogDao.updateCompleted(id, completed)
        invalidateStats()
    }

    /** 只改「PR」这一列，原因同上 */
    suspend fun setSetPr(id: Long, isPr: Boolean) {
        setLogDao.updatePr(id, isPr)
        invalidateStats()
    }

    /** 只改重量和次数，不碰完成 / PR / 热身标记 */
    suspend fun setSetWeightReps(id: Long, weightKg: Float, reps: Int) {
        setLogDao.updateWeightReps(id, weightKg, reps)
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
     * 如果删掉的是计划里的训练，游标要退回那一天——否则「删了刚才那次」之后
     * 计划还停在下一格，等于白练了一次。
     */
    suspend fun deleteSession(id: Long) {
        val templateId = sessionDao.getById(id)?.templateId
        sessionDao.delete(id)
        if (templateId != null) {
            val plan = planDao.activePlan()
            if (plan != null) {
                val days = planDao.daysOf(plan.id).sortedBy { it.position }
                val index = days.indexOfFirst { it.templateId == templateId }
                if (index >= 0) {
                    planDao.setProgress(
                        id = plan.id,
                        nextIndex = index,
                        dayStartedAt = TrainingCycle.startOfDay(System.currentTimeMillis()),
                    )
                }
            }
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
     * 解析当前激活的计划。
     *
     * 传入列表让 Flow 版能一次拿全（避免每条流各查一次库），单次读取时传 null。
     */
    suspend fun resolveActivePlan(
        plans: List<PlanEntity>? = null,
        days: List<PlanDay>? = null,
        templates: List<WorkoutTemplate>? = null,
        now: Long = System.currentTimeMillis(),
    ): TrainingPlan? {
        val plan = (plans ?: planDao.observePlans().first()).firstOrNull { it.isActive } ?: return null
        val allDays = days ?: planDao.observeDays().first()
        val myDays = allDays.filter { it.planId == plan.id }
        if (myDays.isEmpty()) return null
        val templateList = templates ?: templateDao.observeAll().first()
        val ids = myDays.mapNotNull { it.templateId }
        val last = if (ids.isEmpty()) null else sessionDao.lastSessionForTemplates(ids)
        return TrainingCycle.resolve(
            plan = plan,
            days = myDays,
            templateNames = templateList.associate { it.id to it.name },
            lastTrainedAt = last?.startTime,
            now = now,
        )
    }

    /**
     * 结束训练后推进计划。
     *
     * 只在「这次练的正是今天该练的那个模板」时推进：临时加练别的模板、
     * 或者计划外的自由训练，都不该让计划往前走一天。
     * 休息日点「提前练」练了下一个训练日，也算这一天过完了。
     */
    suspend fun advancePlanAfter(session: WorkoutSession) {
        val templateId = session.templateId ?: return
        val plan = planDao.activePlan() ?: return
        val days = planDao.daysOf(plan.id).sortedBy { it.position }
        if (days.isEmpty()) return

        val slots = days.map { PlanDaySlot(it.templateId, "") }
        val today = TrainingCycle.currentIndex(plan.nextIndex, plan.dayStartedAt, slots)

        val done = when {
            days[today].templateId == templateId -> today
            days[today].templateId == null -> {
                // 休息日提前开练：找休息结束后的第一个训练日
                var i = today
                var guard = 0
                while (guard++ < days.size && days[i].templateId == null) {
                    i = (i + 1) % days.size
                }
                if (days[i].templateId == templateId) i else -1
            }
            else -> -1
        }
        if (done < 0) return

        planDao.setProgress(
            id = plan.id,
            nextIndex = (done + 1) % days.size,
            // 下一格从明天算起：今晚再练一次也不会把休息日跳过去
            dayStartedAt = TrainingCycle.startOfNextDay(System.currentTimeMillis()),
        )
    }

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
