package com.yfantasyxx.data.seed

import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.ExerciseCategory

/**
 * 内置动作库种子数据（首次启动写入）
 */
object ExerciseSeed {

    val exercises: List<Exercise> = listOf(
        // ---- 胸 ----
        ex("杠铃卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "胸大肌"),
        ex("哑铃卧推", ExerciseCategory.CHEST, Equipment.DUMBBELL, "胸大肌"),
        ex("上斜哑铃卧推", ExerciseCategory.CHEST, Equipment.DUMBBELL, "上胸"),
        ex("上斜杠铃卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "上胸"),
        ex("双杠臂屈伸", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "下胸"),
        ex("俯卧撑", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "胸大肌"),
        ex("器械推胸", ExerciseCategory.CHEST, Equipment.MACHINE, "胸大肌"),
        ex("绳索夹胸", ExerciseCategory.CHEST, Equipment.CABLE, "胸中缝"),
        ex("哑铃飞鸟", ExerciseCategory.CHEST, Equipment.DUMBBELL, "胸大肌"),

        // ---- 背 ----
        ex("引体向上", ExerciseCategory.BACK, Equipment.BODYWEIGHT, "背阔肌"),
        ex("高位下拉", ExerciseCategory.BACK, Equipment.MACHINE, "背阔肌"),
        ex("杠铃划船", ExerciseCategory.BACK, Equipment.BARBELL, "中背部"),
        ex("哑铃单臂划船", ExerciseCategory.BACK, Equipment.DUMBBELL, "背阔肌"),
        ex("坐姿划船", ExerciseCategory.BACK, Equipment.MACHINE, "中背部"),
        ex("直臂下压", ExerciseCategory.BACK, Equipment.CABLE, "背阔肌"),
        ex("杠铃硬拉", ExerciseCategory.BACK, Equipment.BARBELL, "下背/腘绳肌"),
        ex("罗马尼亚硬拉", ExerciseCategory.BACK, Equipment.BARBELL, "腘绳肌"),

        // ---- 腿 ----
        ex("杠铃深蹲", ExerciseCategory.LEGS, Equipment.BARBELL, "股四头肌"),
        ex("哑铃深蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "股四头肌"),
        ex("前蹲", ExerciseCategory.LEGS, Equipment.BARBELL, "股四头肌"),
        ex("腿举", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌"),
        ex("哈克深蹲", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌"),
        ex("罗马尼亚硬拉(腿)", ExerciseCategory.LEGS, Equipment.BARBELL, "腘绳肌"),
        ex("腿弯举", ExerciseCategory.LEGS, Equipment.MACHINE, "腘绳肌"),
        ex("腿屈伸", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌"),
        ex("保加利亚分腿蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "臀/股四"),
        ex("臀桥", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, "臀大肌"),
        ex("提踵", ExerciseCategory.LEGS, Equipment.MACHINE, "小腿"),

        // ---- 肩 ----
        ex("杠铃推举", ExerciseCategory.SHOULDERS, Equipment.BARBELL, "三角肌前束"),
        ex("哑铃推举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌"),
        ex("哑铃侧平举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌中束"),
        ex("绳索侧平举", ExerciseCategory.SHOULDERS, Equipment.CABLE, "三角肌中束"),
        ex("俯身哑铃飞鸟", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌后束"),
        ex("面拉", ExerciseCategory.SHOULDERS, Equipment.CABLE, "三角肌后束"),
        ex("器械推肩", ExerciseCategory.SHOULDERS, Equipment.MACHINE, "三角肌"),

        // ---- 手臂 ----
        ex("杠铃弯举", ExerciseCategory.ARMS, Equipment.BARBELL, "肱二头肌"),
        ex("哑铃弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱二头肌"),
        ex("锤式弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱肌/肱二头"),
        ex("绳索下压", ExerciseCategory.ARMS, Equipment.CABLE, "肱三头肌"),
        ex("哑铃颈后臂屈伸", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱三头肌"),
        ex("窄距卧推", ExerciseCategory.ARMS, Equipment.BARBELL, "肱三头肌"),
        ex("双杠下压(三头)", ExerciseCategory.ARMS, Equipment.BODYWEIGHT, "肱三头肌"),

        // ---- 核心 ----
        ex("卷腹", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "腹直肌"),
        ex("悬垂举腿", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "下腹"),
        ex("平板支撑", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "核心"),
        ex("死虫式", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "核心稳定"),
        ex("俄罗斯转体", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "腹斜肌"),
        ex("山羊挺身", ExerciseCategory.CORE, Equipment.MACHINE, "下背"),

        // ---- 有氧 ----
        ex("跑步机跑步", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺"),
        ex("椭圆机", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺"),
        ex("动感单车", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺"),
        ex("划船机", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺/全身"),
        ex("跳绳", ExerciseCategory.CARDIO, Equipment.OTHER, "心肺/小腿"),

        // ---- 全身 ----
        ex("高翻", ExerciseCategory.FULL_BODY, Equipment.BARBELL, "全身爆发"),
        ex("壶铃摆荡", ExerciseCategory.FULL_BODY, Equipment.KETTLEBELL, "后链"),
        ex("波比跳", ExerciseCategory.FULL_BODY, Equipment.BODYWEIGHT, "全身"),
        ex("土耳其起立", ExerciseCategory.FULL_BODY, Equipment.KETTLEBELL, "全身"),
    )

    private fun ex(
        name: String,
        category: String,
        equipment: String,
        muscle: String,
    ) = Exercise(
        name = name,
        category = category,
        equipment = equipment,
        primaryMuscle = muscle,
        isCustom = false,
    )
}
