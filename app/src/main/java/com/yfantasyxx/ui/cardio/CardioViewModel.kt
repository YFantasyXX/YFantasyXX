package com.yfantasyxx.ui.cardio

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.CardioRepository
import com.yfantasyxx.data.CardioType
import com.yfantasyxx.data.db.CardioSession
import com.yfantasyxx.util.CardioClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class CardioUiState(
    val recent: List<CardioSession> = emptyList(),
    val weekCount: Int = 0,
    val weekDurationMs: Long = 0L,
    val weekDistanceKm: Float = 0f,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CardioViewModel(private val repo: CardioRepository) : ViewModel() {

    /**
     * 本周起点做成流：App 常驻跨过周日后，构造时算一次的值会停在上一周。
     * 回到列表页时调 [refreshWeekWindow] 重新取。
     */
    private val weekAnchor = MutableStateFlow(weekStart())

    fun refreshWeekWindow() {
        weekAnchor.value = weekStart()
    }

    val uiState: StateFlow<CardioUiState> = weekAnchor.flatMapLatest { since ->
        combine(
            repo.observeRecent(200),
            repo.observeSince(since),
        ) { recent, week ->
            CardioUiState(
                recent = recent,
                weekCount = week.size,
                weekDurationMs = week.sumOf { it.durationMs },
                weekDistanceKm = week.sumOf { it.distanceKm.toDouble() }.toFloat(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CardioUiState())

    /** 进行中的那段；列表页据此显示「继续」而不是「开始」 */
    private val _active = MutableStateFlow<CardioClock.Active?>(null)
    val active: StateFlow<CardioClock.Active?> = _active.asStateFlow()

    fun refreshActive(context: Context) {
        _active.value = CardioClock.read(context)
    }

    /**
     * 保存一段有氧。
     *
     * [CardioClock] 的清理放在调用方（计时页）里做，不在这里：
     * 保存失败时秒表还得能继续走，不然这段就白做了。
     */
    fun save(
        type: CardioType,
        startedAt: Long,
        durationMs: Long,
        distanceKm: Float,
        note: String,
        manual: Boolean,
        settings: String = "",
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            repo.add(
                type = type,
                startedAt = startedAt,
                durationMs = durationMs,
                distanceKm = distanceKm,
                note = note,
                manual = manual,
                settings = settings,
            )
            onDone()
        }
    }

    fun update(session: CardioSession, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.update(session)
            onDone()
        }
    }

    fun delete(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.delete(id)
            onDone()
        }
    }

    private fun weekStart(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CardioViewModel(container.cardioRepository) }
        }
    }
}
