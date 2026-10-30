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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

    /**
     * 本周起点。
     *
     * 必须是个流而不是构造时算一次的值：App 常驻后台跨过周日后，
     * 固定下来的 since 仍是上一周，本周次数/容量会把上周一起算进去。
     * 界面回到首页时调用 [refreshWeekWindow] 重新取一次。
     */
    private val weekAnchor = MutableStateFlow(weekStart())

    fun refreshWeekWindow() {
        weekAnchor.value = weekStart()
    }

    // combine 有 5 个以上源时只能拿到 Array<Any?>，逐个 as 转换无法避免；
    // 注解放属性上才能覆盖整个 lambda（放单行只对那一行生效）
    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<HomeUiState> = weekAnchor.flatMapLatest { since ->
        combine(
            repo.observeActiveSession(),
            repo.observeHomeTemplates(),
            repo.observeHistory(),
            repo.observeSessionCount(since),
            repo.observeTotalVolume(since),
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
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    val latestWeightKg: StateFlow<Float?> = repo.observeLatestWeight()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 连点「开始训练」会压两层训练页、各自跑一套计时器，所以只放行一次 */
    private var starting = false

    fun startWorkout(templateId: Long?, onReady: (Long) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            runCatching { repo.startSession(templateId = templateId) }
                .onSuccess { id ->
                    onReady(id)
                    starting = false
                }
                .onFailure { starting = false }
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
