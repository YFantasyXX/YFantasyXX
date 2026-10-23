package com.yfantasyxx.data.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Room 关系查询容器。
 * 注意定义顺序：被引用的类型必须定义在其使用者之前（KSP 解析要求）。
 */

/** 模板中的单个动作（含动作实体） */
data class TemplateExerciseWithExercise(
    @Embedded val templateExercise: TemplateExercise,
    @Relation(
        parentColumn = "exerciseId",
        entityColumn = "id"
    )
    val exercise: Exercise,
)

/** 训练中的单个动作（含动作实体与所有组） */
data class SessionExerciseWithSets(
    @Embedded val sessionExercise: SessionExercise,
    @Relation(
        parentColumn = "exerciseId",
        entityColumn = "id"
    )
    val exercise: Exercise,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionExerciseId"
    )
    val sets: List<SetLog>,
)

/** 模板 + 动作列表 */
data class TemplateWithExercises(
    @Embedded val template: WorkoutTemplate,
    @Relation(
        entity = TemplateExercise::class,
        parentColumn = "id",
        entityColumn = "templateId"
    )
    val items: List<TemplateExerciseWithExercise>,
)

/** 训练 + 动作列表 */
data class SessionWithDetails(
    @Embedded val session: WorkoutSession,
    @Relation(
        entity = SessionExercise::class,
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val exercises: List<SessionExerciseWithSets>,
)
