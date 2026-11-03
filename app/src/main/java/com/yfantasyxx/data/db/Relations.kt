package com.yfantasyxx.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Relation

/**
 * Room 关系查询容器。
 * 注意定义顺序：被引用的类型必须定义在其使用者之前（KSP 解析要求）。
 *
 * 为什么标 `@Immutable`：这些类带 `List` 字段，Compose 编译器无法证明
 * 集合不可变，会把整个类型判成 unstable；强跳过模式下 unstable 参数
 * 按**实例**比较，而 Room 每次发射都会新建全套对象 —— 于是勾一组、
 * 改一个重量，屏幕上所有可见的动作卡都要重组一遍。
 * 标上 `@Immutable` 后按 `equals` 比较，只有内容真的变了的那张卡才重组。
 * 前提是这些集合确实只读：库这边没有任何地方原地 add/sort。
 */

/** 模板中的单个动作（含动作实体） */
@Immutable
data class TemplateExerciseWithExercise(
    @Embedded val templateExercise: TemplateExercise,
    @Relation(
        parentColumn = "exerciseId",
        entityColumn = "id"
    )
    val exercise: Exercise,
)

/** 训练中的单个动作（含动作实体与所有组） */
@Immutable
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
@Immutable
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
@Immutable
data class SessionWithDetails(
    @Embedded val session: WorkoutSession,
    @Relation(
        entity = SessionExercise::class,
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val exercises: List<SessionExerciseWithSets>,
)
