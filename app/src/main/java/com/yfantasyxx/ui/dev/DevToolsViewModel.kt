package com.yfantasyxx.ui.dev

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.withTransaction
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.PlanDaySlot
import com.yfantasyxx.data.RepRange
import com.yfantasyxx.data.TrainingCycle
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.AppDatabase
import com.yfantasyxx.service.RestNotifier
import com.yfantasyxx.service.RestOverlayController
import com.yfantasyxx.service.RestTimerService
import com.yfantasyxx.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** 一条提示。带 id 是为了「同样的文字再提示一次」也能重新计时消失。 */
data class DevMessage(val id: Long, val text: String, val error: Boolean = false)

data class TableCount(val table: String, val rows: Int)

data class DevInfo(
    val tables: List<TableCount> = emptyList(),
    val dbBytes: Long = 0,
    val schemaVersion: Int = 0,
    val planName: String? = null,
    val planDetail: String? = null,
    val crashLog: String? = null,
)

/**
 * 测试工具（只在 lite 版出现）。
 *
 * 这里的东西都是「绕过正常流程」的：直接写 SQL 伪造历史、直接改计划游标、
 * 直接抛异常。所以全部集中在 `ui/dev` 下，正式版靠 `BuildConfig.BUILD_TYPE` 把入口藏掉，
 * R8 会把常量判断折成 false 并删掉整段。
 */
class DevToolsViewModel(
    private val container: AppContainer,
    private val repo: WorkoutRepository,
) : ViewModel() {

    private var messageSeq = 0L

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<DevMessage?>(null)
    val message: StateFlow<DevMessage?> = _message.asStateFlow()

    private val _info = MutableStateFlow(DevInfo())
    val info: StateFlow<DevInfo> = _info.asStateFlow()

    private fun say(text: String, error: Boolean = false) {
        messageSeq += 1
        _message.value = DevMessage(messageSeq, text, error)
    }

    fun clearMessage() {
        _message.value = null
    }

    // ---------- 计时 / 通知 ----------

    /** 发起一次倒计时，走的完全是训练页那条前台服务路径 */
    fun startTimer(seconds: Int, label: String?, useAlarm: Boolean) {
        val s = seconds.coerceIn(1, 3600)
        runCatching {
            RestTimerService.start(
                context = container.appContext,
                seconds = s,
                label = label?.takeIf { it.isNotBlank() },
                useAlarm = useAlarm,
            )
            say("已发起 ${s}s 倒计时" + if (useAlarm) "（带系统闹钟兜底）" else "（只靠前台服务）")
        }.onFailure { say("发起失败：${it.message}", error = true) }
    }

    fun stopTimer() {
        runCatching {
            RestTimerService.skip(container.appContext)
            RestNotifier.cancelDone(container.appContext)
            say("已停止计时并清掉通知")
        }.onFailure { say("停止失败：${it.message}", error = true) }
    }

    /** 不等倒计时，直接走「休息结束」那段：通知渠道 + 声音 + 震动一起验 */
    fun fireRestFinished() {
        runCatching {
            // 走服务而不是直接发通知：这样和真实结束时的表现完全一致
            // （前台通知、悬浮窗、提示音都在服务里）
            RestTimerService.showFinished(container.appContext, "测试动作 · 第 3 组")
            say("已进入「休息结束」：通知 + 震动 + 提示音")
        }.onFailure { say("推送失败：${it.message}", error = true) }
    }

    /** 忽略「App 在前台就不显示」的判断，强制把结束悬浮窗画出来看长相 */
    fun forceShowOverlay() {
        if (!RestOverlayController.canShow(container.appContext)) {
            say("没有悬浮窗权限：去「我的 → 休息提醒」开启", error = true)
            return
        }
        runCatching {
            RestTimerService.showFinished(
                container.appContext,
                "测试动作 · 第 3 组",
                forceOverlay = true,
            )
            say("已强制显示结束悬浮窗，12 秒后自动消失")
        }.onFailure { say("显示失败：${it.message}", error = true) }
    }

    fun testVibrate() {
        runCatching {
            RestNotifier.vibrate(container.appContext, force = true)
            say("已触发 4 段震动")
        }.onFailure { say("震动失败：${it.message}", error = true) }
    }

    /**
     * 直接杀掉进程。
     * 用来验证「退出 App 后倒计时/提醒还在不在」——先发起倒计时再点这个，
     * 到点应该仍然收到「休息结束」通知。
     */
    fun killProcess() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------- 假数据 ----------

    /**
     * 从「空 App」直接铺到能用的状态：3 个模板 + 一个 5 天计划（推 / 拉 / 腿 / 休 / 休）。
     *
     * 重复点不会造第二份：模板按名字复用，计划按名字复用。
     * 这样测训练页、首页计划卡都不用先手动建东西。
     */
    fun plantTestSetup() {
        run("已铺好测试环境") {
            val pool = repo.observeExercises().first().sortedBy { it.id }
            if (pool.isEmpty()) throw IllegalStateException("动作库是空的，先点「补全动作库」")

            val existingTemplates = repo.observeTemplates().first().associateBy { it.name }
            val names = listOf("测试 · 推", "测试 · 拉", "测试 · 腿")
            val templateIds = names.mapIndexed { index, name ->
                existingTemplates[name]?.id ?: run {
                    val chosen = pool.drop(index * 3).take(3).ifEmpty { pool.take(3) }
                    repo.saveTemplate(
                        templateId = null,
                        name = name,
                        note = "测试工具生成",
                        items = chosen.map { exercise ->
                            WorkoutRepository.TemplateExerciseDraft(
                                exerciseId = exercise.id,
                                targetSets = 3,
                                range = RepRange(8, 12),
                            )
                        },
                    )
                }
            }

            val hasPlan = repo.observePlans().first().any { it.name == "测试计划" }
            if (!hasPlan) {
                repo.createPlan(
                    name = "测试计划",
                    // 最后两天是休息日，正好验证「休息日自动流过」和「再休一天」
                    days = templateIds + listOf(null, null),
                    activate = true,
                )
            }
            repo.notifyDataChanged()
            "3 个模板 + 5 天计划（推 / 拉 / 腿 / 休 / 休）"
        }
    }

    fun seedHistory(
        days: Int,
        exercisesPerDay: Int,
        setsPerExercise: Int,
        includeToday: Boolean,
    ) {
        run("已生成测试训练") {
            seedHistoryInternal(days, exercisesPerDay, setsPerExercise, includeToday)
        }
    }

    private suspend fun seedHistoryInternal(
        days: Int,
        exercisesPerDay: Int,
        setsPerExercise: Int,
        includeToday: Boolean,
    ): String {
        val db = container.db()
        val pool = repo.observeExercises().first().sortedBy { it.id }
        if (pool.isEmpty()) throw IllegalStateException("动作库是空的，先点「补全动作库」")

        val dayCount = days.coerceIn(1, 365)
        val perDay = exercisesPerDay.coerceIn(1, 8)
        val setCount = setsPerExercise.coerceIn(1, 10)
        val firstOffset = if (includeToday) 0 else 1
        val now = System.currentTimeMillis()
        val names = listOf("胸肩三头", "背部二头", "腿部", "肩臂", "全身")

        var sessions = 0
        var sets = 0
        db.withTransaction {
            val sqlite = db.openHelper.writableDatabase
            for (offset in firstOffset until firstOffset + dayCount) {
                // 今天这条用「一个半小时前刚练完」，别写成今晚 19:00——
                // 那样在上午操作时 endTime 会落在未来，看着很怪
                val start = if (offset == 0) {
                    now - 90 * 60_000L
                } else {
                    atClock(now, -offset, hour = 19, minute = 0)
                }
                val end = start + (55L + (offset * 7) % 25) * 60_000L

                sqlite.execSQL(
                    "INSERT INTO sessions (name, templateId, startTime, activeStartedAt, endTime, note) " +
                        "VALUES (?, NULL, ?, ?, ?, ?)",
                    arrayOf<Any?>("${names[offset % names.size]}（测试）", start, start, end, ""),
                )
                val sessionId = lastInsertId(sqlite)

                val chosen = pool.shuffled(Random(offset * 31L + 7)).take(perDay)
                chosen.forEachIndexed { index, exercise ->
                    sqlite.execSQL(
                        "INSERT INTO session_exercises (sessionId, exerciseId, sortOrder, restSeconds, " +
                            "plannedSets, plannedReps, plannedRepsMin, plannedRepsMax, supersetGroup) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                        arrayOf<Any?>(sessionId, exercise.id, index, 90, setCount, "8-12", 8, 12),
                    )
                    val seId = lastInsertId(sqlite)

                    // 越早的训练越轻：每过一周 +2%，最多加到 12 周前
                    val base = 2.5f * (8 + (exercise.id % 20))
                    val step = (offset / 7).coerceAtMost(12)
                    val weight = ((base * (1f + 0.02f * step)) / 2.5f).roundToInt() * 2.5f

                    for (n in 1..setCount) {
                        val reps = 5 + ((exercise.id + offset + n) % 8)
                        // 最近一天每个动作的最后一组标 PR，PR 榜才有东西看
                        val isPr = offset == 0 && n == setCount
                        sqlite.execSQL(
                            "INSERT INTO set_logs (sessionExerciseId, setNumber, weightKg, reps, rpe, " +
                                "isWarmup, isPr, completed, loggedAt) VALUES (?, ?, ?, ?, NULL, 0, ?, 1, ?)",
                            arrayOf<Any?>(
                                seId,
                                n,
                                weight,
                                reps,
                                if (isPr) 1 else 0,
                                start + n * 4 * 60_000L,
                            ),
                        )
                        sets++
                    }
                }
                sessions++
            }
        }
        repo.notifyDataChanged()
        return "$sessions 次训练 · $sets 组记录"
    }

    fun seedBodyMetrics(days: Int) {
        run("已生成体重曲线") {
            val db = container.db()
            val n = days.coerceIn(7, 365)
            val today = TrainingCycle.startOfDay(System.currentTimeMillis())
            db.withTransaction {
                val sqlite = db.openHelper.writableDatabase
                sqlite.execSQL("DELETE FROM body_metrics")
                for (back in n - 1 downTo 0) {
                    val date = TrainingCycle.startOfDay(today - back * 86_400_000L)
                    // 从过去到现在慢慢下降，再叠一点日常波动
                    val wave = ((back * 37) % 11 - 5) * 0.06f
                    val weight = 76f + back * 0.06f + wave
                    sqlite.execSQL(
                        "INSERT INTO body_metrics (date, weightKg, bodyFatPercent, note) VALUES (?, ?, NULL, ?)",
                        arrayOf<Any?>(date, weight, "测试数据"),
                    )
                }
            }
            repo.notifyDataChanged()
            "$n 天体重记录（原有体重数据已清空）"
        }
    }

    fun seedExercises() {
        run("已补全动作库") {
            repo.seedMissing()
            "当前共 ${repo.observeExercises().first().size} 个动作"
        }
    }

    fun clearWorkouts() {
        run("已清空训练记录") {
            val db = container.db()
            db.withTransaction {
                val sqlite = db.openHelper.writableDatabase
                // 子表先删，避免外键约束报错（虽然 ON DELETE CASCADE 也能兜住）
                sqlite.execSQL("DELETE FROM set_logs")
                sqlite.execSQL("DELETE FROM session_exercises")
                sqlite.execSQL("DELETE FROM sessions")
            }
            repo.notifyDataChanged()
            "训练记录、组数、PR 全部清空（动作 / 模板 / 计划保留）"
        }
    }

    // ---------- 假的训练中 ----------

    /**
     * 造一页「假的训练中」并直接进训练页。
     *
     * 不做静态预览页：预填重量次数、顶部组数统计、休息计时、复制上一组
     * 全都跑在真实 ViewModel + 数据库上，只有进真页面才验得出来。
     *
     * 造的数据：
     * - 一次「上次训练」：7 个动作，每组重量次数都不同，最后一组是明显更重的 PR；
     * - 一次进行中的训练：同样 7 个动作 × 3 组，第一个动作前 2 组已打勾、计时已走 12 分钟。
     *
     * 进去先看两件事：每组预填是不是上次**同一个组号**的重量次数（第 3 组不该是那个 PR），
     * 顶部是不是「7 个动作 · 已完成 2/21 组」。
     */
    fun startFakeWorkout(onReady: () -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { buildFakeWorkout() } }
            _busy.value = false
            result.onSuccess { detail ->
                say("假训练已就绪：$detail")
                onReady()
            }.onFailure { say("生成假训练失败：${it.message ?: it::class.simpleName}", true) }
            refreshInfo()
        }
    }

    private suspend fun buildFakeWorkout(): String {
        val pool = repo.observeExercises().first().sortedBy { it.id }
        if (pool.size < FAKE_EXERCISES) {
            throw IllegalStateException("动作库只有 ${pool.size} 个动作，先点「补全动作库」")
        }
        val chosen = pool.take(FAKE_EXERCISES)
        val db = container.db()

        // 先丢掉当前草稿，否则进训练页看到的还是你原来那次
        repo.getActiveSessionId()?.let { repo.discardSession(it) }

        // 1) 上次训练：两小时前刚结束，正好是这 7 个动作的最近一次
        val end = System.currentTimeMillis() - 2 * 60 * 60_000L
        val start = end - 55 * 60_000L
        db.withTransaction {
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO sessions (name, templateId, startTime, activeStartedAt, endTime, note) " +
                    "VALUES (?, NULL, ?, ?, ?, ?)",
                arrayOf<Any?>("假的：上次训练", start, start, end, "测试工具生成"),
            )
            val historyId = lastInsertId(sqlite)
            chosen.forEachIndexed { index, exercise ->
                sqlite.execSQL(
                    "INSERT INTO session_exercises (sessionId, exerciseId, sortOrder, restSeconds, " +
                        "plannedSets, plannedReps, plannedRepsMin, plannedRepsMax, supersetGroup) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    arrayOf<Any?>(historyId, exercise.id, index, 90, FAKE_SETS, "8-12", 8, 12),
                )
                val seId = lastInsertId(sqlite)
                // 每个动作三组：40×10 / 42.5×9 / 60×2(PR)。PR 明显更重次数更少，
                // 一眼就能看出预填有没有把它抄进来。
                val base = 40f + 10f * index
                for (n in 1..FAKE_SETS) {
                    val weight = when (n) {
                        1 -> base
                        2 -> base + 2.5f
                        else -> base + 20f
                    }
                    val reps = when (n) {
                        1 -> 10
                        2 -> 9
                        else -> 2
                    }
                    sqlite.execSQL(
                        "INSERT INTO set_logs (sessionExerciseId, setNumber, weightKg, reps, rpe, " +
                            "isWarmup, isPr, completed, loggedAt) VALUES (?, ?, ?, ?, NULL, 0, ?, 1, ?)",
                        arrayOf<Any?>(
                            seId,
                            n,
                            weight,
                            reps,
                            if (n == FAKE_SETS) 1 else 0,
                            start + n * 5 * 60_000L,
                        ),
                    )
                }
            }
        }

        // 2) 本次训练：走正式的开始训练 + 加动作，预填逻辑原样跑一遍
        val sessionId = repo.startSession(name = "假的训练中")
        chosen.forEach { exercise ->
            repo.addExerciseToSession(sessionId, exercise.id, targetSets = FAKE_SETS)
        }
        // 第一个动作前两组先打勾，顺便把计时器点亮，才能看到「已完成 x/y 组」
        repo.getSessionDetails(sessionId).orEmpty().firstOrNull()?.let { item ->
            item.sets.sortedBy { it.setNumber }.take(2).forEach { set ->
                repo.setSetCompleted(set.id, true)
            }
        }
        repo.markSessionStarted(sessionId, System.currentTimeMillis() - 12 * 60_000L)
        repo.notifyDataChanged()
        return "$FAKE_EXERCISES 个动作 × $FAKE_SETS 组，已完成 2 组；" +
            "上次训练第 $FAKE_SETS 组是 PR（更重、只有 2 次）"
    }

    // ---------- 计划时间旅行 ----------

    /** [days] 为正 = 时间前进（休息日会被直接跳过），为负 = 时间倒退 */
    fun shiftPlanTime(days: Int) {
        run(if (days > 0) "计划时间前进 $days 天" else "计划时间后退 ${-days} 天") {
            val db = container.db()
            var planName: String? = null
            db.withTransaction {
                val planDao = db.planDao()
                val plan = planDao.activePlan() ?: throw IllegalStateException("没有启用的训练计划")
                planName = plan.name
                val base = if (plan.dayStartedAt > 0L) {
                    plan.dayStartedAt
                } else {
                    TrainingCycle.startOfDay(System.currentTimeMillis())
                }
                planDao.setProgress(plan.id, plan.nextIndex, base - days * 86_400_000L)
            }
            repo.notifyDataChanged()
            "「$planName」的日期基准已移动 $days 天"
        }
    }

    fun jumpToNextTrainingDay() {
        run("已跳到下一个训练日") {
            val db = container.db()
            db.withTransaction {
                val planDao = db.planDao()
                val plan = planDao.activePlan() ?: throw IllegalStateException("没有启用的训练计划")
                val slots = planDao.daysOf(plan.id)
                    .sortedBy { it.position }
                    .map { PlanDaySlot(it.templateId, "") }
                if (slots.isEmpty()) throw IllegalStateException("这个计划一天都没排")
                val cur = TrainingCycle.cursor(plan.nextIndex, plan.dayStartedAt, slots)
                var next = (cur.index + 1) % slots.size
                var guard = 0
                while (slots[next].isRest && guard++ < slots.size) {
                    next = (next + 1) % slots.size
                }
                planDao.setProgress(
                    plan.id,
                    next,
                    TrainingCycle.startOfNextDay(System.currentTimeMillis()),
                )
            }
            repo.notifyDataChanged()
            "游标已跳到下一个训练日"
        }
    }

    fun resetPlanProgress() {
        run("计划已回到第 1 天") {
            val plan = container.db().planDao().activePlan()
                ?: throw IllegalStateException("没有启用的训练计划")
            repo.resetPlanProgress(plan.id)
            repo.notifyDataChanged()
            "「${plan.name}」从第 1 天重新开始"
        }
    }

    // ---------- 数据库 ----------

    fun refreshInfo() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readInfo() } }
            result.onSuccess { _info.value = it }
                .onFailure { say("读取数据库信息失败：${it.message}", true) }
        }
    }

    private suspend fun readInfo(): DevInfo {
        val db = container.db()
        val sqlite = db.openHelper.readableDatabase
        val tables = listOf(
            "exercises", "templates", "template_exercises", "sessions",
            "session_exercises", "set_logs", "body_metrics",
            "training_plans", "plan_days",
        )
        val counts = tables.map { name ->
            val rows = runCatching {
                sqlite.query("SELECT COUNT(*) FROM $name").use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }.getOrDefault(0)
            TableCount(name, rows)
        }

        val file = container.appContext.getDatabasePath(AppDatabase.NAME)
        val bytes = listOf(
            file,
            File(file.path + "-wal"),
            File(file.path + "-shm"),
        ).filter { it.exists() }.sumOf { it.length() }

        val plan = db.planDao().activePlan()
        val days = plan?.let { db.planDao().daysOf(it.id) }.orEmpty()
        val slots = days.sortedBy { it.position }.map { PlanDaySlot(it.templateId, "") }
        val cursor = plan?.let {
            TrainingCycle.cursor(it.nextIndex, it.dayStartedAt, slots)
        }

        return DevInfo(
            tables = counts,
            dbBytes = bytes,
            schemaVersion = runCatching { sqlite.version }.getOrDefault(0),
            planName = plan?.name,
            planDetail = if (plan != null && cursor != null) {
                "第 ${cursor.index + 1}/${days.size} 天 · " +
                    "起点 ${formatTime(cursor.dayStartedAt)} · " +
                    "今天该练：${if (slots.getOrNull(cursor.index)?.isRest != false) "休息" else "训练"}"
            } else {
                null
            },
            crashLog = CrashLogger.readLast(container.appContext),
        )
    }

    fun checkpointWal() {
        run("WAL 已合入主库") {
            val db = container.db()
            val before = dbFileSizes()
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL_TRUNCATE)")
                .use { it.moveToFirst() }
            val after = dbFileSizes()
            "主库 + WAL 合计 ${mb(before)} → ${mb(after)}"
        }
    }

    /** 导出到 App 外部私有目录，文件管理器能直接翻到 */
    fun exportDatabase() {
        run("已导出数据库") {
            val db = container.db()
            val dir = container.appContext.getExternalFilesDir("dev")
                ?: File(container.appContext.filesDir, "dev")
            if (!dir.exists()) dir.mkdirs()
            val src = container.appContext.getDatabasePath(AppDatabase.NAME)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dst = File(dir, "dev-$stamp.db")

            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL_TRUNCATE)")
                .use { it.moveToFirst() }
            src.copyTo(dst, overwrite = true)
            "已导出到 ${dst.absolutePath}（${mb(dst.length())}）"
        }
    }

    /** 模拟「恢复备份后重建数据库」这一步，不需要真的连网盘 */
    fun rebuildDatabase() {
        run("数据库实例已重建") {
            container.rebuildDatabase()
            repo.notifyDataChanged()
            "已关闭旧实例并重新打开，各 Repository 仍持有旧引用，建议接着点「重建界面」"
        }
    }

    // ---------- 崩溃 / 日志 ----------

    /**
     * 在子线程抛未捕获异常。
     * 用一个独立线程是为了让堆栈里看得见线程名，同时不污染主线程的调用栈。
     */
    fun crashNow() {
        Thread({
            throw IllegalStateException("测试崩溃：这是测试工具主动抛出的异常")
        }, "dev-crash-test").start()
        say("已抛出测试异常，如果界面没有立刻消失，说明没崩")
    }

    fun clearCrashLogs() {
        run("崩溃日志已清空") {
            CrashLogger.clear(container.appContext)
            CrashLogger.clearHistory(container.appContext)
            refreshInfo()
            "last_crash.txt 与 logs/ 下的存档都删了"
        }
    }

    // ---------- 内部工具 ----------

    private fun run(successPrefix: String, block: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            _busy.value = false
            result.onSuccess { detail -> say("$successPrefix：$detail") }
                .onFailure { say("失败：${it.message ?: it::class.simpleName}", true) }
            refreshInfo()
        }
    }

    private fun lastInsertId(sqlite: androidx.sqlite.db.SupportSQLiteDatabase): Long =
        sqlite.query("SELECT last_insert_rowid()").use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    private fun atClock(base: Long, dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dbFileSizes(): Long {
        val file = container.appContext.getDatabasePath(AppDatabase.NAME)
        return listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.2f MB", bytes / 1024f / 1024f)

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))

    companion object {
        /** 假训练的动作数：和首页常见的一天训练量对齐，方便看顶部统计 */
        private const val FAKE_EXERCISES = 7
        private const val FAKE_SETS = 3

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DevToolsViewModel(container, container.workoutRepository) }
        }
    }
}
