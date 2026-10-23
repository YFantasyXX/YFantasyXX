package com.yfantasyxx.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 动作分类 */
object ExerciseCategory {
    const val CHEST = "胸"
    const val BACK = "背"
    const val LEGS = "腿"
    const val SHOULDERS = "肩"
    const val ARMS = "手臂"
    const val CORE = "核心"
    const val CARDIO = "有氧"
    const val FULL_BODY = "全身"

    val all = listOf(CHEST, BACK, LEGS, SHOULDERS, ARMS, CORE, CARDIO, FULL_BODY)
}

/** 器械类型 */
object Equipment {
    const val BARBELL = "杠铃"
    const val DUMBBELL = "哑铃"
    const val MACHINE = "器械"
    const val CABLE = "绳索"
    const val BODYWEIGHT = "自重"
    const val KETTLEBELL = "壶铃"
    const val BAND = "弹力带"
    const val OTHER = "其他"

    val all = listOf(BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, KETTLEBELL, BAND, OTHER)
}

/**
 * 动作库
 */
@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 分类，见 [ExerciseCategory] */
    val category: String,
    /** 器械，见 [Equipment] */
    val equipment: String = Equipment.OTHER,
    val primaryMuscle: String = "",
    val note: String = "",
    /** 该动作的默认组间休息秒数；null 表示使用全局默认 */
    val restSeconds: Int? = null,
    /** 用户自定义动作 */
    val isCustom: Boolean = false,
    /** 归档（隐藏但保留历史关联） */
    val archived: Boolean = false,
)

/**
 * 训练模板
 */
@Entity(tableName = "templates")
data class WorkoutTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 所属文件夹；null 表示未分组 */
    val folderId: Long? = null,
    /** 同组内排序 */
    val sortOrder: Int = 0,
)

/**
 * 模板文件夹（用于给模板分类）
 */
@Entity(tableName = "template_folders")
data class TemplateFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    /** 该文件夹下的模板是否在首页快捷开始区显示 */
    val showOnHome: Boolean = true,
)

/**
 * 模板内的动作条目
 */
@Entity(
    tableName = "template_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("templateId"), Index("exerciseId")]
)
data class TemplateExercise(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val templateId: Long,
    val exerciseId: Long,
    val sortOrder: Int = 0,
    val targetSets: Int = 3,
    val targetReps: String = "8-12",
    /** 目标重量（kg）；null 表示不指定，训练时用上次重量或手动填 */
    val targetWeightKg: Float? = null,
    /** 该动作是否作为热身动作 */
    val isWarmup: Boolean = false,
    /** 模板内为该动作指定的休息秒数；null 表示沿用动作默认/全局 */
    val restSeconds: Int? = null,
)

/**
 * 一次训练记录（endTime 为 null 表示进行中的草稿）
 */
@Entity(tableName = "sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val templateId: Long? = null,
    val startTime: Long,
    /** 真正开始计时的时刻：第一组完成时写入；null 表示还没开始练 */
    val activeStartedAt: Long? = null,
    val endTime: Long? = null,
    val note: String = "",
)

/**
 * 训练内的动作条目
 */
@Entity(
    tableName = "session_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("exerciseId")]
)
data class SessionExercise(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val sortOrder: Int = 0,
    /** 本次训练该动作的休息秒数（来自模板设定）；null 表示沿用动作/全局 */
    val restSeconds: Int? = null,
    /** 模板计划的组数（快照，模板后续修改不影响历史记录）；null 表示手动添加 */
    val plannedSets: Int? = null,
)

/**
 * 一组训练记录
 */
@Entity(
    tableName = "set_logs",
    foreignKeys = [
        ForeignKey(
            entity = SessionExercise::class,
            parentColumns = ["id"],
            childColumns = ["sessionExerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionExerciseId")]
)
data class SetLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionExerciseId: Long,
    val setNumber: Int,
    /** 重量（kg），自重动作为 0 */
    val weightKg: Float = 0f,
    val reps: Int = 0,
    /** 自觉费力程度 1-10，可空 */
    val rpe: Float? = null,
    /** 热身组 */
    val isWarmup: Boolean = false,
    /** 冲 PR 的极限组：计入 PR 榜，但不作为下次训练的参考重量 */
    val isPr: Boolean = false,
    /** 是否已完成 */
    val completed: Boolean = false,
    val loggedAt: Long = System.currentTimeMillis(),
)

/**
 * 身体数据记录（体重等）
 */
@Entity(tableName = "body_metrics", indices = [Index("date")])
data class BodyMetric(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 当天 0 点时间戳，用于 upsert */
    val date: Long,
    val weightKg: Float,
    val bodyFatPercent: Float? = null,
    val note: String = "",
)

// ---------- 关系查询用（见 Relations.kt）----------
data class SessionSummary(
    val id: Long,
    val name: String,
    val templateId: Long?,
    val startTime: Long,
    val endTime: Long?,
    val exerciseCount: Int,
    val setCount: Int,
    /** 总容量 kg*reps */
    val totalVolume: Float,
)

/** 训练中草稿用的摘要 */
data class ActiveSessionSummary(
    val id: Long,
    val name: String,
    val startTime: Long,
    val exerciseCount: Int,
    val completedSetCount: Int,
    val plannedSetCount: Int,
)

/** 统计：某分类的容量 */
data class CategoryVolume(
    val category: String,
    val volume: Float,
)

/** 统计：单个动作的 PR */
data class ExercisePr(
    val exerciseId: Long,
    val exerciseName: String,
    val bestWeightKg: Float,
    val bestReps: Int,
    val bestVolume: Float,
)
