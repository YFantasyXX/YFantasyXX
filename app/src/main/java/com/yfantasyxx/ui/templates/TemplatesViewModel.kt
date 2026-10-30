package com.yfantasyxx.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.ProgressionMode
import com.yfantasyxx.data.RepRange
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
import kotlinx.coroutines.flow.combine

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
    /** 输入框里的原文，允许「8-」这种中间态 */
    val repsText: String,
    /** 解析后的区间；解析失败时保留上一次的有效值 */
    val range: RepRange,
    val targetWeightKg: Float,
    val isWarmup: Boolean,
    val restSeconds: Int?,
    val progression: ProgressionMode = ProgressionMode.OFF,
    val supersetGroup: Int? = null,
)

/** 模板分组（folderId 为 null 表示未分组） */
data class TemplateGroup(
    val folderId: Long?,
    val folderName: String?,
    val templates: List<WorkoutTemplate>,
)

class TemplatesViewModel(private val repo: WorkoutRepository) : ViewModel() {

    val templates: StateFlow<List<WorkoutTemplate>> = repo.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _groups = MutableStateFlow<List<TemplateGroup>>(emptyList())
    val groups: StateFlow<List<TemplateGroup>> = _groups

    private val _folders = MutableStateFlow<List<com.yfantasyxx.data.db.TemplateFolder>>(emptyList())
    val folders: StateFlow<List<com.yfantasyxx.data.db.TemplateFolder>> = _folders

    init {
        viewModelScope.launch {
            combine(repo.observeTemplates(), repo.observeFolders()) { list, folderList ->
                _folders.value = folderList
                val byFolder = list.groupBy { it.folderId }
                buildList {
                    folderList.forEach { folder ->
                        add(
                            TemplateGroup(
                                folderId = folder.id,
                                folderName = folder.name,
                                templates = byFolder[folder.id].orEmpty(),
                            )
                        )
                    }
                    byFolder[null]?.takeIf { it.isNotEmpty() }?.let { loose ->
                        add(TemplateGroup(folderId = null, folderName = null, templates = loose))
                    }
                }
            }.collect { _groups.value = it }
        }
    }

    fun delete(template: WorkoutTemplate) {
        viewModelScope.launch { repo.deleteTemplate(template) }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.createFolder(name) }
    }

    fun renameFolder(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.renameFolder(id, name) }
    }

    /** 切换该文件夹的模板是否在首页显示 */
    fun toggleFolderHome(id: Long, current: Boolean) {
        viewModelScope.launch { repo.setFolderShowOnHome(id, !current) }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch { repo.deleteFolder(id) }
    }

    /** 把模板移动到某个文件夹（追加到末尾） */
    fun moveToFolder(template: WorkoutTemplate, folderId: Long?) {
        if (template.folderId == folderId) return
        viewModelScope.launch {
            // 不能在 _groups 里找「未分组」的条数：未分组为空时这个分组压根不在列表里，
            // 会算成 0，模板反而排到最前面。直接问库里的 MAX(sortOrder)
            val order = repo.nextTemplateOrder(folderId)
            repo.moveTemplate(template.id, folderId, order)
        }
    }

    /** 分组内拖动排序 */
    fun moveInGroup(folderId: Long?, from: Int, to: Int) {
        val list = _groups.value.toMutableList()
        val gi = list.indexOfFirst { it.folderId == folderId }
        if (gi < 0) return
        val items = list[gi].templates.toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        list[gi] = list[gi].copy(templates = items)
        _groups.value = list
        viewModelScope.launch { repo.reorderTemplates(folderId, items.map { it.id }) }
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

    private fun TemplateExerciseWithExercise.toEditable(): EditableTemplateItem {
        val range = templateExercise.let { t ->
            t.targetRepsMin?.let { min -> t.targetRepsMax?.let { max -> RepRange(min, max) } }
        } ?: RepRange.parseOrDefault(templateExercise.targetReps)
        return EditableTemplateItem(
            key = templateExercise.id,
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            targetSets = templateExercise.targetSets,
            repsText = range.label,
            range = range,
            targetWeightKg = templateExercise.targetWeightKg ?: 0f,
            isWarmup = templateExercise.isWarmup,
            restSeconds = templateExercise.restSeconds,
            progression = ProgressionMode.from(templateExercise.progression),
            supersetGroup = templateExercise.supersetGroup,
        )
    }

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
                    repsText = RepRange.DEFAULT.label,
                    range = RepRange.DEFAULT,
                    targetWeightKg = lastWeight,
                    isWarmup = false,
                    restSeconds = null,
                )
            )
            _state.value = _state.value.copy(items = items)
        }
    }

    fun updateItem(
        key: Long,
        sets: Int? = null,
        reps: String? = null,
        weight: Float? = null,
        restSeconds: Int? = null,
        clearRest: Boolean = false,
        progression: ProgressionMode? = null,
        supersetGroup: Int? = null,
    ) {
        val items = _state.value.items.map {
            if (it.key == key) {
                it.copy(
                    targetSets = sets ?: it.targetSets,
                    repsText = reps ?: it.repsText,
                    // 输入中途（如「8-」）解析不出来时保留上一次的有效区间，
                    // 否则每敲一个字符区间就会跳一次
                    range = reps?.let { text -> RepRange.parse(text) ?: it.range } ?: it.range,
                    targetWeightKg = weight ?: it.targetWeightKg,
                    restSeconds = if (clearRest) null else (restSeconds ?: it.restSeconds),
                    progression = progression ?: it.progression,
                    supersetGroup = supersetGroup ?: it.supersetGroup,
                )
            } else it
        }
        _state.value = _state.value.copy(items = items)
    }

    /** 和下一个动作组成超级组 / 退出超级组 */
    fun toggleSuperset(key: Long) {
        val items = _state.value.items.toMutableList()
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        val current = items[index]
        if (current.supersetGroup != null) {
            // 退出：同组不足两人时把另一个也清掉
            val group = current.supersetGroup
            val siblings = items.count { it.supersetGroup == group }
            items[index] = current.copy(supersetGroup = null)
            if (siblings <= 2) {
                items.indices.forEach { i ->
                    if (items[i].supersetGroup == group) {
                        items[i] = items[i].copy(supersetGroup = null)
                    }
                }
            }
        } else {
            if (index == items.lastIndex) return
            val group = (items.mapNotNull { it.supersetGroup }.maxOrNull() ?: 0) + 1
            items[index] = current.copy(supersetGroup = group)
            items[index + 1] = items[index + 1].copy(supersetGroup = group)
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

    fun save(folderId: Long? = null, onDone: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank() || s.items.isEmpty()) return
        viewModelScope.launch {
            repo.saveTemplate(
                templateId = templateId,
                name = s.name,
                note = s.note,
                folderId = folderId,
                items = s.items.map {
                    WorkoutRepository.TemplateExerciseDraft(
                        exerciseId = it.exerciseId,
                        targetSets = it.targetSets,
                        range = it.range,
                        targetWeightKg = it.targetWeightKg.takeIf { w -> w > 0f },
                        isWarmup = it.isWarmup,
                        restSeconds = it.restSeconds,
                        progression = it.progression,
                        supersetGroup = it.supersetGroup,
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
