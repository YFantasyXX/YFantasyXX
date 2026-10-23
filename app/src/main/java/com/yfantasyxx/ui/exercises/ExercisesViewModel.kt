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

class ExercisesViewModel(private val repo: WorkoutRepository) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    private val _category = MutableStateFlow<String?>(null)

    val exercises = combine(_keyword, _category) { kw, cat -> kw to cat }
        .flatMapLatest { (kw, cat) -> repo.observeExercises(kw, cat) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKeyword(value: String) { _keyword.value = value }
    fun setCategory(value: String?) { _category.value = value }

    suspend fun getExercise(id: Long): Exercise? = repo.getExercise(id)

    suspend fun getLastPerformance(id: Long): List<SetLog> = repo.lastPerformance(id)

    fun addExercise(name: String, category: String, equipment: String, muscle: String) {
        viewModelScope.launch {
            repo.addCustomExercise(name, category, equipment, muscle)
        }
    }

    suspend fun getBestSet(exerciseId: Long): SetLog? = repo.bestSetFor(exerciseId)

    fun updateExercise(exercise: Exercise) {
        viewModelScope.launch { repo.updateExercise(exercise) }
    }

    fun deleteExercise(id: Long) {
        viewModelScope.launch { repo.deleteCustomExercise(id) }
    }

    companion object {
        val categories = ExerciseCategory.all
        val equipments = Equipment.all

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExercisesViewModel(container.workoutRepository) }
        }
    }
}
