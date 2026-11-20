package com.yfantasyxx.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.ExerciseCategory
import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.SetLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class ExercisesViewModel(private val repo: WorkoutRepository) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    private val _category = MutableStateFlow<String?>(null)
    private val _equipment = MutableStateFlow<String?>(null)

    /**
     * 关键词以 ViewModel 为唯一来源。
     *
     * 界面里另存一份 remember 的话，从动作详情返回时组合被重建、输入框清空，
     * 但 ViewModel 仍在用旧词过滤 —— 列表被筛着、搜索框却是空的，看起来像坏了。
     */
    val keyword = _keyword.asStateFlow()

    /** 分类：预设顺序优先，再补充数据库中出现的自定义分类 */
    val categories = repo.observeExerciseCategories()
        .map { dbList ->
            (ExerciseCategory.all + dbList.filterNot { it in ExerciseCategory.all }).distinct()
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ExerciseCategory.all,
        )

    /** 器械：跟随当前分类，只列出该分类下实际存在的器械 */
    val equipments = _category
        .flatMapLatest { cat -> repo.observeExerciseEquipments(cat) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 切换分类后，若已选器械在新分类里不存在则自动取消
        viewModelScope.launch {
            equipments.collect { list ->
                _equipment.value?.let { current ->
                    if (current !in list) _equipment.value = null
                }
            }
        }
    }

    val exercises = combine(_keyword, _category, _equipment) { kw, cat, eq -> Triple(kw, cat, eq) }
        .flatMapLatest { (kw, cat, eq) -> repo.observeExercises(kw, cat, eq) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 动作库总条数（不受搜索与筛选影响）。
     *
     * 和 [exercises] 分开算：列表会给筛选后的结果，用户想看「库里到底有多少条」
     * 时需要一个不受筛选影响的基准。
     */
    val totalCount = repo.observeExerciseCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val selectedCategory = _category.asStateFlow()
    val selectedEquipment = _equipment.asStateFlow()

    fun setKeyword(value: String) { _keyword.value = value }
    fun setCategory(value: String?) { _category.value = value }
    fun setEquipment(value: String?) { _equipment.value = value }

    suspend fun getExercise(id: Long): Exercise? = repo.getExercise(id)

    suspend fun getLastPerformance(id: Long): List<SetLog> = repo.lastPerformance(id)

    fun addExercise(name: String, category: String, equipment: String, muscle: String) {
        viewModelScope.launch {
            repo.addCustomExercise(name, category, equipment, muscle)
        }
    }

    suspend fun getBestSet(exerciseId: Long): SetLog? = repo.bestSetFor(exerciseId)

    /** 该动作的 e1RM 曲线（一次训练一个点） */
    suspend fun getE1rmHistory(exerciseId: Long): List<com.yfantasyxx.data.db.E1rmPoint> =
        repo.e1rmHistory(exerciseId)

    /** 这条曲线是按群体均值还是个人校准算的，以及样本量 */
    suspend fun getCurveHint(exerciseId: Long): com.yfantasyxx.data.CurveHint =
        repo.curveHint(exerciseId)

    fun updateExercise(exercise: Exercise) {
        viewModelScope.launch { repo.updateExercise(exercise) }
    }

    fun deleteExercise(id: Long, onDone: (archived: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // 练过的动作只会被归档（历史记录保留），没练过的才真删
            val archived = runCatching { repo.deleteCustomExercise(id) }.getOrDefault(false)
            onDone(archived)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExercisesViewModel(container.workoutRepository) }
        }
    }
}
