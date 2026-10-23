package com.yfantasyxx.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yfantasyxx.YFantasyXXApplication
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
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

    fun templateEdit(id: Long? = null) = "template_edit?id=$id"
    fun sessionDetail(id: Long) = "session_detail/$id"
    fun exerciseDetail(id: Long) = "exercise_detail/$id"
}

private data class TabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem(Routes.HOME, "今天", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem(Routes.EXERCISES, "动作", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter),
    TabItem(Routes.HISTORY, "历史", Icons.Filled.History, Icons.Outlined.History),
    TabItem(Routes.STATS, "统计", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    TabItem(Routes.SETTINGS, "我的", Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun YFantasyXXApp() {
    val container = (LocalContext.current.applicationContext as YFantasyXXApplication).container
    CompositionLocalProvider(LocalAppContainer provides container) {
        YFantasyXXNavHost()
    }
}

@Composable
private fun YFantasyXXNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = if (selected) tab.selectedIcon else tab.icon,
                            label = tab.label,
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onStartWorkout = { navController.navigate(Routes.WORKOUT) },
                    onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                    onOpenSession = { id -> navController.navigate(Routes.sessionDetail(id)) },
                    onStartFromTemplate = { navController.navigate(Routes.WORKOUT) },
                )
            }
            composable(Routes.EXERCISES) {
                ExercisesScreen(
                    onOpenExercise = { id -> navController.navigate(Routes.exerciseDetail(id)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenSession = { id -> navController.navigate(Routes.sessionDetail(id)) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                )
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen(
                    onEditTemplate = { id -> navController.navigate(Routes.templateEdit(id)) },
                    onCreateTemplate = { navController.navigate(Routes.templateEdit(null)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("${Routes.TEMPLATE_EDIT}?id={id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                TemplateEditScreen(
                    templateId = id,
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
