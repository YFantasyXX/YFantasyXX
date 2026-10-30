package com.yfantasyxx.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.data.db.WorkoutSession
import com.yfantasyxx.data.prefs.SettingsRepository
import com.yfantasyxx.sync.BackupManager
import com.yfantasyxx.sync.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.yfantasyxx.service.RestTimerService
import com.yfantasyxx.util.RestTimerStore

data class RestTimerState(
    val running: Boolean = false,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    /** 例如「杠铃卧推 · 第 2 组」 */
    val label: String? = null,
)

/**
 * 训练中「看历史」弹窗的状态。
 *
 * @param exerciseId 动作库里的 id，用来判断列表回来时弹窗是不是已经换成别的动作
 * @param sessionExerciseId 本次训练里这个动作的条目 id，套用历史时要写它的组
 */
data class HistoryState(
    val exerciseId: Long,
    val sessionExerciseId: Long,
    val exerciseName: String,
    val loading: Boolean = true,
    val entries: List<com.yfantasyxx.data.ExerciseHistoryEntry> = emptyList(),
)

class WorkoutViewModel(
    private val repo: WorkoutRepository,
    private val settingsRepo: SettingsRepository,
    private val context: android.content.Context,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _exercises = MutableStateFlow<List<SessionExerciseWithSets>>(emptyList())
    val exercises: StateFlow<List<SessionExerciseWithSets>> = _exercises.asStateFlow()

    private val _session = MutableStateFlow<WorkoutSession?>(null)
    val session: StateFlow<WorkoutSession?> = _session.asStateFlow()

    private val _restTimer = MutableStateFlow(RestTimerState())
    val restTimer: StateFlow<RestTimerState> = _restTimer.asStateFlow()

    /** 当前这次休息对应的动作（用于"设为该动作默认"） */
    private val _currentExerciseId = MutableStateFlow<Long?>(null)
    val currentExerciseId: StateFlow<Long?> = _currentExerciseId.asStateFlow()

    /** 当前休息对应的「动作 · 第 N 组」，用于通知与界面展示 */
    private var restLabel: String? = null

    /**
     * 完成训练时名称输入框的默认值：
     * 从模板开始 → 模板名；空训练 →「计划」
     */
    private val _defaultSessionName = MutableStateFlow("计划")
    val defaultSessionName: StateFlow<String> = _defaultSessionName

    /** 休息刚结束时置 true，界面显示提示；用户点掉后置回 false */
    private val _restFinished = MutableStateFlow(false)
    val restFinished: StateFlow<Boolean> = _restFinished

    fun dismissRestFinished() {
        _restFinished.value = false
    }

    /** 提示音/震动等全局设置（杠铃片计算器要用杆重与配片） */
    val settings: kotlinx.coroutines.flow.StateFlow<com.yfantasyxx.data.prefs.AppSettings> =
        settingsRepo.settings.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            com.yfantasyxx.data.prefs.AppSettings(),
        )

    /** 每个动作的"上次表现"参考（exerciseId -> 组列表） */
    private val _lastPerformance = MutableStateFlow<Map<Long, List<SetLog>>>(emptyMap())
    val lastPerformance: StateFlow<Map<Long, List<SetLog>>> = _lastPerformance.asStateFlow()

    /** 动作历史弹窗状态；null 表示没打开 */
    private val _history = MutableStateFlow<HistoryState?>(null)
    val history: StateFlow<HistoryState?> = _history.asStateFlow()

    /** 打开某个动作的历史，数据现查现给（只查这一个动作，成本很低） */
    fun openHistory(item: SessionExerciseWithSets) {
        val exerciseId = item.sessionExercise.exerciseId
        val sessionExerciseId = item.sessionExercise.id
        _history.value = HistoryState(
            exerciseId = exerciseId,
            sessionExerciseId = sessionExerciseId,
            exerciseName = item.exercise.name,
        )
        viewModelScope.launch {
            val entries = runCatching { repo.exerciseHistory(exerciseId) }.getOrDefault(emptyList())
            // 期间可能已经关掉，或者又点开了别的动作。
            // 判重要用 sessionExerciseId：同一训练里可以重复加同一个动作，
            // 只比 exerciseId 的话，A 的回包会写到 B 的弹窗上，「套用」就套错行
            _history.value = _history.value
                ?.takeIf { it.sessionExerciseId == sessionExerciseId }
                ?.copy(loading = false, entries = entries)
        }
    }

    fun closeHistory() {
        _history.value = null
    }

    /**
     * 把某次历史的重量次数按顺序套到还没完成的组上。
     * 历史里的组比待练的组少时，多出来的组沿用最后一组。
     */
    fun applyHistory(sessionExerciseId: Long, entry: com.yfantasyxx.data.ExerciseHistoryEntry) {
        val sources = entry.sets
        if (sources.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val pending = repo.currentSets(sessionExerciseId)
                    .filter { !it.completed }
                    .sortedBy { it.setNumber }
                if (pending.isEmpty()) {
                    repo.addSet(sessionExerciseId, sources.last().weightKg, sources.last().reps)
                    return@runCatching
                }
                pending.forEachIndexed { index, set ->
                    val src = sources.getOrNull(index) ?: sources.last()
                    repo.setSetWeightReps(set.id, src.weightKg, src.reps)
                }
            }.onFailure { e -> _error.value = "套用历史失败：${e.message.orEmpty()}" }
        }
    }

    /** 操作错误提示（避免异常直接导致闪退） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** 本次会话的训练总秒数 */
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private var tickerJob: Job? = null
    private var restTickerJob: Job? = null
    private var observeJob: Job? = null

    /** 确保有一个进行中的训练（没有就创建）。空草稿（无动作）也必须能正常进入。 */
    fun ensureSession(onReady: () -> Unit = {}) {
        if (_sessionId.value != null) {
            onReady()
            return
        }
        viewModelScope.launch {
            runCatching {
                // 注意：空训练草稿的动作列表是空 List（非 null），不能用 first()
                val draftId = repo.getActiveDraft()?.firstOrNull()?.sessionExercise?.sessionId
                val existingId = draftId ?: repo.getActiveSessionId()
                val id = existingId ?: repo.startSession()
                _session.value = repo.getSession(id)
                startObserving(id)
                startTicker()
            }.onFailure { e ->
                _error.value = "加载训练失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"
            }
            onReady()
        }
    }

    private suspend fun startObserving(id: Long) {
        _sessionId.value = id
        _session.value = repo.getSession(id)
        refreshDefaultName()
        loadProgressionModes()
        localOrderLock = false
        // 新训练重新放行「完成」：finishing 是防重复提交的闸门，
        // 不能跨 session 一直卡着
        finishing = false
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeSessionDetails(id).collectLatest { list ->
                // 拖动排序中：只忽略「顺序」，不能整条丢掉 ——
                // 这次发射里还带着刚打的勾、刚填的重量、新加的组，
                // 整条丢弃会让界面停在旧数据上，而且没有补发
                val ordered = if (localOrderLock) {
                    val byId = list.associateBy { it.sessionExercise.id }
                    val localIds = _exercises.value.map { it.sessionExercise.id }
                    val localSet = localIds.toSet()
                    localIds.mapNotNull { byId[it] } +
                        list.filter { it.sessionExercise.id !in localSet }
                } else {
                    list
                }
                _exercises.value = ordered
                // 仅在动作增减时刷新"上次表现"参考（组数据变化不需要重查）
                val ids = ordered.map { it.sessionExercise.exerciseId }
                if (ids != loadedPerformanceIds) {
                    loadedPerformanceIds = ids
                    _lastPerformance.value = ids.associateWith { exerciseId ->
                        repo.lastPerformance(exerciseId)
                    }
                }
            }
        }
    }

    private var loadedPerformanceIds: List<Long> = emptyList()

    /**
     * 动作 → 进阶策略（来自模板）。
     *
     * 空的训练没有模板，也就没有目标次数，进阶建议自然不出现，
     * 所以这里查不到时给空表即可，不用回落到全局默认。
     */
    private val _progressionModes = MutableStateFlow<Map<Long, com.yfantasyxx.data.ProgressionMode>>(
        emptyMap()
    )
    val progressionModes: StateFlow<Map<Long, com.yfantasyxx.data.ProgressionMode>> =
        _progressionModes.asStateFlow()

    private suspend fun loadProgressionModes() {
        val templateId = _session.value?.templateId ?: return
        val tpl = repo.getTemplate(templateId) ?: return
        _progressionModes.value = tpl.items.associate {
            it.exercise.id to com.yfantasyxx.data.ProgressionMode.from(it.templateExercise.progression)
        }
    }

    /** 应用进阶建议：把所有未完成的组改成建议的重量与次数 */
    fun applyProgression(item: SessionExerciseWithSets, weightKg: Float, reps: Int) {
        viewModelScope.launch {
            runCatching {
                item.sets.filter { !it.completed }.forEach { set ->
                    // 只改重量/次数两列：整行写回会把快照里的完成状态一起带回去
                    repo.setSetWeightReps(set.id, weightKg, reps)
                }
            }.onFailure { e -> _error.value = "应用进阶建议失败：${e.message.orEmpty()}" }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        val startedAt = _session.value?.activeStartedAt
        if (startedAt == null) {
            // 还没完成第一组，训练时长不计时
            _elapsedSeconds.value = 0
            return
        }
        tickerJob = viewModelScope.launch {
            while (true) {
                _elapsedSeconds.value = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                delay(1000)
            }
        }
    }

    fun addExercise(exerciseId: Long) {
        val id = _sessionId.value
        if (id == null) {
            _error.value = "训练还没准备好，请稍等一秒再试"
            return
        }
        viewModelScope.launch {
            runCatching { repo.addExerciseToSession(id, exerciseId) }
                .onFailure { e ->
                    _error.value = "添加动作失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"
                }
        }
    }

    /** 计算默认训练名：模板名优先，空训练用「计划」 */
    private suspend fun refreshDefaultName() {
        val templateId = _session.value?.templateId
        _defaultSessionName.value = when {
            templateId != null -> repo.getTemplate(templateId)?.template?.name
                ?.takeIf { it.isNotBlank() } ?: "训练"
            else -> "计划"
        }
    }

    /**
     * 拖动中：仅调整本地列表顺序，不落库。
     * 拖动过程中每次换位都写库会产生并发事务，旧顺序可能最后落地，
     * Room Flow 再发射就把界面拉回，因此改为「松手一次性持久化」。
     */
    /**
     * 按界面拖出来的顺序重排并落库。
     *
     * 界面上的下标属于「显示顺序」，而 [_exercises] 是库里的顺序；拖动期间
     * 两者可能不一致（上一次落库还没回声），所以这里收的是 id 列表，
     * 不再用下标去猜。
     */
    fun applyExerciseOrder(orderedIds: List<Long>) {
        val items = _exercises.value
        if (items.isEmpty() || orderedIds.isEmpty()) return
        val byId = items.associateBy { it.sessionExercise.id }
        val orderedSet = orderedIds.toSet()
        val reordered = orderedIds.mapNotNull { byId[it] } +
            items.filter { it.sessionExercise.id !in orderedSet }
        if (reordered.map { it.sessionExercise.id } == items.map { it.sessionExercise.id }) return
        _exercises.value = reordered
        localOrderLock = true
        persistExerciseOrder()
    }

    /** 拖动结束：把当前顺序一次性写入数据库 */
    fun persistExerciseOrder() {
        val items = _exercises.value
        val sessionId = _sessionId.value ?: return
        viewModelScope.launch {
            val ok = runCatching {
                repo.reorderSessionExercises(sessionId, items.map { it.sessionExercise.id })
            }.onFailure { e ->
                _error.value = "调整顺序失败：${e.message.orEmpty()}"
            }.isSuccess
            if (!ok) {
                // 落库失败就回到库里的真实顺序，别让界面拿着一个永远等不到回声的顺序
                runCatching { repo.getSessionDetails(sessionId) }.getOrNull()?.let {
                    _exercises.value = it
                }
            }
            // 必须等写入完成后再解锁：提前释放会让 Room Flow 的旧顺序
            // 在写入落地前覆盖本地新顺序
            localOrderLock = false
        }
    }

    /** 拖动排序期间：忽略 Room Flow 的顺序发射，以本地顺序为准 */
    private var localOrderLock = false

    /** 新建自定义动作，保存到动作库并直接加入当前训练 */
    fun addCustomExerciseToSession(
        name: String,
        category: String,
        equipment: String,
        muscle: String,
    ) {
        val sessionId = _sessionId.value
        if (sessionId == null) {
            _error.value = "训练还没准备好，请稍等一秒再试"
            return
        }
        viewModelScope.launch {
            runCatching {
                val exerciseId = repo.addCustomExercise(name, category, equipment, muscle)
                repo.addExerciseToSession(sessionId, exerciseId)
            }.onFailure { e ->
                _error.value = "新建动作失败：${e.message.orEmpty()}"
            }
        }
    }

    fun addSet(sessionExerciseId: Long) {
        viewModelScope.launch {
            runCatching { repo.addSet(sessionExerciseId) }
                .onFailure { e -> _error.value = "添加组失败：${e.message.orEmpty()}" }
        }
    }

    /**
     * 复制上一组：把「上一组」的重量次数套到下一个还没填的组上。
     *
     * 以前直接拿界面传进来的 [item] 快照算「第一个未完成的组」，有两个坑：
     * - 连点两下时，第一次的写入还没回包，第二次算出来的目标还是同一组，
     *   写进去的值也一模一样，看起来就是「点一次之后就没反应」；
     * - 本次训练一组都还没做时没有来源，函数直接静默返回。
     *
     * 现在从库里取最新组，目标只挑「还没填重量次数」的待练组：每点一次就落到下一组，
     * 待练的组都填过了就照上一组再加一组。来源优先目标前面最后一个有数据的组，
     * 没有就退到上次训练的同一动作。
     */
    fun copyPreviousSet(item: SessionExerciseWithSets) {
        val sessionExerciseId = item.sessionExercise.id
        val exerciseId = item.sessionExercise.exerciseId
        viewModelScope.launch {
            runCatching {
                val sets = repo.currentSets(sessionExerciseId).sortedBy { it.setNumber }
                val target = sets.firstOrNull {
                    !it.completed && it.weightKg <= 0f && it.reps <= 0f
                }
                val previous = if (target != null) {
                    // 「上一组」得是目标之前的那一行，不能拿目标自己（可能刚填了数）
                    sets.lastOrNull {
                        it.setNumber < target.setNumber && (it.weightKg > 0f || it.reps > 0f)
                    }
                } else {
                    sets.lastOrNull { it.weightKg > 0f || it.reps > 0f }
                }
                val source = previous ?: repo.lastPerformance(exerciseId).lastOrNull()
                if (source == null) {
                    _error.value = "这个动作还没有可以复制的记录"
                    return@runCatching
                }
                if (target != null) {
                    repo.setSetWeightReps(target.id, source.weightKg, source.reps)
                } else {
                    repo.addSet(sessionExerciseId, source.weightKg, source.reps)
                }
            }.onFailure { e -> _error.value = "复制失败：${e.message.orEmpty()}" }
        }
    }

    /** 训练中替换动作：组、组号、排序、超级组全部保留 */
    fun replaceExercise(sessionExerciseId: Long, newExerciseId: Long) {
        viewModelScope.launch {
            runCatching {
                repo.replaceSessionExercise(sessionExerciseId, newExerciseId)
                // 换掉的是正在休息的那个动作：标签里的名字已经不对了，
                // 留着会让「设为该动作默认休息」写到新动作上
                if (_currentExerciseId.value == sessionExerciseId) {
                    _currentExerciseId.value = null
                    restLabel = null
                }
                // 动作换了，「上次表现」也得换。这个映射原本只在动作增减时刷新，
                // 换动作不改 sessionExercise id，所以要手动重载一次。
                _exercises.value.let { list ->
                    val ids = list.map { it.sessionExercise.exerciseId }
                    loadedPerformanceIds = ids
                    _lastPerformance.value = ids.distinct().associateWith {
                        repo.lastPerformance(it)
                    }
                }
            }.onFailure { e -> _error.value = "替换动作失败：${e.message.orEmpty()}" }
        }
    }

    /** 组成 / 退出超级组。组队只影响休息时机与展示，不改组数据 */
    fun toggleSuperset(current: SessionExerciseWithSets, nextSessionExerciseId: Long?) {
        val sessionId = _sessionId.value ?: return
        viewModelScope.launch {
            runCatching {
                if (current.sessionExercise.supersetGroup != null) {
                    repo.unlinkSuperset(sessionId, current.sessionExercise.id)
                } else if (nextSessionExerciseId != null) {
                    repo.linkSuperset(sessionId, current.sessionExercise.id, nextSessionExerciseId)
                }
            }.onFailure { e -> _error.value = "超级组设置失败：${e.message.orEmpty()}" }
        }
    }

    fun savePlateSettings(barWeightKg: Float, plates: String) {
        viewModelScope.launch {
            settingsRepo.setBarWeight(barWeightKg)
            settingsRepo.setPlates(plates)
        }
    }

    fun removeExercise(sessionExerciseId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeSessionExercise(sessionExerciseId) }
                .onFailure { e -> _error.value = "移除动作失败：${e.message.orEmpty()}" }
            // 被删的动作如果正在休息计时里，标签要一起清掉：
            // 否则休息条还挂着「某动作 · 第 N 组」，点进去还能把它设成默认
            if (_currentExerciseId.value == sessionExerciseId) {
                _currentExerciseId.value = null
                restLabel = null
            }
        }
    }

    /**
     * 更新一组的重量/次数/RPE（不改变完成状态）。
     *
     * 只写字面量发生变化的列：`set` 是界面手里的快照，如果整行写回，
     * 快照里过期的 completed / isPr 会把刚打上的勾抹掉。
     */
    fun updateSet(set: SetLog, weightKg: Float? = null, reps: Int? = null, rpe: Float? = null) {
        viewModelScope.launch {
            if (rpe != null && rpe != set.rpe) {
                // RPE 很少改，仍走整行；先取最新的行再改，避免用旧快照覆盖别的列
                val fresh = runCatching { repo.getSet(set.id) }.getOrNull() ?: set
                repo.updateSet(fresh.copy(rpe = rpe))
            }
            if (weightKg != null || reps != null) {
                repo.setSetWeightReps(
                    id = set.id,
                    weightKg = weightKg ?: set.weightKg,
                    reps = reps ?: set.reps,
                )
            }
        }
    }

    fun toggleSetComplete(set: SetLog) {
        viewModelScope.launch {
            // 以库里的最新值为准取反，而不是界面的快照：
            // 手指抖一下连点两次时，两次都基于同一个旧快照算成 true，
            // 勾就取消不掉了，还会把休息计时重新拉满
            val fresh = runCatching { repo.getSet(set.id) }.getOrNull() ?: return@launch
            val willComplete = !fresh.completed
            // 只改完成这一列。整行写回会用这一行的旧快照，
            // 把刚填进去的重量/次数覆盖成旧值（表现就是「打勾后数值归零」）。
            repo.setSetCompleted(fresh.id, willComplete)
            if (willComplete) {
                // 第一组完成时才真正开始计时（空训练/模板进入时不立即计时）
                val sessionId = _sessionId.value
                if (sessionId != null && _session.value?.activeStartedAt == null) {
                    val now = System.currentTimeMillis()
                    repo.markSessionStarted(sessionId, now)
                    _session.value = _session.value?.copy(activeStartedAt = now)
                    startTicker()
                }
                val item = _exercises.value
                    .firstOrNull { it.sessionExercise.id == set.sessionExerciseId }
                val exerciseId = item?.sessionExercise?.exerciseId
                // 兜底查询，避免 flow 尚未 emit 时拿不到动作名
                val exerciseName = item?.exercise?.name
                    ?: exerciseId?.let { runCatching { repo.getExercise(it)?.name }.getOrNull() }
                val label = if (exerciseName != null) {
                    "$exerciseName · 第 ${set.setNumber} 组"
                } else {
                    "第 ${set.setNumber} 组"
                }
                // 超级组里交替做，组内不休息：只有轮到最后那一个才起计时器
                val supersetGroup = item?.sessionExercise?.supersetGroup
                if (supersetGroup == null || isLastOfSupersetRound(item, set, supersetGroup)) {
                    startRestTimerFor(exerciseId, set.sessionExerciseId, label)
                }
            }
        }
    }

    /**
     * 超级组是否轮到了「组末」。
     *
     * 判据：同一超级组里，其它动作已完成的组数都不少于我这里做完后的组数。
     * 只要有同伴还落后，就说明该轮到它了，不该开始长休息。
     */
    private fun isLastOfSupersetRound(
        item: SessionExerciseWithSets,
        set: SetLog,
        group: Int,
    ): Boolean {
        val peers = _exercises.value.filter {
            it.sessionExercise.supersetGroup == group && it.sessionExercise.id != set.sessionExerciseId
        }
        if (peers.isEmpty()) return true
        val mine = item.sets.count { it.completed && it.id != set.id } + 1
        val peerMin = peers.minOf { peer -> peer.sets.count { it.completed } }
        return peerMin >= mine
    }

    /** 标记/取消 PR 组（计入 PR 榜，但不作为下次训练参考重量） */
    fun toggleSetPr(set: SetLog) {
        viewModelScope.launch {
            // 以库里的最新值为准取反：用界面快照连点两下会算成同一个值，PR 标记就取消不掉
            val fresh = runCatching { repo.getSet(set.id) }.getOrNull() ?: return@launch
            runCatching { repo.setSetPr(fresh.id, !fresh.isPr) }
                .onFailure { e -> _error.value = "标记 PR 失败：${e.message.orEmpty()}" }
        }
    }

    fun deleteSet(set: SetLog) {
        viewModelScope.launch {
            runCatching { repo.deleteSetAndRenumber(set.id) }
                .onFailure { e -> _error.value = "删除该组失败：${e.message.orEmpty()}" }
        }
    }

    /**
     * 休息时长优先级：模板为该动作指定的 > 动作自身设定 > 全局默认
     */
    private suspend fun resolveRestSeconds(
        exerciseId: Long?,
        sessionExerciseId: Long? = null,
    ): Int {
        val fromTemplate = sessionExerciseId?.let { repo.sessionRestSeconds(it) }
        val actionDefault = exerciseId?.let { repo.restSecondsFor(it) }
        return fromTemplate
            ?: actionDefault
            ?: settingsRepo.settings.first().restSeconds
    }

    /**
     * 开始休息计时：交给前台服务在通知栏倒计时，退出 App 也不会重置。
     * [exerciseId] 用于读取该动作自己的休息时长。
     */
    fun startRestTimerFor(
        exerciseId: Long?,
        sessionExerciseId: Long? = null,
        label: String? = null,
    ) {
        viewModelScope.launch {
            val seconds = resolveRestSeconds(exerciseId, sessionExerciseId)
            _currentExerciseId.value = exerciseId
            restLabel = label
            _restFinished.value = false
            RestTimerService.start(context, seconds, label)
            _restTimer.value = RestTimerState(
                running = true,
                remainingSeconds = seconds,
                totalSeconds = seconds,
                label = label,
            )
            startRestTicker()
        }
    }

    fun startRestTimer() {
        startRestTimerFor(_currentExerciseId.value)
    }

    /** 用波轮选的时长重设本次休息（不影响动作默认值） */
    fun restartRestTimer(seconds: Int) {
        viewModelScope.launch {
            _restFinished.value = false
            RestTimerService.start(context, seconds, restLabel)
            _restTimer.value = RestTimerState(
                running = true,
                remainingSeconds = seconds,
                totalSeconds = seconds,
                label = restLabel,
            )
            startRestTicker()
        }
    }

    /** 把某个时长保存为该动作的默认休息时长 */
    fun saveExerciseRestSeconds(exerciseId: Long, seconds: Int) {
        viewModelScope.launch {
            runCatching {
                repo.getExercise(exerciseId)?.let { exercise ->
                    repo.updateExercise(exercise.copy(restSeconds = seconds))
                }
            }
        }
    }

    /** 重新进入训练页时，恢复仍在运行的休息计时 */
    fun restoreRestTimer() {
        val snapshot = RestTimerStore.snapshot(context)
        if (snapshot.running) {
            restLabel = snapshot.label
            _restTimer.value = RestTimerState(
                running = true,
                remainingSeconds = snapshot.remainingSeconds,
                totalSeconds = snapshot.totalSeconds,
                label = snapshot.label,
            )
            startRestTicker()
        }
    }

    private fun startRestTicker() {
        restTickerJob?.cancel()
        restTickerJob = viewModelScope.launch {
            // 服务是异步启动的，先等它把结束时间写进 Store，
            // 否则会读到上一次（或空）的状态，误判成「休息已结束」
            delay(200)
            var sawRunning = false
            var waitTries = 0
            while (true) {
                val snapshot = RestTimerStore.snapshot(context)
                if (snapshot.running) {
                    sawRunning = true
                    waitTries = 0
                    _restTimer.value = RestTimerState(
                        running = true,
                        remainingSeconds = snapshot.remainingSeconds,
                        totalSeconds = snapshot.totalSeconds,
                        label = snapshot.label,
                    )
                } else if (sawRunning) {
                    // 真正跑过再归零，才算休息结束
                    _restTimer.value = RestTimerState(
                        running = false,
                        remainingSeconds = 0,
                        totalSeconds = snapshot.totalSeconds,
                        label = snapshot.label,
                    )
                    _restFinished.value = true
                    com.yfantasyxx.service.RestNotifier.vibrate(context)
                    break
                } else {
                    // 服务还没写好状态，短暂等待；超时则放弃（避免死循环）
                    waitTries++
                    if (waitTries > 15) break
                    delay(200)
                    continue
                }
                delay(400)
            }
        }
    }

    fun skipRestTimer() {
        RestTimerService.skip(context)
        restTickerJob?.cancel()
        restLabel = null
        _restTimer.value = RestTimerState()
    }

    fun extendRestTimer(extraSeconds: Int) {
        RestTimerService.extend(context, extraSeconds)
    }

    /** 完成训练 */
    /** 完成训练只允许进来一次：finishSession 里会推进计划游标，重复执行等于白丢一天 */
    private var finishing = false

    fun finishWorkout(name: String, note: String, onDone: () -> Unit) {
        if (finishing) return
        val id = _sessionId.value ?: return
        finishing = true
        // 名称为空时用默认名（模板名或「计划」）
        val finalName = name.trim().ifBlank { _defaultSessionName.value }
        viewModelScope.launch {
            val result = runCatching { repo.finishSession(id, finalName, note) }
            if (result.isFailure) {
                finishing = false
                _error.value = "完成训练失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            tickerJob?.cancel()
            restTickerJob?.cancel()
            RestTimerService.skip(context)
            onDone()
            // 训练完成后按设置自动备份（独立协程，不阻塞导航）
            autoBackupAfterFinish()
        }
    }

    /** 设置开启「训练完成后自动备份」且 WebDAV 已配置时，备份到远端 */
    private fun autoBackupAfterFinish() {
        // 用应用级协程：finish 后界面导航离开、ViewModel 可能被清除，
        // viewModelScope 会被取消导致备份半途而废
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val s = settingsRepo.settings.first()
            if (!s.autoBackup) return@launch
            if (s.webdavUrl.isBlank() || s.webdavUsername.isBlank()) return@launch
            val client = WebDavClient(s.webdavUrl, s.webdavUsername, s.webdavPassword)
            val result = backupManager.backup(client)
            if (result.isSuccess) {
                settingsRepo.setLastBackupTime(System.currentTimeMillis())
            }
            val msg = result.fold(
                onSuccess = { "已完成训练并自动备份" },
                onFailure = { "自动备份失败：${it.message ?: "未知错误"}" },
            )
            // Toast 需要在主线程
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 放弃训练（删除草稿） */
    fun discardWorkout(onDone: () -> Unit) {
        val id = _sessionId.value ?: return
        viewModelScope.launch {
            repo.discardSession(id)
            tickerJob?.cancel()
            restTickerJob?.cancel()
            RestTimerService.skip(context)
            _sessionId.value = null
            onDone()
        }
    }

    fun renameSession(name: String, note: String) {
        val id = _sessionId.value ?: return
        viewModelScope.launch {
            repo.updateSessionName(id, name, note)
            _session.value = repo.getSession(id)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                WorkoutViewModel(
                    container.workoutRepository,
                    container.settingsRepository,
                    container.appContext,
                    container.backupManager,
                )
            }
        }
    }
}
