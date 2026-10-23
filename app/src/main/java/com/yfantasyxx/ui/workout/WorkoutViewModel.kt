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

    /** 每个动作的"上次表现"参考（exerciseId -> 组列表） */
    private val _lastPerformance = MutableStateFlow<Map<Long, List<SetLog>>>(emptyMap())
    val lastPerformance: StateFlow<Map<Long, List<SetLog>>> = _lastPerformance.asStateFlow()

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
        localOrderLock = false
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeSessionDetails(id).collectLatest { list ->
                // 拖动排序中：Flow 里可能是旧顺序，忽略它以本地顺序为准
                if (localOrderLock) return@collectLatest
                _exercises.value = list
                // 仅在动作增减时刷新"上次表现"参考（组数据变化不需要重查）
                val ids = list.map { it.sessionExercise.exerciseId }
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
    fun moveExerciseLocal(from: Int, to: Int) {
        val items = _exercises.value.toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        _exercises.value = items
        localOrderLock = true
    }

    /** 拖动结束：把当前顺序一次性写入数据库 */
    fun persistExerciseOrder() {
        val items = _exercises.value
        val sessionId = _sessionId.value ?: return
        localOrderLock = false
        viewModelScope.launch {
            runCatching {
                repo.reorderSessionExercises(sessionId, items.map { it.sessionExercise.id })
            }.onFailure { e ->
                localOrderLock = false
                _error.value = "调整顺序失败：${e.message.orEmpty()}"
            }
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

    fun removeExercise(sessionExerciseId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeSessionExercise(sessionExerciseId) }
                .onFailure { e -> _error.value = "移除动作失败：${e.message.orEmpty()}" }
        }
    }

    /** 更新一组的重量/次数/RPE（不改变完成状态） */
    fun updateSet(set: SetLog, weightKg: Float? = null, reps: Int? = null, rpe: Float? = null) {
        viewModelScope.launch {
            val updated = set.copy(
                weightKg = weightKg ?: set.weightKg,
                reps = reps ?: set.reps,
                rpe = rpe ?: set.rpe,
            )
            repo.updateSet(updated)
        }
    }

    fun toggleSetComplete(set: SetLog) {
        viewModelScope.launch {
            val willComplete = !set.completed
            repo.updateSet(set.copy(completed = willComplete))
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
                startRestTimerFor(exerciseId, set.sessionExerciseId, label)
            }
        }
    }

    /** 标记/取消 PR 组（计入 PR 榜，但不作为下次训练参考重量） */
    fun toggleSetPr(set: SetLog) {
        viewModelScope.launch {
            runCatching { repo.updateSet(set.copy(isPr = !set.isPr)) }
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
    fun finishWorkout(name: String, note: String, onDone: () -> Unit) {
        val id = _sessionId.value ?: return
        // 名称为空时用默认名（模板名或「计划」）
        val finalName = name.trim().ifBlank { _defaultSessionName.value }
        viewModelScope.launch {
            repo.finishSession(id, finalName, note)
            tickerJob?.cancel()
            restTickerJob?.cancel()
            RestTimerService.skip(context)
            tickerJob?.cancel()
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
