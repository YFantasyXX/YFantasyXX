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
    val muscleLoads: List<com.yfantasyxx.data.db.MuscleLoad> = emptyList(),
    /** key = LiftPattern.value；只包含真的攒出样本的档 */
    val curves: Map<Int, com.yfantasyxx.data.PatternCalibration> = emptyMap(),
)

class StatsViewModel(
    private val repo: WorkoutRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

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
            // 热力图看 7 天：一个肌群一周练 10-20 组是常见区间，
            // 只看 3 天会看不出「这周还没练过」的空白
            muscleLoads = repo.muscleLoads(System.currentTimeMillis() - 7 * dayMs),
            // 校准结果也要能看见：PR 数字随曲线变，用户得知道现在用的是哪条
            curves = repo.calibration(),
        )
    }

    /**
     * 近 8 周训练容量。
     *
     * SQL 只回「每场训练的开始时间 + 容量」，周归属在这里按**本地日历**算：
     * 以前 SQL 用 `(startTime / weekMs) * weekMs` 分桶，桶边界在 UTC 周四，
     * 与界面用的「本地周一」永远对不上，整张图因此一直是空的。
     */
    private suspend fun buildWeekBuckets(): List<Pair<String, Float>> {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val thisMonday = cal.timeInMillis
        // 用日历往前推，不用「减 7×24h」：夏令时地区减毫秒会落到周日 23:00，
        // 那一周的柱子又变成 0
        fun mondayBefore(weeks: Int): Long = Calendar.getInstance().apply {
            timeInMillis = thisMonday
            add(Calendar.DAY_OF_YEAR, -7 * weeks)
        }.timeInMillis

        // 多取一周，避免最早那一周正好落在边界上被漏掉
        val raw = repo.sessionVolumes(mondayBefore(9))
        val byBucket = HashMap<Long, Float>()
        raw.forEach { row ->
            val monday = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                timeInMillis = row.startTime
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            byBucket[monday] = (byBucket[monday] ?: 0f) + row.volume
        }
        return (0 until 8).map { i ->
            val ts = mondayBefore(7 - i)
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
