package com.yfantasyxx.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.Load
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.PreviousSetRow
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.data.db.SetSegment
import com.yfantasyxx.data.db.WorkoutSession
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.formatSessionDuration
import com.yfantasyxx.ui.components.formatSetDuration
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yfantasyxx.ui.components.ActionText
import androidx.compose.foundation.lazy.items

class SessionDetailViewModel(private val repo: WorkoutRepository) : ViewModel() {
    private val _details = MutableStateFlow<List<SessionExerciseWithSets>>(emptyList())
    val details: StateFlow<List<SessionExerciseWithSets>> = _details.asStateFlow()

    private val _session = MutableStateFlow<WorkoutSession?>(null)
    val session: StateFlow<WorkoutSession?> = _session.asStateFlow()

    /** 首帧是否已经读到数据：区分「还没读」与「真的被删了」 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 最近一次体重：自重动作的容量要加它，见 data/Load.kt */
    private val _bodyweight = MutableStateFlow<Float?>(null)
    val bodyweight: StateFlow<Float?> = _bodyweight.asStateFlow()

    /** 每个动作的「上次」对照，key 是 exerciseId */
    private val _previous = MutableStateFlow<Map<Long, PreviousSetRow>>(emptyMap())
    val previous: StateFlow<Map<Long, PreviousSetRow>> = _previous.asStateFlow()

    /** 主组 id → 递减段 */
    private val _segments = MutableStateFlow<Map<Long, List<SetSegment>>>(emptyMap())
    val segments: StateFlow<Map<Long, List<SetSegment>>> = _segments.asStateFlow()

    /**
     * 已经查过「上次」的动作。
     *
     * 明细是持续观察的，改一次重量就会重新发射；不记这一笔的话每改一次
     * 都要把全部动作的「上次」重查一遍。
     */
    private val compared = HashSet<Long>()

    private var observeJob: kotlinx.coroutines.Job? = null
    private var segmentJob: kotlinx.coroutines.Job? = null
    private var sessionId: Long? = null

    /**
     * 改成持续观察而不是读一次快照：编辑组数据后总容量、组数、
     * 每一行的值都要立刻跟着变，靠手动 reload 太容易漏。
     */
    fun load(id: Long) {
        sessionId = id
        observeJob?.cancel()
        compared.clear()
        _previous.value = emptyMap()
        observeJob = viewModelScope.launch {
            repo.observeSessionDetails(id).collect { list ->
                _details.value = list
                _loaded.value = true
                loadPrevious(list)
            }
        }
        viewModelScope.launch {
            _session.value = repo.getSession(id)
            // 会话和明细谁先到都有可能，两边都试着补一次；
            // 时间边界要用会话自己的开始时刻，所以先拿到会话才算得准
            loadPrevious(_details.value)
        }
        viewModelScope.launch { _bodyweight.value = repo.latestWeightKg() }
        // 递减段单独一路：不在关系类里（见 SetSegment 的注释）
        segmentJob?.cancel()
        segmentJob = viewModelScope.launch {
            repo.observeSegments(id).collect { _segments.value = it }
        }
    }

    /**
     * 补查「上次这个动作练了多少」。
     *
     * 只查没查过的动作，查完记进 [compared]。放在 IO 上执行：
     * 有多少个动作就是多少次单行查询，不该占用主线程。
     */
    private fun loadPrevious(items: List<SessionExerciseWithSets>) {
        val session = _session.value ?: return
        val before = session.activeStartedAt ?: session.startTime
        val missing = items.map { it.exercise.id }.distinct().filter { it !in compared }
        if (missing.isEmpty()) return
        compared += missing
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                missing.mapNotNull { exerciseId ->
                    runCatching {
                        repo.previousBestSet(exerciseId, before, session.id)
                    }.getOrNull()?.let { exerciseId to it }
                }.toMap()
            }
            if (found.isNotEmpty()) _previous.value = _previous.value + found
        }
    }

    /** 改重量 / 次数：只动这一组，完成状态与所属会话都不变 */
    fun updateSet(set: com.yfantasyxx.data.db.SetLog, weightKg: Float, reps: Int) {
        if (set.weightKg == weightKg && set.reps == reps) return
        viewModelScope.launch {
            runCatching {
                // 写之前重读一次：界面手里的可能是几帧前的快照，
                // 直接 copy 会把期间别处的改动（热身标记等）一起盖回去
                val fresh = repo.getSet(set.id) ?: set
                repo.updateSet(fresh.copy(weightKg = weightKg, reps = reps))
            }
        }
    }

    /** 删掉一组；后面的组重新编号，避免出现 1、2、4 */
    fun deleteSet(id: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteSetAndRenumber(id) }
        }
    }

    /** 补一段递减（沿用这一组的重量次数，改完就生效） */
    fun addSegment(set: SetLog) {
        viewModelScope.launch {
            runCatching { repo.addSegment(set.id, set.weightKg, set.reps) }
        }
    }

    fun updateSegment(segment: SetSegment, weightKg: Float, reps: Int) {
        if (segment.weightKg == weightKg && segment.reps == reps) return
        viewModelScope.launch {
            runCatching { repo.updateSegment(segment.id, weightKg, reps) }
        }
    }

    fun deleteSegment(segment: SetSegment) {
        viewModelScope.launch {
            runCatching { repo.deleteSegmentAndRenumber(segment.id) }
        }
    }

    /**
     * 补一组：沿用当前最后一组的重量与次数，并直接标为已完成——
     * 已经结束的训练里没有「计划中还没做」的组，补进去就是做了。
     */
    fun addSet(item: SessionExerciseWithSets) {
        val last = item.sets.maxByOrNull { it.setNumber }
        viewModelScope.launch {
            runCatching {
                repo.addSet(
                    sessionExerciseId = item.sessionExercise.id,
                    weightKg = last?.weightKg ?: 0f,
                    reps = last?.reps ?: 0,
                    completed = true,
                )
            }
        }
    }

    /** 补动作：新动作的组同样直接算完成，默认 3 组便于加减 */
    fun addExercise(exerciseId: Long) {
        val id = sessionId ?: return
        viewModelScope.launch {
            runCatching { repo.addExerciseToSession(id, exerciseId, completed = true) }
        }
    }

    /** 新建自定义动作并直接补进这次训练 */
    fun addCustomExercise(name: String, category: String, equipment: String, muscle: String) {
        val id = sessionId ?: return
        viewModelScope.launch {
            runCatching {
                val exerciseId = repo.addCustomExercise(name, category, equipment, muscle)
                repo.addExerciseToSession(id, exerciseId, completed = true)
            }
        }
    }

    fun removeExercise(sessionExerciseId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeSessionExercise(sessionExerciseId) }
        }
    }

    fun rename(name: String, note: String) {
        val id = sessionId ?: return
        viewModelScope.launch {
            runCatching {
                repo.updateSessionName(id, name.trim(), note.trim())
                _session.value = repo.getSession(id)
            }
        }
    }

    fun deleteSession(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteSession(id)
            onDone()
        }
    }

    /** 各档的实际换算曲线，分享卡片挑亮点时用 */
    suspend fun curves(): Map<Int, com.yfantasyxx.data.PatternCalibration> = repo.calibration()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SessionDetailViewModel(container.workoutRepository) }
        }
    }
}

@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SessionDetailViewModel = viewModel(factory = SessionDetailViewModel.factory(container))
    val details by vm.details.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val bodyweight by vm.bodyweight.collectAsStateWithLifecycle()
    val previous by vm.previous.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SessionExerciseWithSets?>(null) }

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    val totalVolume = details.sumOf { item ->
        item.sets.filter { it.completed && !it.isWarmup }
            .sumOf { set ->
                // 自重动作的 weightKg 是额外负重，容量要把体重算进去
                val main = (Load.effectiveKg(set.weightKg, item.exercise.equipment, bodyweight) * set.reps)
                    .toDouble()
                // 递减段各算各的：这一组练了两个重量，只算主组会把量记少
                val drops = segments[set.id].orEmpty().sumOf { sg ->
                    (Load.effectiveKg(sg.weightKg, item.exercise.equipment, bodyweight) * sg.reps)
                        .toDouble()
                }
                main + drops
            }
    }.toFloat()
    // 组数分两种口径：热身组不算进「完成组数」，否则 20 组的训练里
    // 有 6 组热身时会显得练得比实际多
    val warmupSets = details.sumOf { item -> item.sets.count { it.completed && it.isWarmup } }
    val workingSets = details.sumOf { item -> item.sets.count { it.completed && !it.isWarmup } }
    val prSets = details.sumOf { item -> item.sets.count { it.completed && it.isPr } }
    // 「动作数」只数真的练过的：加进来又一组没做的动作不该算
    val trainedExercises = details.count { item -> item.sets.any { it.completed } }

    val startedAt = session?.let { it.activeStartedAt ?: it.startTime }
    val endedAt = session?.endTime
    val durationMs = if (startedAt != null && endedAt != null) {
        (endedAt - startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    // 分享卡片：画图 + PNG 压缩放到 IO 线程，点一下不会卡住界面
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun shareCard() {
        val s = session ?: return
        scope.launch {
            // 曲线要先拿：亮点按 e1RM 挑，群体表和个人校准可能选出不同的组
            val curves = runCatching { vm.curves() }.getOrDefault(emptyMap())
            val data = com.yfantasyxx.util.ShareCard.Data(
                title = s.name.ifBlank { "训练" },
                date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(s.startTime)),
                durationMinutes = (((s.endTime ?: System.currentTimeMillis()) -
                    (s.activeStartedAt ?: s.startTime)) / 60000).toInt(),
                exerciseCount = trainedExercises,
                // 与详情页口径一致：热身组不算进组数（整场只有热身时再退回总数）
                setCount = if (workingSets > 0) workingSets else warmupSets,
                volumeKg = totalVolume,
                // 亮点按每个动作最强的一组取，最多三条
                highlights = details
                    .sortedByDescending { item ->
                        item.sets.filter { it.completed }
                            .sumOf {
                                (com.yfantasyxx.data.Load
                                    .effectiveKg(it.weightKg, item.exercise.equipment, bodyweight) * it.reps).toDouble()
                            }
                    }
                    .take(3)
                    .mapNotNull { item ->
                        val pattern = com.yfantasyxx.data.LiftPattern
                            .fromValue(item.exercise.pattern)
                        val curve = curves[pattern.value] ?: pattern
                        val best = item.sets
                            .filter { it.completed && !it.isWarmup }
                            .maxByOrNull {
                                com.yfantasyxx.data.OneRepMax
                                    .estimate(it.weightKg, it.reps, curve) ?: -1f
                            } ?: return@mapNotNull null
                        "${item.exercise.name} ${best.weightKg.toInt()} kg × ${best.reps}"
                    },
            )
            runCatching {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.yfantasyxx.util.ShareCard.write(context, data)
                }
                com.yfantasyxx.util.ShareCard.share(context, file)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (editing) {
                    "修改记录"
                } else {
                    session?.name?.ifEmpty { "训练详情" } ?: "训练详情"
                },
                subtitle = if (editing) {
                    // 说明清楚：改的是内容，不是这次训练发生的时间
                    "改完点右上角 ✓ · 训练日期与计划进度不变"
                } else {
                    session?.let {
                        SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(it.startTime))
                    } ?: ""
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (editing) {
                        IconButton(onClick = { editing = false }) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "完成编辑",
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(onClick = { shareCard() }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "分享",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { editing = true }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "修改记录",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            // 首帧还没读到：先什么都不画，免得闪一下「记录已被删除」
            !loaded -> Unit
            // 用 session 判断「记录是否存在」：details 为空也可能是
            // 用户把动作全删光了，那时候记录还在，不能显示成「已被删除」
            session == null -> EmptyState(text = "记录不存在或已被删除", action = {
                ActionText(text = "返回", onClick = onBack)
            })
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // 以前这里只有动作数/组数/容量：几点练的、练了多久
                            // 都要退回列表去看，或者自己拿开始时刻减
                            if (startedAt != null) {
                                InfoRow(
                                    label = "时间",
                                    value = buildString {
                                        append(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(startedAt)))
                                        append(" – ")
                                        append(
                                            endedAt?.let {
                                                SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it))
                                            } ?: "进行中"
                                        )
                                    },
                                )
                                // 没结束的会话没有时长可言，别显示成「不到 1 分钟」
                                if (endedAt != null) {
                                    InfoRow("训练时长", formatSessionDuration(durationMs))
                                }
                            }
                            InfoRow("动作数", "$trainedExercises 个")
                            InfoRow(
                                label = "完成组数",
                                value = if (warmupSets > 0) {
                                    "$workingSets 组（热身 $warmupSets 组）"
                                } else {
                                    "$workingSets 组"
                                },
                            )
                            if (prSets > 0) {
                                InfoRow("PR", "$prSets 组")
                            }
                            InfoRow("总容量", "${formatVolume(totalVolume)} kg")
                            session?.note?.takeIf { it.isNotBlank() }?.let {
                                InfoRow("备注", it)
                            }
                            if (editing) {
                                Spacer(Modifier.height(6.dp))
                                ActionText(
                                    text = "修改名称 / 备注",
                                    onClick = { showRename = true },
                                )
                            }
                        }
                    }
                }
                item { SectionHeader("动作明细") }
                items(details, key = { it.sessionExercise.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.exercise.name, style = MiuixTheme.textStyles.main)
                                    // 模板设定的目标次数，如「目标 8-12」
                                    item.sessionExercise.plannedReps
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { reps ->
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "目标 $reps",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    // 与上一次练同一个动作的对照：翻历史时最想知道的就是
                                    // 「这次比上次强了还是弱了」，不该让人自己逐个去翻
                                    previous[item.exercise.id]?.let { prev ->
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = previousLabel(prev, item.exercise.equipment),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                                if (editing) {
                                    IconButton(onClick = { removeTarget = item }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "移除这个动作",
                                            tint = MiuixTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            item.sets.sortedBy { it.setNumber }.forEach { set ->
                                val drops = segments[set.id].orEmpty().sortedBy { it.seq }
                                if (editing) {
                                    EditableSetRow(
                                        set = set,
                                        onUpdate = { weight, reps -> vm.updateSet(set, weight, reps) },
                                        onDelete = { vm.deleteSet(set.id) },
                                    )
                                } else {
                                    ReadOnlySetRow(set)
                                }
                                // 递减段：缩进、小一号，跟在主组下面
                                drops.forEach { segment ->
                                    if (editing) {
                                        EditableSegmentRow(
                                            setNumber = set.setNumber,
                                            segment = segment,
                                            onUpdate = { w, r -> vm.updateSegment(segment, w, r) },
                                            onDelete = { vm.deleteSegment(segment) },
                                        )
                                    } else {
                                        ReadOnlySegmentRow(set.setNumber, segment)
                                    }
                                }
                            }
                            if (editing) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ActionText(text = "+ 添加一组", onClick = { vm.addSet(item) })
                                    // 补递减段挂在最后一组下面：力竭后降重量续做，
                                    // 几乎都发生在最后那一组
                                    item.sets.maxByOrNull { it.setNumber }?.let { last ->
                                        ActionText(
                                            text = "+ 递减",
                                            onClick = { vm.addSegment(last) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (editing) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            onClick = { showAddExercise = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "添加动作",
                                    style = MiuixTheme.textStyles.main,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDelete) {
        AppDialog(
            title = "删除这条训练记录？",
            summary = "删除后无法恢复",
            onDismissRequest = { showDelete = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { showDelete = false })
                ActionText(text = "删除", onClick = {
                    showDelete = false
                    vm.deleteSession(sessionId) { onBack() }
                }, color = MiuixTheme.colorScheme.error)
            }
        }
    }

    // 移除动作会连带删掉它下面所有的组，先问一句
    removeTarget?.let { target ->
        AppDialog(
            title = "移除「${target.exercise.name}」？",
            summary = "这个动作下 ${target.sets.size} 组记录会一起删除",
            onDismissRequest = { removeTarget = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { removeTarget = null })
                ActionText(text = "移除", onClick = {
                    vm.removeExercise(target.sessionExercise.id)
                    removeTarget = null
                }, color = MiuixTheme.colorScheme.error)
            }
        }
    }

    if (showRename) {
        val current = session
        RenameSessionDialog(
            currentName = current?.name.orEmpty(),
            currentNote = current?.note.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { name, note ->
                vm.rename(name, note)
                showRename = false
            },
        )
    }

    if (showAddExercise) {
        com.yfantasyxx.ui.workout.ExercisePickerDialog(
            container = container,
            onDismiss = { showAddExercise = false },
            onPick = { exerciseId ->
                vm.addExercise(exerciseId)
                showAddExercise = false
            },
            onRequestCreate = {
                showAddExercise = false
                showAddCustom = true
            },
        )
    }

    if (showAddCustom) {
        com.yfantasyxx.ui.components.AddExerciseDialog(
            onDismiss = { showAddCustom = false },
            onConfirm = { name, category, equipment, muscle ->
                vm.addCustomExercise(name, category, equipment, muscle)
                showAddCustom = false
            },
        )
    }
}

/** 只读的一组：和编辑态保持同样的行高节奏，切换时不会跳 */
@Composable
private fun ReadOnlySetRow(set: SetLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // PR 组单独标出来：同一行里数字看起来差不多，但它代表的意义不一样
            text = buildString {
                append("第 ${set.setNumber} 组")
                if (set.isWarmup) append("（热身）")
                if (set.isPr) append("（PR）")
            },
            style = MiuixTheme.textStyles.paragraph,
            color = if (set.completed) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${set.weightKg.toInt()} kg × ${set.reps} 次" +
                    (set.rpe?.let { " · RPE ${it.toInt()}" } ?: ""),
                style = MiuixTheme.textStyles.paragraph,
                color = if (set.completed) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                modifier = Modifier.alignByBaseline(),
            )
            // 组内用时（两次休息之间隔了多久，见 util/SetClock）。和训练页同一套写法：
            // 小一号的次要色跟在后面，不抢重量次数的注意力。
            // 0 表示没记录：还没做的组，以及 v15 及更早练的（那时候根本没记），
            // 这时候整格不画 —— 写「0s」会被当成「一秒做完了」
            if (set.durationMs > 0L) {
                Text(
                    text = formatSetDuration(set.durationMs),
                    // 按基线对齐：小字号用居中会对齐到行盒中心，
                    // 数字看起来比旁边的中文矮半截，像掉在下面
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 递减段（只读）：缩进 + 小一号，挂在主组下面。
 *
 * 组号写成 3a / 3b，和训练页一致 —— 「3 组降重量续做了一次」，
 * 组数仍然只算一组。
 */
@Composable
private fun ReadOnlySegmentRow(setNumber: Int, segment: SetSegment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$setNumber${segment.label}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = "${segment.weightKg.toInt()} kg × ${segment.reps} 次",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 递减段（编辑）：两个小一号步进器 + 删除。
 *
 * 改动即时落库，和组行同一套做法：没有保存按钮，退出编辑态就是保存。
 */
@Composable
private fun EditableSegmentRow(
    setNumber: Int,
    segment: SetSegment,
    onUpdate: (Float, Int) -> Unit,
    onDelete: () -> Unit,
) {
    var weight by remember(segment.id) { mutableStateOf(segment.weightKg) }
    var reps by remember(segment.id) { mutableStateOf(segment.reps.toFloat()) }

    LaunchedEffect(weight, reps) {
        val r = reps.toInt()
        if (weight != segment.weightKg || r != segment.reps) onUpdate(weight, r)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$setNumber${segment.label}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp),
        )
        NumberStepper(
            value = weight,
            onValueChange = { weight = it },
            step = 2.5f,
            rememberKey = segment.id,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        NumberStepper(
            value = reps,
            onValueChange = { reps = it },
            step = 1f,
            rememberKey = segment.id,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        // 用 22dp 小圆点：一行里已经有两个步进器，标准按钮会把数字挤扁
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除第 $setNumber${segment.label} 段",
                tint = MiuixTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 「上次 60 kg × 8 次 · 3 天前」。
 *
 * 自重动作的 weightKg 存的是额外负重，直接写「0 kg」会让人以为没练，
 * 所以换成「自重」或「自重 +20 kg」。
 */
private fun previousLabel(row: PreviousSetRow, equipment: String): String {
    val set = row.set
    val weight = if (Load.isBodyweight(equipment)) {
        if (set.weightKg > 0f) "自重 +${set.weightKg.toInt()} kg" else "自重"
    } else {
        "${set.weightKg.toInt()} kg"
    }
    return "上次 $weight × ${set.reps} 次 · ${relativeDay(row.sessionStart)}"
}

/** 一周以内说「几天前」，更久直接给日期——「43 天前」读起来没有意义 */
private fun relativeDay(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(zone))
    return when {
        days <= 0L -> "今天"
        days == 1L -> "昨天"
        days < 7L -> "$days 天前"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

/**
 * 可编辑的一组：重量、次数各自一个步进器，右侧删除。
 *
 * 改动即时落库（和训练页同一套做法）：没有「保存」按钮，退出编辑态就是保存，
 * 用户不需要理解草稿与已确认的区别。本地值用 remember(set.id) 持有，
 * 所以 Flow 回推同一个值时不会把正在加减的数字打回。
 */
@Composable
private fun EditableSetRow(
    set: SetLog,
    onUpdate: (Float, Int) -> Unit,
    onDelete: () -> Unit,
) {
    var weight by remember(set.id) { mutableStateOf(set.weightKg) }
    var reps by remember(set.id) { mutableStateOf(set.reps.toFloat()) }

    LaunchedEffect(weight, reps) {
        val r = reps.toInt()
        if (weight != set.weightKg || r != set.reps) onUpdate(weight, r)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                // 和训练页的组号一致：实心主色 + onPrimary 的白字。
                // 以前是 primaryContainer 底 + primary 字，两色太接近，
                // 截图里组号几乎看不见（浅蓝字压在浅蓝底上）
                .background(MiuixTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = set.setNumber.toString(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(8.dp))
        NumberStepper(
            value = weight,
            onValueChange = { weight = it },
            step = 2.5f,
            rememberKey = set.id,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        NumberStepper(
            value = reps,
            onValueChange = { reps = it },
            step = 1f,
            rememberKey = set.id,
            modifier = Modifier.weight(1f),
        )
        // 用 22dp 的小圆点而不是标准按钮：两个步进器已经占满一行，
        // 标准 IconButton 的 40dp 最小点击区会把数字挤扁
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除第 ${set.setNumber} 组",
                tint = MiuixTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun RenameSessionDialog(
    currentName: String,
    currentNote: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    var note by remember(currentNote) { mutableStateOf(currentNote) }
    AppDialog(title = "修改名称与备注", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = name,
                onValueChange = { name = it },
                label = "训练名称",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onConfirm(name, note) }) {
                    Text("保存", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
