package com.yfantasyxx.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.CategoryVolume
import com.yfantasyxx.data.db.ExercisePr
import com.yfantasyxx.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class StatsUiState(
    val weekVolumes: List<Pair<String, Float>> = emptyList(),
    val categoryVolumes: List<CategoryVolume> = emptyList(),
    val personalRecords: List<ExercisePr> = emptyList(),
    val weightTrend: List<Pair<String, Float>> = emptyList(),
)

class StatsViewModel(
    private val repo: WorkoutRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val weekMs = 7L * 24 * 3600 * 1000
    private val dayMs = 24L * 3600 * 1000
    private val dateFormat = SimpleDateFormat("M/d", Locale.CHINA)

    /** 派生数据（周容量/部位分布）在数据变化时重算 */
    private val _derived = MutableStateFlow(StatsUiState())

    private val refreshTrigger = MutableStateFlow(0)

    val uiState: StateFlow<StatsUiState> = combine(
        repo.observePersonalRecords(),
        repo.observeBodyMetrics(),
        _derived,
        refreshTrigger,
    ) { prs, weights, derived, _ ->
        derived.copy(
            personalRecords = prs,
            weightTrend = weights.sortedBy { it.date }.takeLast(30).map {
                dateFormat.format(Date(it.date)) to it.weightKg
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    init {
        viewModelScope.launch {
            // 训练记录变化时重算（用 PR flow 作为数据变化信号即可覆盖大多数情况）
            repo.observePersonalRecords().collect {
                val weeks = buildWeekBuckets()
                val cats = repo.volumeByCategory(System.currentTimeMillis() - 30 * dayMs)
                _derived.value = _derived.value.copy(
                    weekVolumes = weeks,
                    categoryVolumes = cats,
                )
            }
        }
    }

    /** 近 8 周训练容量 */
    private suspend fun buildWeekBuckets(): List<Pair<String, Float>> {
        val since = System.currentTimeMillis() - 8 * weekMs
        val raw = repo.volumeByWeek(since)
        val byBucket = raw.associate { it.bucket to it.volume }

        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val thisMonday = cal.timeInMillis
        return (0 until 8).map { i ->
            val ts = thisMonday - (7 - i) * weekMs
            dateFormat.format(Date(ts)) to (byBucket[ts] ?: 0f)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                StatsViewModel(container.workoutRepository, container.settingsRepository)
            }
        }
    }
}
