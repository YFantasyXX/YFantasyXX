package com.yfantasyxx.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.BodyMetric
import com.yfantasyxx.data.db.CategoryVolume
import com.yfantasyxx.data.db.ExercisePr
import com.yfantasyxx.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /** 已经算过的数据版本，用来判断 PR 是复用缓存还是重算 */
    private var computedVersion = -1

    /**
     * 数据版本（任何影响统计的写入 +1）和体重记录一起驱动重算。
     *
     * 原来 PR 是 `observePersonalRecords()` 这个 Room Flow：
     * 训练中每改一次重量都会重跑整个历史的三表 join + GROUP BY，
     * 而且这个 Flow 还兼作「周容量 / 分类容量」的重算信号，一次写入触发四次查询。
     * 现在 PR 走仓库缓存（只在勾选完成 / 删除 / 结束训练时失效），
     * 周容量与分类容量按版本号重算。
     */
    val uiState: StateFlow<StatsUiState> = combine(
        repo.observeDataVersion(),
        repo.observeBodyMetrics(),
    ) { version, weights -> version to weights }
        .map { (version, weights) -> build(weights, version) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private suspend fun build(weights: List<BodyMetric>, version: Int): StatsUiState {
        val refresh = version != computedVersion
        computedVersion = version
        return StatsUiState(
            weekVolumes = buildWeekBuckets(),
            categoryVolumes = repo.volumeByCategory(System.currentTimeMillis() - 30 * dayMs),
            personalRecords = repo.personalRecords(refresh = refresh),
            weightTrend = weights.sortedBy { it.date }.takeLast(30).map {
                dateFormat.format(Date(it.date)) to it.weightKg
            },
        )
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
