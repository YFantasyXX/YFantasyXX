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
    /**
     * 次要肌群，逗号分隔（如「肱三头肌,三角肌前束」）。
     *
     * 热力图和疲劳度按「主肌群计 1 组、次肌群计 0.5 组」折算，
     * 否则卧推只算胸、不算三头与前束，胸日看起来练了很多、手臂却永远是 0。
     */
    val secondaryMuscles: String = "",
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
    /**
     * 计划轮换：把文件夹里勾了此项的模板当成 A/B/C 循环，
     * 首页给出「练完 A 下一个是 B」的入口，而不是按星期几排。
     */
    val rotation: Boolean = false,
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
    /** 旧字段：自由文本（「8-12」）。只作为兼容保留，逻辑一律看 min/max */
    val targetReps: String = "8-12",
    /** 目标次数下限（含）。null 表示没有设定 */
    val targetRepsMin: Int? = null,
    /** 目标次数上限（含）。等于下限时表示固定次数 */
    val targetRepsMax: Int? = null,
    /** 目标重量（kg）；null 表示不指定，训练时用上次重量或手动填 */
    val targetWeightKg: Float? = null,
    /** 该动作是否作为热身动作 */
    val isWarmup: Boolean = false,
    /** 模板内为该动作指定的休息秒数；null 表示沿用动作默认/全局 */
    val restSeconds: Int? = null,
    /** 进阶策略，见 [com.yfantasyxx.data.ProgressionMode] */
    val progression: Int = 0,
    /** 超级组编号：相同非空值的相邻动作为一组，交替完成、只在组末休息 */
    val supersetGroup: Int? = null,
)

/**
 * 一次训练记录（endTime 为 null 表示进行中的草稿）
 *
 * startTime / endTime 都建了索引：
 * 四条统计查询全部按 startTime 范围过滤，getActiveDraft 是
 * 「WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1」，
 * 没有索引时两者都是全表扫描。
 */
@Entity(
    tableName = "sessions",
    indices = [Index("startTime"), Index("endTime")]
)
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
    /** 模板设定的目标次数（如 8-12），快照保存。旧字段，仅兼容 */
    val plannedReps: String? = null,
    /** 目标次数下限快照 */
    val plannedRepsMin: Int? = null,
    /** 目标次数上限快照 */
    val plannedRepsMax: Int? = null,
    /** 超级组编号（来自模板或训练中手工编组） */
    val supersetGroup: Int? = null,
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

/**
 * 统计：单个动作的 PR。
 *
 * 口径统一到「估算 1RM」，而不是最大重量：
 * 100 kg × 10 的 e1RM ≈ 131，明显强于 110 kg × 3 的 e1RM ≈ 119，
 * 但按最大重量排会把后者排到前面。
 *
 * [bestWeightKg] / [bestReps] 是产生 [estOneRm] 的那一组，
 * 也就是说这两个数是**同一组里真实发生过的**，
 * 而不是把「历史最大重量」和「历史最大次数」各自取极值拼出来。
 */
data class ExercisePr(
    val exerciseId: Long,
    val exerciseName: String,
    /** 估算 1RM（kg）；该动作所有工作组都是 12 次以上时无法估算，为 null */
    val estOneRm: Float?,
    val bestWeightKg: Float,
    val bestReps: Int,
    /** 历史最大单组容量 weight × reps */
    val bestVolume: Float,
)

/** PR 计算的原始行：只取需要的列，1RM 融合公式在 Kotlin 侧算 */
data class PrSetRow(
    val exerciseId: Long,
    val exerciseName: String,
    val weightKg: Float,
    val reps: Int,
)

/**
 * e1RM 曲线上的一个点：某次训练里该动作的最好一组。
 *
 * 按「每次训练一个点」而不是「每组一个点」：
 * 一次训练里 4 组通常一路递减，全画出来是一串锯齿，趋势看不出来。
 */
data class E1rmPoint(
    val sessionId: Long,
    val startTime: Long,
    val e1rm: Float,
    val weightKg: Float,
    val reps: Int,
)

/** 算 e1RM 曲线的原始行：一个动作的全部工作组 */
data class E1rmRow(
    val sessionId: Long,
    val startTime: Long,
    val weightKg: Float,
    val reps: Int,
)

/**
 * 肌群负荷的一行原始数据：一个动作在窗口期内练了多少组。
 * 次要肌群在 Kotlin 侧按 0.5 折算。
 */
data class MuscleSetRow(
    val primaryMuscle: String,
    val secondaryMuscles: String,
    val sets: Int,
    val lastTrainedAt: Long,
)

/** 热力图上的一个肌群 */
data class MuscleLoad(
    val muscle: String,
    /** 折算后的有效组数（主 1.0 / 次 0.5） */
    val sets: Float,
    /** 最近一次练到的时间戳；0 表示窗口期内没练过 */
    val lastTrainedAt: Long,
)
