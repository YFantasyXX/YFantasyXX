package com.yfantasyxx.ui.dev

import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
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
import com.yfantasyxx.util.Clock
import com.yfantasyxx.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlin.math.abs
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

/** 一次演练的判定结果 */
private data class DrillResult(val passed: Boolean, val message: String)

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

    /** 已存的数据快照，最新的在前 */
    private val _snapshots = MutableStateFlow<List<DevSnapshot>>(emptyList())
    val snapshots: StateFlow<List<DevSnapshot>> = _snapshots.asStateFlow()

    /** 断言面板结果 */
    private val _kernelCases = MutableStateFlow<List<KernelCase>>(emptyList())
    val kernelCases: StateFlow<List<KernelCase>> = _kernelCases.asStateFlow()

    /** 假时钟当前偏移（毫秒），正数=拨到未来 */
    private val _clockOffset = MutableStateFlow(Clock.offsetMillis())
    val clockOffset: StateFlow<Long> = _clockOffset.asStateFlow()

    /** 假远端：(备份份数, 占用字节) */
    private val _fakeRemote = MutableStateFlow(0 to 0L)
    val fakeRemote: StateFlow<Pair<Int, Long>> = _fakeRemote.asStateFlow()

    init {
        refreshFakeRemote()
    }

    private fun say(text: String, error: Boolean = false) {
        messageSeq += 1
        _message.value = DevMessage(messageSeq, text, error)
    }

    /**
     * 只给 lite 版用：拉一次线上 Release 并伪装成更高版本，
     * 好在真正发版之前就能验完下载与安装流程。
     */
    fun simulateUpdateAvailable() {
        viewModelScope.launch {
            val ok = container.updateRepository.simulateNewVersion()
            _message.value = DevMessage(
                id = ++messageSeq,
                text = if (ok) {
                    "已伪装成新版本，去「我的 → 应用更新」看效果"
                } else {
                    "拉取线上 Release 失败，先确认网络"
                },
                error = !ok,
            )
        }
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
        if (!ensureOverlayPermission()) return
        runCatching {
            RestTimerService.showFinished(
                container.appContext,
                "测试动作 · 第 3 组",
                forceOverlay = true,
            )
            say("已强制显示结束悬浮窗；常驻开着的话 12 秒后收成小球")
        }.onFailure { say("显示失败：${it.message}", error = true) }
    }

    /**
     * 只画倒计时气泡，不启动计时服务。
     *
     * 走的是和真实休息一样的气泡（环随剩余时间收缩），不占进程、也不会到点弹卡片，
     * 用来调位置、看配色、验权限最省事。
     */
    fun previewCountdownBubble(seconds: Int = 90) {
        if (!ensureOverlayPermission()) return
        runCatching {
            val overlay = RestOverlayController.shared(container.appContext)
            val end = System.currentTimeMillis() + seconds * 1000L
            if (!overlay.showCountdown(end, seconds, "测试 · 组间休息")) {
                say("气泡没显示出来：权限可能刚被撤销，去系统设置里看一下", error = true)
                return
            }
            say("已显示倒计时气泡（$seconds 秒 · 可以拖动）")
        }.onFailure { say("显示失败：${it.message}", error = true) }
    }

    /**
     * 跳过结束卡片的 12 秒等待，直接看它收成的常驻小球。
     *
     * 小球没有关闭入口，预览完点「收掉常驻小球」。
     */
    fun previewPersistentBall() {
        if (!ensureOverlayPermission()) return
        runCatching {
            val overlay = RestOverlayController.shared(container.appContext)
            overlay.persistent = true
            if (!overlay.showFinished("测试动作 · 第 3 组", lingerMs = 0L)) {
                say("小球没显示出来：权限可能刚被撤销", error = true)
                return
            }
            say("已跳过 12 秒，直接收成常驻小球（点它只会回 App）")
        }.onFailure { say("显示失败：${it.message}", error = true) }
    }

    /** 收掉预览出来的悬浮窗。正式路径是去设置里关掉「悬浮窗常驻」 */
    fun dismissPersistentBall() {
        runCatching {
            val overlay = RestOverlayController.shared(container.appContext)
            val wasPersistent = overlay.persistent || overlay.mode == RestOverlayController.MODE_PERSIST
            overlay.persistent = false
            overlay.hide()
            RestNotifier.cancelDone(container.appContext)
            // 只有常驻小球需要顺手把服务退掉（它本来就靠前台服务撑着）。
            // 倒计时气泡背后可能是一次真在跑的计时，收掉窗口就够了，不能把计时一起停掉
            if (wasPersistent) RestTimerService.dismissOverlayNow(container.appContext)
            say(
                if (wasPersistent) "常驻悬浮窗、前台服务和「休息结束」通知都已收掉"
                else "悬浮窗已收掉"
            )
        }.onFailure { say("收掉失败：${it.message}", error = true) }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (RestOverlayController.canShow(container.appContext)) return true
        say("没有悬浮窗权限：点下面的「悬浮窗权限设置」开启", error = true)
        return false
    }

    fun testVibrate() {
        runCatching {
            RestNotifier.vibrate(container.appContext, force = true)
            say("已触发 4 段震动")
        }.onFailure { say("震动失败：${it.message}", error = true) }
    }

    /**
     * 模拟「休息结束那一刻，系统里别的应用也在震」。
     *
     * 震动有 350ms 起手延迟，这里在 450ms 处插一条 900ms 的独立震动——
     * 和别的应用顶掉我们这一下是同一回事（振动器同一时刻只放一条）。
     * 被顶掉的只是第 1、2 段，后面两段应该照样震。
     */
    fun testVibrateInterrupted() {
        runCatching {
            RestNotifier.vibrate(container.appContext, force = true)
            viewModelScope.launch {
                delay(450)
                runCatching {
                    val vibrator = ContextCompat.getSystemService(
                        container.appContext,
                        VibratorManager::class.java,
                    )?.defaultVibrator
                    vibrator?.vibrate(VibrationEffect.createOneShot(900, 255))
                }
                say("起震 100ms 后插了一条 900ms 震动：后两段应该照样震")
            }
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
            // 造数据前先存档：这一步失败就不往下写，免得真实记录回不来
            val guard = DevSnapshots.snapshot(container.appContext, container.db(), "before-seed")
            seedHistoryInternal(days, exercisesPerDay, setsPerExercise, includeToday) +
                " · 存了快照 ${DevSnapshots.sizeLabel(guard.bytes)}"
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
            // 下面第一句就是 DELETE，先把现有的体重数据存下来
            val guard = DevSnapshots.snapshot(container.appContext, db, "before-seed-body")
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
            "$n 天体重记录（原有体重数据已清空）· 存了快照 ${DevSnapshots.sizeLabel(guard.bytes)}"
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
            // 这是最不可逆的一个入口，快照必须存成功才继续
            val guard = DevSnapshots.snapshot(container.appContext, db, "before-clear")
            db.withTransaction {
                val sqlite = db.openHelper.writableDatabase
                // 子表先删，避免外键约束报错（虽然 ON DELETE CASCADE 也能兜住）
                sqlite.execSQL("DELETE FROM set_logs")
                sqlite.execSQL("DELETE FROM session_exercises")
                sqlite.execSQL("DELETE FROM sessions")
            }
            repo.notifyDataChanged()
            "训练记录、组数、PR 全部清空（动作 / 模板 / 计划保留）· " +
                "清空前存了 ${DevSnapshots.sizeLabel(guard.bytes)} 的快照"
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
        if (_busy.value) {
            say("上一个操作还在跑，等它结束再来一次", error = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { buildFakeWorkout() } }
            _busy.value = false
            result.onSuccess { detail ->
                say("假训练已就绪：$detail")
                onReady()
            }.onFailure { say("生成假训练失败：${it.message ?: it::class.simpleName}", true) }
            refreshInfo()
            refreshSnapshots()
        }
    }

    private suspend fun buildFakeWorkout(): String {
        val pool = repo.observeExercises().first().sortedBy { it.id }
        if (pool.size < FAKE_EXERCISES) {
            throw IllegalStateException("动作库只有 ${pool.size} 个动作，先点「补全动作库」")
        }
        // 下面会 discardSession 掉正在练的那次，先存档
        val guard = DevSnapshots.snapshot(container.appContext, container.db(), "before-fake-workout")
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
            "上次训练第 $FAKE_SETS 组是 PR（更重、只有 2 次）；" +
            "动手前存了快照 ${DevSnapshots.sizeLabel(guard.bytes)}"
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

    // ---------- 数据快照 ----------

    fun refreshSnapshots() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { DevSnapshots.list(container.appContext) }.getOrDefault(emptyList())
            }
            _snapshots.value = list
        }
    }

    fun snapshotNow() {
        run("已存一份快照") {
            val s = DevSnapshots.snapshot(container.appContext, container.db(), "manual")
            "${s.sessions} 次训练 · ${DevSnapshots.sizeLabel(s.bytes)}"
        }
    }

    fun restoreSnapshot(file: File) {
        run("已还原") {
            val detail = DevSnapshots.restore(container.appContext, container, file)
            repo.notifyDataChanged()
            detail
        }
    }

    fun deleteSnapshot(file: File) {
        run("快照已删除") {
            DevSnapshots.delete(container.appContext, file)
            DevSnapshots.stampOf(file)
        }
    }

    fun clearSnapshots() {
        run("快照已清空") {
            val n = _snapshots.value.size
            DevSnapshots.clear(container.appContext)
            "$n 份，共释放约 ${DevSnapshots.sizeLabel(_snapshots.value.sumOf { it.bytes })}"
        }
    }

    // ---------- 计算内核（P1） ----------

    /**
     * 跑一遍全部断言。
     *
     * 这些函数算错了不会崩，只会在训练页印一个偏了十几公斤的数字——
     * 靠肉眼是看不出 117.6 和 122.0 哪个对的。
     */
    fun runKernelAssertions() {
        val cases = DevKernel.runAll()
        _kernelCases.value = cases
        val failed = cases.count { !it.passed }
        say(
            if (failed == 0) {
                "断言全绿：${cases.size} 项全部通过"
            } else {
                "断言 $failed / ${cases.size} 项未通过，展开下面的分组看具体哪条"
            },
            error = failed > 0,
        )
    }

    /** 用库里真实训练数据跑一次校准，看每一档的 γ 和样本量 */
    fun refreshCalibration() {
        run("校准结果") {
            val rows = container.db().sessionDao().repMaxPoints()
            DevKernel.calibrationSummary(rows)
        }
    }

    // ---------- 时间旅行（P1） ----------

    /** [days] 传 0 = 拨回现在；正数前进，负数后退 */
    fun shiftClock(days: Int) {
        val offset = if (days == 0) {
            Clock.reset(container.appContext)
        } else {
            Clock.shiftDays(container.appContext, days)
        }
        _clockOffset.value = offset
        // 统计页的窗口是在 Flow 里算的，得推一下才会按新时间重算
        repo.notifyDataChanged()
        say(
            if (offset == 0L) {
                "业务时间已拨回现在"
            } else {
                "业务时间${if (offset > 0) "前进" else "后退"} " +
                    "${abs(offset) / 86_400_000L} 天（只影响读，倒计时和已有记录不走这个钟）"
            }
        )
    }

    // ---------- 备份演练（P1） ----------

    /**
     * 用假远端跑一次真实 [com.yfantasyxx.sync.BackupManager]。
     *
     * **只演练「该被拦下」的分支和「备份」分支**，没有「恢复成功」的场景：
     * 那条路径会真的覆盖本地库，用它做演练等于拿真实训练记录冒险。
     */
    fun runDrill(drill: BackupDrill) {
        if (_busy.value) {
            say("上一个操作还在跑，等它结束再来一次", error = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val startedAt = System.nanoTime()
            val outcome = withContext(Dispatchers.IO) { runCatching { executeDrill(drill) } }
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            _busy.value = false
            refreshFakeRemote()
            outcome.onSuccess { r ->
                say(
                    "${if (r.passed) "通过" else "未通过"} · ${drill.label} · ${ms}ms · ${r.message}",
                    error = !r.passed,
                )
            }.onFailure {
                say(
                    "未通过 · ${drill.label} · 演练本身出错：${it.message ?: it::class.simpleName}",
                    error = true,
                )
            }
        }
    }

    private suspend fun executeDrill(drill: BackupDrill): DrillResult {
        val store = DevBackupLab.store(container.appContext, drill.fault)
        val result = when (drill.op) {
            DrillOp.BACKUP ->
                container.backupManager.backup(store).map { "产出 $it" }

            DrillOp.RESTORE -> {
                val name = if (drill == BackupDrill.MISSING) {
                    MISSING_REMOTE_NAME
                } else {
                    ensureFakeRemoteFile()
                }
                container.backupManager.restore(store, name).map { "恢复流程返回成功" }
            }
        }
        val text = result.fold(
            onSuccess = { it },
            onFailure = { it.message ?: it::class.simpleName.orEmpty() },
        )
        val branchOk = result.isFailure == drill.expectFailure
        val keywordOk = !drill.expectFailure || text.contains(drill.expectKeyword)
        return DrillResult(
            passed = branchOk && keywordOk,
            message = buildString {
                append(text)
                if (branchOk && !keywordOk) {
                    append("（失败原因里没有「")
                    append(drill.expectKeyword)
                    append("」，可能走了别的分支，这条不算验到）")
                }
            },
        )
    }

    /** 假远端里没有文件时放一个占位：只为让「文件存在」这一关过掉 */
    private fun ensureFakeRemoteFile(): String {
        DevBackupLab.firstFileName(container.appContext)?.let { return it }
        val dir = DevBackupLab.backupDir(container.appContext)
        dir.mkdirs()
        val placeholder = File(dir, PLACEHOLDER_REMOTE_NAME)
        if (!placeholder.exists()) placeholder.writeBytes(ByteArray(4096))
        return placeholder.name
    }

    fun clearFakeRemote() {
        val n = DevBackupLab.clear(container.appContext)
        refreshFakeRemote()
        say("假远端已清空（$n 份）")
    }

    private fun refreshFakeRemote() {
        _fakeRemote.value = DevBackupLab.fileCount(container.appContext) to
            DevBackupLab.totalBytes(container.appContext)
    }

    // ---------- 诊断包 ----------

    /**
     * 打一个 zip 并拉系统分享面板。
     *
     * 内容：库快照 + 崩溃日志 + 最近的 logs/ + 脱敏设置 + 机型/schema/行数。
     * 以前排查一个「恢复备份后打不开」的问题要来回问四次，现在一次发完。
     */
    fun shareDiagnostics() {
        if (_busy.value) {
            say("还有操作在进行，稍等一下再打包", error = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                DevDiagnostics.ensureUsable(
                    DevDiagnostics.build(container.appContext, container, _info.value)
                )
            }
            _busy.value = false
            result.onSuccess { file ->
                runCatching { DevDiagnostics.share(container.appContext, file) }
                    .onSuccess { say("诊断包 ${mb(file.length())}，已拉起分享面板") }
                    .onFailure {
                        say("分享面板打不开：${it.message}；文件在 ${file.absolutePath}", error = true)
                    }
            }.onFailure { say("打包失败：${it.message ?: it::class.simpleName}", true) }
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
        // 以前这里直接 return，表现是「点了没反应」，看不出是撞上了还是没生效
        if (_busy.value) {
            say("上一个操作还在跑，等它结束再来一次", error = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            _busy.value = false
            result.onSuccess { detail -> say("$successPrefix：$detail") }
                .onFailure { say("失败：${it.message ?: it::class.simpleName}", true) }
            refreshInfo()
            refreshSnapshots()
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

        /** 「远端文件已被删」场景用的文件名：一定不存在 */
        private const val MISSING_REMOTE_NAME = "yfantasyxx-backup-19700101-000000-000-v15.db"

        /** 占位文件：只为让「文件存在」这一关过掉，内容会被注入的故障覆盖 */
        private const val PLACEHOLDER_REMOTE_NAME = "yfantasyxx-backup-19900101-000000-000-v15.db"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DevToolsViewModel(container, container.workoutRepository) }
        }
    }
}
