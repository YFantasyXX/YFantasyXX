package com.yfantasyxx.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.TrainingPlan
import com.yfantasyxx.data.db.ActiveSessionSummary
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.data.db.WorkoutTemplate
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.data.prefs.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class HomeUiState(
    val activeSession: ActiveSessionSummary? = null,
    val templates: List<WorkoutTemplate> = emptyList(),
    val recentSessions: List<SessionSummary> = emptyList(),
    val weekSessionCount: Int = 0,
    val weekVolume: Float = 0f,
    val streakWeeks: Int = 0,
    val settings: AppSettings = AppSettings(),
    val weightUnitLabel: String = "kg",
    val latestWeightKg: Float? = null,
    /** 循环训练计划的当前状态；没启用轮换时为 null */
    val plan: TrainingPlan? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: WorkoutRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // combine 有 5 个以上源时只能拿到 Array<Any?>，逐个 as 转换无法避免；
    // 注解放属性上才能覆盖整个 lambda（放单行只对那一行生效）
    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<HomeUiState> = combine(
        repo.observeActiveSession(),
        repo.observeHomeTemplates(),
        repo.observeHistory(),
        repo.observeSessionCount(weekStart()),
        repo.observeTotalVolume(weekStart()),
        settingsRepo.settings,
        repo.observeActivePlan(),
    ) { values ->
        val active = values[0] as ActiveSessionSummary?
        val templates = values[1] as List<WorkoutTemplate>
        val sessions = values[2] as List<SessionSummary>
        val weekCount = values[3] as Int
        val weekVolume = values[4] as Float
        val settings = values[5] as AppSettings
        val plan = values[6] as TrainingPlan?
        HomeUiState(
            activeSession = active,
            templates = templates,
            recentSessions = sessions.take(5),
            weekSessionCount = weekCount,
            weekVolume = weekVolume,
            streakWeeks = computeStreak(sessions),
            settings = settings,
            weightUnitLabel = settings.weightUnit.label,
            latestWeightKg = null,
            plan = plan,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    val latestWeightKg: StateFlow<Float?> = repo.observeLatestWeight()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun startWorkout(templateId: Long?, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.startSession(templateId = templateId)
            onReady(id)
        }
    }

    fun recordWeight(weightKg: Float) {
        viewModelScope.launch {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            repo.saveWeight(today, weightKg)
        }
    }

    /** 休息日再多休一天（只改进度，不动计划结构） */
    fun extendRestDay(planId: Long) {
        viewModelScope.launch { repo.extendRestDay(planId) }
    }

    /** 跳过今天这一天，主动放弃这一次训练 */
    fun skipPlanDay(planId: Long) {
        viewModelScope.launch { repo.skipPlanDay(planId) }
    }

    private fun weekStart(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 连续有训练的周数（含本周） */
    private fun computeStreak(sessions: List<SessionSummary>): Int {
        if (sessions.isEmpty()) return 0
        val weeks = sessions.map { session ->
            Calendar.getInstance().apply {
                timeInMillis = session.startTime
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.distinct().sortedDescending()

        var streak = 0
        var cursor = weekStart()
        for (w in weeks) {
            if (w == cursor) {
                streak++
                cursor -= 7L * 24 * 3600 * 1000
            } else if (w < cursor) {
                break
            }
        }
        return streak
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container.workoutRepository, container.settingsRepository) }
        }
    }
}
