package com.yfantasyxx.data.seed

import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.ExerciseCategory

/**
 * 内置动作库种子数据。
 *
 * 首次启动写入；老用户升级时由 `WorkoutRepository.seedMissing()` 补齐缺的那些
 * （按名字去重，不覆盖用户改过的记录）。
 *
 * 每个动作都带次要肌群：肌群热力图要按「主肌群 1 组、次肌群 0.5 组」折算，
 * 否则卧推只会算到胸，三头和前束永远是 0，热力图就失真了。
 */
object ExerciseSeed {

    val exercises: List<Exercise> = listOf(
        // ---------------- 胸 ----------------
        ex("杠铃卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "胸大肌", "肱三头肌,三角肌前束"),
        ex("哑铃卧推", ExerciseCategory.CHEST, Equipment.DUMBBELL, "胸大肌", "肱三头肌,三角肌前束"),
        ex("上斜哑铃卧推", ExerciseCategory.CHEST, Equipment.DUMBBELL, "上胸", "三角肌前束,肱三头肌"),
        ex("上斜杠铃卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "上胸", "三角肌前束,肱三头肌"),
        ex("下斜杠铃卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "下胸", "肱三头肌"),
        ex("下斜哑铃卧推", ExerciseCategory.CHEST, Equipment.DUMBBELL, "下胸", "肱三头肌"),
        ex("史密斯卧推", ExerciseCategory.CHEST, Equipment.MACHINE, "胸大肌", "肱三头肌,三角肌前束"),
        ex("器械推胸", ExerciseCategory.CHEST, Equipment.MACHINE, "胸大肌", "肱三头肌"),
        ex("双杠臂屈伸", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "下胸", "肱三头肌,三角肌前束"),
        ex("俯卧撑", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "胸大肌", "肱三头肌,核心"),
        ex("宽距俯卧撑", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "胸大肌", "三角肌前束"),
        ex("跪姿俯卧撑", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "胸大肌", "肱三头肌"),
        ex("绳索夹胸", ExerciseCategory.CHEST, Equipment.CABLE, "胸中缝", "三角肌前束"),
        ex("蝴蝶机夹胸", ExerciseCategory.CHEST, Equipment.MACHINE, "胸中缝", "三角肌前束"),
        ex("哑铃飞鸟", ExerciseCategory.CHEST, Equipment.DUMBBELL, "胸大肌", "三角肌前束"),
        ex("上斜哑铃飞鸟", ExerciseCategory.CHEST, Equipment.DUMBBELL, "上胸", "三角肌前束"),
        ex("地板卧推", ExerciseCategory.CHEST, Equipment.BARBELL, "胸大肌", "肱三头肌"),
        ex("哑铃上拉", ExerciseCategory.CHEST, Equipment.DUMBBELL, "胸大肌", "背阔肌,肱三头肌"),

        // ---------------- 背 ----------------
        ex("引体向上", ExerciseCategory.BACK, Equipment.BODYWEIGHT, "背阔肌", "肱二头肌,斜方肌"),
        ex("窄距引体向上", ExerciseCategory.BACK, Equipment.BODYWEIGHT, "背阔肌", "肱二头肌"),
        ex("高位下拉", ExerciseCategory.BACK, Equipment.MACHINE, "背阔肌", "肱二头肌,斜方肌"),
        ex("反手高位下拉", ExerciseCategory.BACK, Equipment.MACHINE, "背阔肌", "肱二头肌"),
        ex("杠铃划船", ExerciseCategory.BACK, Equipment.BARBELL, "中背部", "背阔肌,肱二头肌,后束"),
        ex("哑铃单臂划船", ExerciseCategory.BACK, Equipment.DUMBBELL, "背阔肌", "肱二头肌,中背部"),
        ex("坐姿划船", ExerciseCategory.BACK, Equipment.MACHINE, "中背部", "背阔肌,肱二头肌"),
        ex("器械划船", ExerciseCategory.BACK, Equipment.MACHINE, "中背部", "背阔肌"),
        ex("T杠划船", ExerciseCategory.BACK, Equipment.BARBELL, "中背部", "背阔肌,肱二头肌"),
        ex("史密斯划船", ExerciseCategory.BACK, Equipment.MACHINE, "中背部", "背阔肌"),
        ex("直臂下压", ExerciseCategory.BACK, Equipment.CABLE, "背阔肌", "肱三头肌长头"),
        ex("反向划船", ExerciseCategory.BACK, Equipment.BODYWEIGHT, "中背部", "背阔肌,肱二头肌"),
        ex("杠铃硬拉", ExerciseCategory.BACK, Equipment.BARBELL, "竖脊肌", "臀大肌,腘绳肌,斜方肌"),
        ex("架上硬拉", ExerciseCategory.BACK, Equipment.BARBELL, "竖脊肌", "斜方肌,臀大肌"),
        ex("罗马尼亚硬拉", ExerciseCategory.BACK, Equipment.BARBELL, "腘绳肌", "竖脊肌,臀大肌"),
        ex("杠铃耸肩", ExerciseCategory.BACK, Equipment.BARBELL, "斜方肌", "前臂"),
        ex("哑铃耸肩", ExerciseCategory.BACK, Equipment.DUMBBELL, "斜方肌", "前臂"),
        ex("面拉", ExerciseCategory.BACK, Equipment.CABLE, "三角肌后束", "斜方肌,菱形肌"),
        ex("山羊挺身", ExerciseCategory.BACK, Equipment.BODYWEIGHT, "竖脊肌", "臀大肌,腘绳肌"),
        ex("俯身杠铃划船(窄握)", ExerciseCategory.BACK, Equipment.BARBELL, "背阔肌", "中背部,肱二头肌"),
        ex("单臂绳索划船", ExerciseCategory.BACK, Equipment.CABLE, "背阔肌", "中背部"),

        // ---------------- 腿 ----------------
        ex("杠铃深蹲", ExerciseCategory.LEGS, Equipment.BARBELL, "股四头肌", "臀大肌,竖脊肌,腘绳肌"),
        ex("哑铃深蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "股四头肌", "臀大肌"),
        ex("前蹲", ExerciseCategory.LEGS, Equipment.BARBELL, "股四头肌", "臀大肌,核心"),
        ex("高脚杯深蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "股四头肌", "臀大肌,核心"),
        ex("史密斯深蹲", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌", "臀大肌"),
        ex("腿举", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌", "臀大肌,腘绳肌"),
        ex("哈克深蹲", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌", "臀大肌"),
        ex("罗马尼亚硬拉(腿)", ExerciseCategory.LEGS, Equipment.BARBELL, "腘绳肌", "臀大肌,竖脊肌"),
        ex("单腿罗马尼亚硬拉", ExerciseCategory.LEGS, Equipment.DUMBBELL, "腘绳肌", "臀大肌,核心"),
        ex("腿弯举", ExerciseCategory.LEGS, Equipment.MACHINE, "腘绳肌", "小腿"),
        ex("坐姿腿弯举", ExerciseCategory.LEGS, Equipment.MACHINE, "腘绳肌", ""),
        ex("腿屈伸", ExerciseCategory.LEGS, Equipment.MACHINE, "股四头肌", ""),
        ex("保加利亚分腿蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "臀大肌", "股四头肌,核心"),
        ex("箭步蹲", ExerciseCategory.LEGS, Equipment.DUMBBELL, "股四头肌", "臀大肌,核心"),
        ex("哑铃登阶", ExerciseCategory.LEGS, Equipment.DUMBBELL, "臀大肌", "股四头肌"),
        ex("臀桥", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, "臀大肌", "腘绳肌"),
        ex("杠铃臀推", ExerciseCategory.LEGS, Equipment.BARBELL, "臀大肌", "腘绳肌,股四头肌"),
        ex("相扑硬拉", ExerciseCategory.LEGS, Equipment.BARBELL, "臀大肌", "股四头肌,内收肌群"),
        ex("提踵", ExerciseCategory.LEGS, Equipment.MACHINE, "小腿", ""),
        ex("站姿提踵", ExerciseCategory.LEGS, Equipment.MACHINE, "小腿", ""),
        ex("坐姿提踵", ExerciseCategory.LEGS, Equipment.MACHINE, "小腿", ""),
        ex("大腿内收", ExerciseCategory.LEGS, Equipment.MACHINE, "内收肌群", ""),
        ex("大腿外展", ExerciseCategory.LEGS, Equipment.MACHINE, "臀中肌", ""),
        ex("箱式深蹲", ExerciseCategory.LEGS, Equipment.BARBELL, "股四头肌", "臀大肌"),

        // ---------------- 肩 ----------------
        ex("杠铃推举", ExerciseCategory.SHOULDERS, Equipment.BARBELL, "三角肌前束", "肱三头肌,核心"),
        ex("哑铃推举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌前束", "三角肌中束,肱三头肌"),
        ex("阿诺德推举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌前束", "三角肌中束"),
        ex("器械推肩", ExerciseCategory.SHOULDERS, Equipment.MACHINE, "三角肌前束", "肱三头肌"),
        ex("哑铃侧平举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌中束", "斜方肌"),
        ex("绳索侧平举", ExerciseCategory.SHOULDERS, Equipment.CABLE, "三角肌中束", ""),
        ex("器械侧平举", ExerciseCategory.SHOULDERS, Equipment.MACHINE, "三角肌中束", ""),
        ex("哑铃前平举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌前束", ""),
        ex("俯身哑铃飞鸟", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, "三角肌后束", "菱形肌,斜方肌"),
        ex("反向蝴蝶机", ExerciseCategory.SHOULDERS, Equipment.MACHINE, "三角肌后束", "菱形肌"),
        ex("直立划船", ExerciseCategory.SHOULDERS, Equipment.BARBELL, "三角肌中束", "斜方肌"),
        ex("倒立撑", ExerciseCategory.SHOULDERS, Equipment.BODYWEIGHT, "三角肌前束", "肱三头肌,核心"),

        // ---------------- 手臂 ----------------
        ex("杠铃弯举", ExerciseCategory.ARMS, Equipment.BARBELL, "肱二头肌", "前臂"),
        ex("哑铃弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱二头肌", "前臂"),
        ex("锤式弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱肌", "肱二头肌,前臂"),
        ex("牧师凳弯举", ExerciseCategory.ARMS, Equipment.BARBELL, "肱二头肌", ""),
        ex("绳索弯举", ExerciseCategory.ARMS, Equipment.CABLE, "肱二头肌", ""),
        ex("集中弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱二头肌", ""),
        ex("反握弯举", ExerciseCategory.ARMS, Equipment.BARBELL, "前臂", "肱二头肌"),
        ex("腕弯举", ExerciseCategory.ARMS, Equipment.DUMBBELL, "前臂", ""),
        ex("绳索下压", ExerciseCategory.ARMS, Equipment.CABLE, "肱三头肌", ""),
        ex("哑铃颈后臂屈伸", ExerciseCategory.ARMS, Equipment.DUMBBELL, "肱三头肌", ""),
        ex("仰卧臂屈伸", ExerciseCategory.ARMS, Equipment.BARBELL, "肱三头肌", ""),
        ex("窄距卧推", ExerciseCategory.ARMS, Equipment.BARBELL, "肱三头肌", "胸大肌,三角肌前束"),
        ex("双杠下压(三头)", ExerciseCategory.ARMS, Equipment.BODYWEIGHT, "肱三头肌", "胸大肌"),
        ex("绳索过顶臂屈伸", ExerciseCategory.ARMS, Equipment.CABLE, "肱三头肌", ""),
        ex("凳上反屈伸", ExerciseCategory.ARMS, Equipment.BODYWEIGHT, "肱三头肌", "三角肌前束"),

        // ---------------- 核心 ----------------
        ex("卷腹", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "腹直肌", ""),
        ex("绳索卷腹", ExerciseCategory.CORE, Equipment.CABLE, "腹直肌", ""),
        ex("举腿", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "下腹", "髂腰肌"),
        ex("悬垂举腿", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "下腹", "前臂,髂腰肌"),
        ex("平板支撑", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "核心", "腹直肌,腹横肌"),
        ex("侧平板", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "腹斜肌", "核心"),
        ex("死虫式", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "核心稳定", "腹直肌"),
        ex("鸟狗式", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "核心稳定", "竖脊肌"),
        ex("俄罗斯转体", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "腹斜肌", "腹直肌"),
        ex("健腹轮", ExerciseCategory.CORE, Equipment.OTHER, "腹直肌", "背阔肌,核心"),
        ex("农夫行走", ExerciseCategory.CORE, Equipment.DUMBBELL, "核心", "前臂,斜方肌"),
        ex("药球砸地", ExerciseCategory.CORE, Equipment.OTHER, "腹直肌", "核心"),

        // ---------------- 有氧 ----------------
        ex("跑步机跑步", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺", "股四头肌,腘绳肌"),
        ex("椭圆机", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺", "股四头肌"),
        ex("动感单车", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺", "股四头肌"),
        ex("划船机", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺", "背阔肌,股四头肌"),
        ex("爬楼机", ExerciseCategory.CARDIO, Equipment.MACHINE, "心肺", "臀大肌,小腿"),
        ex("跳绳", ExerciseCategory.CARDIO, Equipment.OTHER, "心肺", "小腿,前臂"),
        ex("开合跳", ExerciseCategory.CARDIO, Equipment.BODYWEIGHT, "心肺", "肩,小腿"),
        ex("战绳", ExerciseCategory.CARDIO, Equipment.OTHER, "心肺", "肩,核心"),

        // ---------------- 全身 ----------------
        ex("高翻", ExerciseCategory.FULL_BODY, Equipment.BARBELL, "全身爆发", "斜方肌,臀大肌,腘绳肌"),
        ex("抓举", ExerciseCategory.FULL_BODY, Equipment.BARBELL, "全身爆发", "肩,竖脊肌"),
        ex("挺举", ExerciseCategory.FULL_BODY, Equipment.BARBELL, "全身爆发", "肩,股四头肌"),
        ex("壶铃摆荡", ExerciseCategory.FULL_BODY, Equipment.KETTLEBELL, "后链", "臀大肌,腘绳肌,核心"),
        ex("波比跳", ExerciseCategory.FULL_BODY, Equipment.BODYWEIGHT, "全身", "胸,股四头肌,核心"),
        ex("土耳其起立", ExerciseCategory.FULL_BODY, Equipment.KETTLEBELL, "全身", "核心,肩"),
        ex("推雪橇", ExerciseCategory.FULL_BODY, Equipment.OTHER, "股四头肌", "臀大肌,心肺"),
        ex("负重行走", ExerciseCategory.FULL_BODY, Equipment.KETTLEBELL, "全身", "斜方肌,前臂"),
    )

    private fun ex(
        name: String,
        category: String,
        equipment: String,
        primary: String,
        secondary: String,
    ) = Exercise(
        name = name,
        category = category,
        equipment = equipment,
        primaryMuscle = primary,
        secondaryMuscles = secondary,
        isCustom = false,
    )
}
