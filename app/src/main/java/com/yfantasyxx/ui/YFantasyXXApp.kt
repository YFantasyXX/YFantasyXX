package com.yfantasyxx.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.navigation.NavBackStackEntry
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
import com.yfantasyxx.ui.theme.Motion
import com.yfantasyxx.util.SafeModeGuard
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

/** 一级页签的 route 集合：用来区分「切页签」和「进二级页」两种转场 */
private val tabRouteSet: Set<String> = tabs.map { it.route }.toSet()

private fun NavBackStackEntry.isTabRoute(): Boolean = destination.route in tabRouteSet

/**
 * 两个 destination 之间是不是「一级页签互相切换」。
 *
 * 只有这一种情况用淡入淡出：五个页签是并列关系，横着滑会让人误以为
 * 它们在一条可来回翻的轨道上，来回切两次就分不清左右了。
 */
private fun isTabSwap(initial: NavBackStackEntry, target: NavBackStackEntry): Boolean =
    initial.isTabRoute() && target.isTabRoute()

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
private fun YFantasyXXNavHost() {
    val container = LocalAppContainer.current
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
            // 安全模式降级：液态玻璃是启动崩溃的头号嫌疑（离屏采集层 + RuntimeShader），
            // 本次进程直接按悬浮胶囊走，毛玻璃也关掉。用户设置一个字都不改，
            // 下次冷启动会重新判定。
            if (SafeModeGuard.degraded) {
                com.yfantasyxx.data.prefs.BottomBarStyle.FLOATING to false
            } else {
                s.bottomBarStyle to s.blurBottomBar
            }
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
                AdaptiveContentWidth {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                        // ───────── 转场设计 ─────────
                        //
                        // 一级页签之间：纯淡入淡出 + 极轻微的缩放。
                        // 五个页签是并列关系，左右滑动会暗示「它们在一条可以来回翻的
                        // 轨道上」，来回切两次方向感就没了，所以只做透明度过渡。
                        //
                        // 进二级页：做「容器转场」——新页从右侧推入的同时**缩小**
                        // （0.92 → 1.0），旧页向左让出四分之一屏的同时**缩得更小**
                        // （1.0 → 0.92）并压暗。这一层缩放是层次感的关键：
                        // 只有平移的话，两页始终在同一平面上，看起来像两张纸
                        // 在互相推；加上缩放就有了「近—远」，眼睛立刻读出
                        // 「刚才那页退到后面去了」。
                        //
                        // 返回时严格反过来：旧页从左侧回来并放大，新页向右退出并缩小。
                        // 两边必须严格互逆，否则按返回会有「弹错一下」的感觉。
                        //
                        // 位移和缩放都由弹簧驱动（[Motion.emphasized]）：
                        // 位移到一半被打断（比如用户连按返回）时能续上速度，
                        // 不会从当前位置重新按曲线走一遍、中间拐个弯。
                        enterTransition = {
                            when {
                                // 安全模式降级：转场会给每个页面套一层 graphicsLayer，
                                // 而页面恰好位于 backdrop 的采集层内部。两者叠加是
                                // 启动崩溃的另一个嫌疑点，降级时干脆不转场。
                                SafeModeGuard.degraded -> EnterTransition.None
                                isTabSwap(initialState, targetState) ->
                                    fadeIn(Motion.standard(Motion.DURATION_FAST))
                                else -> slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = Motion.emphasized(),
                                    initialOffset = { it },
                                ) + fadeIn(Motion.decelerate(Motion.DURATION_FAST)) +
                                    scaleIn(
                                        animationSpec = Motion.emphasized(),
                                        initialScale = 0.92f,
                                    )
                            }
                        },
                        exitTransition = {
                            when {
                                SafeModeGuard.degraded -> ExitTransition.None
                                isTabSwap(initialState, targetState) ->
                                    fadeOut(Motion.accelerate(Motion.DURATION_FAST))
                                else -> slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = Motion.emphasized(),
                                    targetOffset = { -it / 4 },
                                ) + fadeOut(Motion.accelerate(Motion.DURATION_FAST)) +
                                    scaleOut(
                                        animationSpec = Motion.emphasized(),
                                        targetScale = 0.92f,
                                    )
                            }
                        },
                        popEnterTransition = {
                            when {
                                SafeModeGuard.degraded -> EnterTransition.None
                                isTabSwap(initialState, targetState) ->
                                    fadeIn(Motion.standard(Motion.DURATION_FAST))
                                else -> slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = Motion.emphasized(),
                                    initialOffset = { -it / 4 },
                                ) + fadeIn(Motion.decelerate(Motion.DURATION_FAST)) +
                                    scaleIn(
                                        animationSpec = Motion.emphasized(),
                                        initialScale = 0.92f,
                                    )
                            }
                        },
                        popExitTransition = {
                            when {
                                SafeModeGuard.degraded -> ExitTransition.None
                                isTabSwap(initialState, targetState) ->
                                    fadeOut(Motion.accelerate(Motion.DURATION_FAST))
                                else -> slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = Motion.emphasized(),
                                    targetOffset = { it },
                                ) + fadeOut(Motion.accelerate(Motion.DURATION_FAST)) +
                                    scaleOut(
                                        animationSpec = Motion.emphasized(),
                                        targetScale = 0.92f,
                                    )
                            }
                        },
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                onStartWorkout = { navController.navigateSingleTop(Routes.WORKOUT) },
                                onOpenTemplates = { navController.navigateSingleTop(Routes.TEMPLATES) },
                                onOpenSession = { id ->
                                    navController.navigateSingleTop(Routes.sessionDetail(id))
                                },
                                onStartFromTemplate = { navController.navigateSingleTop(Routes.WORKOUT) },
                            )
                        }
                        composable(Routes.EXERCISES) {
                            ExercisesScreen(
                                onOpenExercise = { id ->
                                    navController.navigateSingleTop(Routes.exerciseDetail(id))
                                },
                            )
                        }
                        composable(Routes.HISTORY) {
                            HistoryScreen(
                                onOpenSession = { id ->
                                    navController.navigateSingleTop(Routes.sessionDetail(id))
                                },
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
                                onEditTemplate = { id ->
                                    navController.navigateSingleTop(Routes.templateEdit(id))
                                },
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
            }

            // 底栏必须在采集层**外面**，这是硬约束，不能改。
            //
            // layerBackdrop(backdrop) 会把这一层的绘制内容录进 backdrop 图层；
            // 而玻璃底栏自己又是用 drawBackdrop(backdrop) 去采样这个图层的。
            // 一旦玻璃被录进它自己采样的那一层，就成了自引用：
            // 采集 → 采样 → 再采集 → 再采样……
            //
            // HWUI 走 prepareTree 时会顺着这个环无限递归，直接把栈打爆：
            //   Fatal signal 11 (SIGSEGV) in tid __ (RenderThread)
            //   Cause: stack pointer is not in a rw map; likely due to stack overflow
            // 512 帧全在 RenderNode::prepareTreeImpl ↔ prepareListAndChildren 之间打转。
            // （Backdrop 官方文档把这个坑写得很清楚：glass on glass 必须用
            //   exportedBackdrop，否则 "it's a loop"。）
            //
            // 所以这里采集层只装页面内容，玻璃底栏跟它平级，各画各的。
            // v1.6 就是这么写的，一直没事；vc128 给 NavHost 加了宽度约束时
            // 多套了一层 Box，顺手把底栏也裹进了采集层里，于是开始崩。
            //
            // 底栏另外还不能包在 AnimatedVisibility / graphicsLayer 里：
            // 那样它会多一层正在做 alpha 的离屏图层，采样层与被采样层又在
            // 同一棵离屏树上互相引用，同样会爆栈。所以保持普通条件渲染。
            //
            // 进二级页时底栏「瞬间消失」并不难看：页面本身是整屏推入的，
            // 底栏被新页盖住，没人会盯着它看。
            if (showBottomBar) {
                BottomBarHost(
                    container = container,
                    tabs = tabs,
                    selectedRoute = currentRoute,
                    backdrop = backdrop,
                    style = bottomBarStyle,
                    glass = blurBottomBar,
                    onSelect = onTabSelected,
                    // 宽屏上底栏不要横贯整屏：药丸拉成 800dp 宽就没有「悬浮」的
                    // 感觉了，五个图标会被摊得离得很远
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 520.dp),
                )
            }
        }
    }
}

/**
 * 内容宽度上限。
 *
 * 手机上（< 600dp）这一层完全不生效，约束原样透传；平板上把内容列收到 600dp
 * 居中，卡片不会被拉成一整屏宽、一句话排六十多个字符——那种排版眼睛要来回扫，
 * 而且同一张卡片在手机和平板上会像两个不同的设计。
 */
@Composable
private fun AdaptiveContentWidth(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
        ) {
            content()
        }
    }
}
