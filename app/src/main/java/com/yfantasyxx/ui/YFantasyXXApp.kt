package com.yfantasyxx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yfantasyxx.YFantasyXXApplication
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.prefs.BottomBarStyle
import com.yfantasyxx.update.UpdateState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.ui.nav.FloatingBottomBar
import com.yfantasyxx.ui.nav.FloatingTab
import com.yfantasyxx.ui.nav.LiquidGlassBottomBar
import com.yfantasyxx.ui.exercises.ExerciseDetailScreen
import com.yfantasyxx.ui.exercises.ExercisesScreen
import com.yfantasyxx.ui.history.HistoryScreen
import com.yfantasyxx.ui.history.SessionDetailScreen
import com.yfantasyxx.ui.home.HomeScreen
import com.yfantasyxx.ui.settings.SettingsScreen
import com.yfantasyxx.ui.stats.StatsScreen
import com.yfantasyxx.ui.templates.TemplateEditScreen
import com.yfantasyxx.ui.templates.TemplatesScreen
import com.yfantasyxx.ui.workout.ActiveWorkoutScreen
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

object Routes {
    const val HOME = "home"
    const val EXERCISES = "exercises"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val TEMPLATES = "templates"
    const val TEMPLATE_EDIT = "template_edit"
    const val WORKOUT = "workout"
    const val SESSION_DETAIL = "session_detail"
    const val EXERCISE_DETAIL = "exercise_detail"

    /** 只在 lite 版可达：入口由 [com.yfantasyxx.ui.dev.DevTools.enabled] 控制 */
    const val DEV_TOOLS = "dev_tools"

    fun templateEdit(id: Long? = null, folderId: Long? = null) =
        "template_edit?id=$id&folder=$folderId"
    fun sessionDetail(id: Long) = "session_detail/$id"
    fun exerciseDetail(id: Long) = "exercise_detail/$id"
}

private val tabs = listOf(
    FloatingTab(Routes.HOME, "今天", Icons.Filled.Home, Icons.Outlined.Home),
    FloatingTab(Routes.EXERCISES, "动作", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter),
    FloatingTab(Routes.HISTORY, "历史", Icons.Filled.History, Icons.Outlined.History),
    FloatingTab(Routes.STATS, "统计", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    FloatingTab(Routes.SETTINGS, "我的", Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun YFantasyXXApp() {
    val container = (LocalContext.current.applicationContext as YFantasyXXApplication).container
    CompositionLocalProvider(LocalAppContainer provides container) {
        YFantasyXXNavHost()
    }
}

/**
 * 带 launchSingleTop 的跳转。
 *
 * 卡片双击、手指抖一下都会连着下发两次 navigate，压出两层一模一样的页面：
 * 用户返回一次还停在原页，看起来就是「返回没反应」。
 */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * 底栏宿主。
 *
 * 单独抽一层是为了隔离重组：这里要读「有没有新版本」，
 * 如果直接在 [YFantasyXXNavHost] 里 collect，状态一变整张导航图
 * （`remember(route, startDestination, builder)` 建的那份）会跟着重建。
 * 下载进度每秒变好几次，更不能让它碰到 NavHost。
 */
@Composable
private fun BottomBarHost(
    container: AppContainer,
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    backdrop: LayerBackdrop,
    style: BottomBarStyle,
    glass: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只关心一个布尔量：有新版本且用户还没看过详情。
    // 下载进度每秒变好几次，绝不能让它碰到 NavHost
    val hasUpdate by combine(
        container.updateRepository.state
            .map { it is UpdateState.Available || it is UpdateState.Ready },
        container.updateRepository.acknowledged,
    ) { available, seen -> available && !seen }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)

    val items = remember(tabs, hasUpdate) {
        if (!hasUpdate) {
            tabs
        } else {
            tabs.map { tab ->
                if (tab.route == Routes.SETTINGS) tab.copy(badge = true) else tab
            }
        }
    }

    if (style == BottomBarStyle.LIQUID_GLASS) {
        LiquidGlassBottomBar(
            tabs = items,
            selectedRoute = selectedRoute,
            backdrop = backdrop,
            onSelect = onSelect,
            modifier = modifier,
        )
    } else {
        FloatingBottomBar(
            tabs = items,
            selectedRoute = selectedRoute,
            backdrop = backdrop,
            glass = glass,
            onSelect = onSelect,
            modifier = modifier,
        )
    }
}

@Composable
private fun YFantasyXXNavHost() {    val container = LocalAppContainer.current
    // 只让底栏真正用到的两个字段参与组合，而不是整份 settings。
    //
    // 整份 settings 参与组合的话，任何一次设置写入（包括每次自动备份回来
    // 更新的 lastBackupTime）都会让这个函数重组；而 NavHost 的导航图是
    // `remember(route, startDestination, builder)` 建的，builder 每次重组
    // 都是新实例 → 整张图连同所有 destination 一起重建。
    //
    // 注意：derivedStateOf 只能追踪 Compose 的 State（快照系统），
    // 直接读 `StateFlow.value` 是不会失效的，所以必须先 collect 成 State 再派生。
    val settingsState = container.settingsRepository.settings.collectAsStateWithLifecycle()
    val barStyle by remember {
        derivedStateOf {
            val s = settingsState.value
            s.bottomBarStyle to s.blurBottomBar
        }
    }
    val (bottomBarStyle, blurBottomBar) = barStyle
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = tabs.any { it.route == currentRoute }
    // 液态玻璃药丸 64dp + 上下 10dp 留白；HyperOS 胶囊 64dp + 上下 14dp
    val navBarInset = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    val bottomSpace = when {
        !showBottomBar -> 0.dp
        bottomBarStyle == com.yfantasyxx.data.prefs.BottomBarStyle.LIQUID_GLASS ->
            84.dp + navBarInset
        else -> 92.dp + navBarInset
    }
    // 底栏毛玻璃的效果来源：采集这个内容层
    val backdrop = rememberLayerBackdrop()

    val onTabSelected: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(
        LocalBottomBarSpace provides if (showBottomBar) bottomSpace else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onStartWorkout = { navController.navigateSingleTop(Routes.WORKOUT) },
                    onOpenTemplates = { navController.navigateSingleTop(Routes.TEMPLATES) },
                    onOpenSession = { id -> navController.navigateSingleTop(Routes.sessionDetail(id)) },
                    onStartFromTemplate = { navController.navigateSingleTop(Routes.WORKOUT) },
                )
            }
            composable(Routes.EXERCISES) {
                ExercisesScreen(
                    onOpenExercise = { id -> navController.navigateSingleTop(Routes.exerciseDetail(id)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenSession = { id -> navController.navigateSingleTop(Routes.sessionDetail(id)) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenTemplates = { navController.navigateSingleTop(Routes.TEMPLATES) },
                    onOpenDevTools = { navController.navigateSingleTop(Routes.DEV_TOOLS) },
                )
            }
            // 路由本身也要关掉：只藏设置里的入口不够，留着这条 composable 就等于
            // 把整套测试代码（造数据、杀进程、时间旅行）打进正式包，R8 删不掉。
            if (com.yfantasyxx.ui.dev.DevTools.enabled) {
                composable(Routes.DEV_TOOLS) {
                    com.yfantasyxx.ui.dev.DevToolsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenWorkout = { navController.navigateSingleTop(Routes.WORKOUT) },
                    )
                }
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen(
                    onEditTemplate = { id -> navController.navigateSingleTop(Routes.templateEdit(id)) },
                    onCreateTemplate = { folderId ->
                        navController.navigateSingleTop(Routes.templateEdit(null, folderId))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("${Routes.TEMPLATE_EDIT}?id={id}&folder={folder}") { entry ->
                TemplateEditScreen(
                    templateId = entry.arguments?.getString("id")?.toLongOrNull(),
                    folderId = entry.arguments?.getString("folder")?.toLongOrNull(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.WORKOUT) {
                ActiveWorkoutScreen(onBack = { navController.popBackStack() })
            }
            composable("${Routes.SESSION_DETAIL}/{id}") { entry ->
                SessionDetailScreen(
                    sessionId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("${Routes.EXERCISE_DETAIL}/{id}") { entry ->
                ExerciseDetailScreen(
                    exerciseId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }

        if (showBottomBar) {
            BottomBarHost(
                container = container,
                tabs = tabs,
                selectedRoute = currentRoute,
                backdrop = backdrop,
                style = bottomBarStyle,
                glass = blurBottomBar,
                onSelect = onTabSelected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        }
    }
}
