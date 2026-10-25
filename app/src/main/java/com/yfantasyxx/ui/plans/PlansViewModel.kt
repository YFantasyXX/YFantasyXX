package com.yfantasyxx.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.PlanDay
import com.yfantasyxx.data.db.PlanEntity
import com.yfantasyxx.data.db.WorkoutTemplate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 训练计划的列表 / 编辑 */
class PlansViewModel(private val repo: WorkoutRepository) : ViewModel() {

    val plans: StateFlow<List<PlanEntity>> = repo.observePlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val days: StateFlow<List<PlanDay>> = repo.observePlanDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val templates: StateFlow<List<WorkoutTemplate>> = repo.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [planId] 为 null 表示新建 */
    fun save(planId: Long?, name: String, dayTemplateIds: List<Long?>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (planId == null) {
                repo.createPlan(name, dayTemplateIds)
            } else {
                repo.updatePlan(planId, name, dayTemplateIds)
            }
            onDone()
        }
    }

    fun delete(planId: Long) {
        viewModelScope.launch { repo.deletePlan(planId) }
    }

    /** 同时只能开一个：关掉传 null */
    fun setActive(planId: Long?) {
        viewModelScope.launch { repo.setActivePlan(planId) }
    }

    fun reset(planId: Long) {
        viewModelScope.launch { repo.resetPlanProgress(planId) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PlansViewModel(container.workoutRepository) }
        }
    }
}
