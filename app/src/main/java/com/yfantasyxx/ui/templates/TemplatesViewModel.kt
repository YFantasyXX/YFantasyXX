package com.yfantasyxx.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.TemplateExerciseWithExercise
import com.yfantasyxx.data.db.TemplateWithExercises
import com.yfantasyxx.data.db.WorkoutTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TemplateEditUiState(
    val name: String = "",
    val note: String = "",
    val items: List<EditableTemplateItem> = emptyList(),
    val loading: Boolean = true,
)

data class EditableTemplateItem(
    val key: Long,
    val exerciseId: Long,
    val exerciseName: String,
    val targetSets: Int,
    val targetReps: String,
)

class TemplatesViewModel(private val repo: WorkoutRepository) : ViewModel() {

    val templates: StateFlow<List<WorkoutTemplate>> = repo.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(template: WorkoutTemplate) {
        viewModelScope.launch { repo.deleteTemplate(template) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TemplatesViewModel(container.workoutRepository) }
        }
    }
}

class TemplateEditViewModel(private val repo: WorkoutRepository) : ViewModel() {

    private val _state = MutableStateFlow(TemplateEditUiState())
    val state: StateFlow<TemplateEditUiState> = _state

    private var templateId: Long? = null
    private var nextKey = -1L

    fun load(id: Long?) {
        templateId = id
        if (id == null) {
            _state.value = TemplateEditUiState(loading = false)
            return
        }
        viewModelScope.launch {
            val tpl: TemplateWithExercises? = repo.getTemplate(id)
            _state.value = TemplateEditUiState(
                name = tpl?.template?.name ?: "",
                note = tpl?.template?.note ?: "",
                items = tpl?.items?.map { it.toEditable() } ?: emptyList(),
                loading = false,
            )
        }
    }

    private fun TemplateExerciseWithExercise.toEditable() = EditableTemplateItem(
        key = templateExercise.id,
        exerciseId = exercise.id,
        exerciseName = exercise.name,
        targetSets = templateExercise.targetSets,
        targetReps = templateExercise.targetReps,
    )

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setNote(value: String) {
        _state.value = _state.value.copy(note = value)
    }

    fun addExercise(exercise: Exercise) {
        val items = _state.value.items.toMutableList()
        items.add(
            EditableTemplateItem(
                key = nextKey--,
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                targetSets = 3,
                targetReps = "8-12",
            )
        )
        _state.value = _state.value.copy(items = items)
    }

    fun updateItem(key: Long, sets: Int? = null, reps: String? = null) {
        val items = _state.value.items.map {
            if (it.key == key) {
                it.copy(
                    targetSets = sets ?: it.targetSets,
                    targetReps = reps ?: it.targetReps,
                )
            } else it
        }
        _state.value = _state.value.copy(items = items)
    }

    fun removeItem(key: Long) {
        _state.value = _state.value.copy(items = _state.value.items.filterNot { it.key == key })
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank() || s.items.isEmpty()) return
        viewModelScope.launch {
            repo.saveTemplate(
                templateId = templateId,
                name = s.name,
                note = s.note,
                items = s.items.map {
                    WorkoutRepository.TemplateExerciseDraft(
                        exerciseId = it.exerciseId,
                        targetSets = it.targetSets,
                        targetReps = it.targetReps,
                    )
                },
            )
            onDone()
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TemplateEditViewModel(container.workoutRepository) }
        }
    }
}
