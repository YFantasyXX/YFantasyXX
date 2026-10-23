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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class RestTimerState(
    val running: Boolean = false,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
)

class WorkoutViewModel(
    private val repo: WorkoutRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _exercises = MutableStateFlow<List<SessionExerciseWithSets>>(emptyList())
    val exercises: StateFlow<List<SessionExerciseWithSets>> = _exercises.asStateFlow()

    private val _session = MutableStateFlow<WorkoutSession?>(null)
    val session: StateFlow<WorkoutSession?> = _session.asStateFlow()

    private val _restTimer = MutableStateFlow(RestTimerState())
    val restTimer: StateFlow<RestTimerState> = _restTimer.asStateFlow()

    /** 每个动作的"上次表现"参考（exerciseId -> 组列表） */
    private val _lastPerformance = MutableStateFlow<Map<Long, List<SetLog>>>(emptyMap())
    val lastPerformance: StateFlow<Map<Long, List<SetLog>>> = _lastPerformance.asStateFlow()

    /** 本次会话的训练总秒数 */
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private var tickerJob: Job? = null
    private var timerJob: Job? = null
    private var observeJob: Job? = null

    /** 确保有一个进行中的训练（没有就创建） */
    fun ensureSession(onReady: () -> Unit = {}) {
        if (_sessionId.value != null) {
            onReady()
            return
        }
        viewModelScope.launch {
            val draft = repo.getActiveDraft()
            if (draft != null) {
                startObserving(draft.first().sessionExercise.sessionId)
            } else {
                val newId = repo.startSession()
                startObserving(newId)
            }
            startTicker()
            onReady()
        }
    }

    private fun startObserving(id: Long) {
        _sessionId.value = id
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeSessionDetails(id).collectLatest { list ->
                _exercises.value = list
                // 刷新上次表现参考
                val map = list.associate { item ->
                    item.sessionExercise.exerciseId to
                        (repo.lastPerformance(item.sessionExercise.exerciseId))
                }
                _lastPerformance.value = map
            }
        }
        viewModelScope.launch {
            _session.value = repo.getSession(id)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            val start = _session.value?.startTime ?: System.currentTimeMillis()
            while (true) {
                _elapsedSeconds.value = ((System.currentTimeMillis() - start) / 1000).toInt()
                delay(1000)
            }
        }
    }

    fun addExercise(exerciseId: Long) {
        val id = _sessionId.value ?: return
        viewModelScope.launch { repo.addExerciseToSession(id, exerciseId) }
    }

    fun addSet(sessionExerciseId: Long) {
        viewModelScope.launch { repo.addSet(sessionExerciseId) }
    }

    fun removeExercise(sessionExerciseId: Long) {
        viewModelScope.launch { repo.removeSessionExercise(sessionExerciseId) }
    }

    /** 更新一组：重量/次数/RPE/完成 */
    fun updateSet(set: SetLog, weightKg: Float? = null, reps: Int? = null, rpe: Float? = null) {
        viewModelScope.launch {
            val updated = set.copy(
                weightKg = weightKg ?: set.weightKg,
                reps = reps ?: set.reps,
                rpe = rpe ?: set.rpe,
                completed = true,
            )
            repo.updateSet(updated)
        }
    }

    fun toggleSetComplete(set: SetLog) {
        viewModelScope.launch {
            val willComplete = !set.completed
            repo.updateSet(set.copy(completed = willComplete))
            if (willComplete) startRestTimer()
        }
    }

    fun deleteSet(set: SetLog) {
        viewModelScope.launch { repo.deleteSet(set.id) }
    }

    /** 开始/重置休息计时 */
    fun startRestTimer() {
        viewModelScope.launch {
            val seconds = settingsRepo.settings.first().restSeconds
            startRestTimerWith(seconds)
        }
    }

    private fun startRestTimerWith(seconds: Int) {
        timerJob?.cancel()
        _restTimer.value = RestTimerState(running = true, remainingSeconds = seconds, totalSeconds = seconds)
        timerJob = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                delay(1000)
                left--
                _restTimer.value = _restTimer.value.copy(remainingSeconds = left)
            }
            _restTimer.value = _restTimer.value.copy(running = false)
        }
    }

    fun skipRestTimer() {
        timerJob?.cancel()
        _restTimer.value = _restTimer.value.copy(running = false)
    }

    fun extendRestTimer(extraSeconds: Int) {
        val state = _restTimer.value
        if (!state.running) return
        val left = state.remainingSeconds + extraSeconds
        _restTimer.value = state.copy(remainingSeconds = left)
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var l = left
            while (l > 0) {
                delay(1000)
                l--
                _restTimer.value = _restTimer.value.copy(remainingSeconds = l)
            }
            _restTimer.value = _restTimer.value.copy(running = false)
        }
    }

    /** 完成训练 */
    fun finishWorkout(name: String, note: String, onDone: () -> Unit) {
        val id = _sessionId.value ?: return
        viewModelScope.launch {
            repo.finishSession(id, name, note)
            tickerJob?.cancel()
            timerJob?.cancel()
            onDone()
        }
    }

    /** 放弃训练（删除草稿） */
    fun discardWorkout(onDone: () -> Unit) {
        val id = _sessionId.value ?: return
        viewModelScope.launch {
            repo.discardSession(id)
            tickerJob?.cancel()
            timerJob?.cancel()
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
            initializer { WorkoutViewModel(container.workoutRepository, container.settingsRepository) }
        }
    }
}
