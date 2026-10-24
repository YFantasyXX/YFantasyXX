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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow

class ExercisesViewModel(private val repo: WorkoutRepository) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    private val _category = MutableStateFlow<String?>(null)
    private val _equipment = MutableStateFlow<String?>(null)

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

    fun updateExercise(exercise: Exercise) {
        viewModelScope.launch { repo.updateExercise(exercise) }
    }

    fun deleteExercise(id: Long) {
        viewModelScope.launch { repo.deleteCustomExercise(id) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExercisesViewModel(container.workoutRepository) }
        }
    }
}
