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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.data.db.WorkoutSession
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private var observeJob: kotlinx.coroutines.Job? = null
    private var sessionId: Long? = null

    /**
     * 改成持续观察而不是读一次快照：编辑组数据后总容量、组数、
     * 每一行的值都要立刻跟着变，靠手动 reload 太容易漏。
     */
    fun load(id: Long) {
        sessionId = id
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeSessionDetails(id).collect { list ->
                _details.value = list
                _loaded.value = true
            }
        }
        viewModelScope.launch { _session.value = repo.getSession(id) }
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
    val details by vm.details.collectAsState()
    val session by vm.session.collectAsState()
    val loaded by vm.loaded.collectAsState()
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SessionExerciseWithSets?>(null) }

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    val totalVolume = details.sumOf { item ->
        item.sets.filter { it.completed && !it.isWarmup }
            .sumOf { (it.weightKg * it.reps).toDouble() }
    }.toFloat()
    val totalSets = details.sumOf { item -> item.sets.count { it.completed } }

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
                exerciseCount = details.count { item -> item.sets.any { it.completed } },
                setCount = totalSets,
                volumeKg = totalVolume,
                // 亮点按每个动作最强的一组取，最多三条
                highlights = details
                    .sortedByDescending { item ->
                        item.sets.filter { it.completed }
                            .sumOf { (it.weightKg * it.reps).toDouble() }
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
                            imageVector = Icons.Filled.ArrowBack,
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
            details.isEmpty() -> EmptyState(text = "记录不存在或已被删除", action = {
                ActionText(text = "返回", onClick = onBack)
            })
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InfoRow("动作数", "${details.size} 个")
                            InfoRow("完成组数", "$totalSets 组")
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
                                if (editing) {
                                    EditableSetRow(
                                        set = set,
                                        onUpdate = { weight, reps -> vm.updateSet(set, weight, reps) },
                                        onDelete = { vm.deleteSet(set.id) },
                                    )
                                } else {
                                    ReadOnlySetRow(set)
                                }
                            }
                            if (editing) {
                                Spacer(Modifier.height(4.dp))
                                ActionText(text = "+ 添加一组", onClick = { vm.addSet(item) })
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
    ) {
        Text(
            text = "第 ${set.setNumber} 组" + if (set.isWarmup) "（热身）" else "",
            style = MiuixTheme.textStyles.paragraph,
            color = if (set.completed) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
        Text(
            text = "${set.weightKg.toInt()} kg × ${set.reps} 次" +
                (set.rpe?.let { " · RPE ${it.toInt()}" } ?: ""),
            style = MiuixTheme.textStyles.paragraph,
            color = if (set.completed) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/**
 * 可编辑的一组：重量、次数各一个步进器，右侧删除。
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
                .background(MiuixTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = set.setNumber.toString(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        NumberStepper(
            value = weight,
            onValueChange = { weight = it },
            step = 2.5f,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        NumberStepper(
            value = reps,
            onValueChange = { reps = it },
            step = 1f,
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
