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
    val targetWeightKg: Float,
    val isWarmup: Boolean,
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
        targetWeightKg = templateExercise.targetWeightKg ?: 0f,
        isWarmup = templateExercise.isWarmup,
    )

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setNote(value: String) {
        _state.value = _state.value.copy(note = value)
    }

    /** 添加动作：若该动作练过，自动带入上次的重量 */
    fun addExercise(exercise: Exercise) {
        viewModelScope.launch {
            val lastWeight = runCatching {
                repo.lastPerformance(exercise.id).firstOrNull()?.weightKg
            }.getOrNull() ?: 0f
            val items = _state.value.items.toMutableList()
            items.add(
                EditableTemplateItem(
                    key = nextKey--,
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    targetSets = 3,
                    targetReps = "8-12",
                    targetWeightKg = lastWeight,
                    isWarmup = false,
                )
            )
            _state.value = _state.value.copy(items = items)
        }
    }

    fun updateItem(key: Long, sets: Int? = null, reps: String? = null, weight: Float? = null) {
        val items = _state.value.items.map {
            if (it.key == key) {
                it.copy(
                    targetSets = sets ?: it.targetSets,
                    targetReps = reps ?: it.targetReps,
                    targetWeightKg = weight ?: it.targetWeightKg,
                )
            } else it
        }
        _state.value = _state.value.copy(items = items)
    }

    /** 新建自定义动作并直接加入模板 */
    fun addCustomExercise(name: String, category: String, equipment: String, muscle: String) {
        viewModelScope.launch {
            runCatching {
                val id = repo.addCustomExercise(name, category, equipment, muscle)
                repo.getExercise(id)
            }.getOrNull()?.let { addExercise(it) }
        }
    }

    /** 切换热身标记 */
    fun toggleWarmup(key: Long) {
        val items = _state.value.items.map {
            if (it.key == key) it.copy(isWarmup = !it.isWarmup) else it
        }
        _state.value = _state.value.copy(items = items)
    }

    /** 拖动排序：按 key 定位，避免闭包里的 index 过期 */
    fun moveItemByKey(key: Long, offset: Int) {
        val items = _state.value.items.toMutableList()
        val from = items.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, items.lastIndex)
        if (to == from) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        _state.value = _state.value.copy(items = items)
    }

    /** 拖动排序 */
    fun moveItem(from: Int, to: Int) {
        val items = _state.value.items.toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
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
                        targetWeightKg = it.targetWeightKg.takeIf { w -> w > 0f },
                        isWarmup = it.isWarmup,
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
