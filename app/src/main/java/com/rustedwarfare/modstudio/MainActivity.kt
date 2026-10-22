package com.rustedwarfare.modstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rustedwarfare.modstudio.ui.ModStudioTheme
import com.rustedwarfare.modstudio.ui.screens.ArchiveScreen
import com.rustedwarfare.modstudio.ui.screens.AudioScreen
import com.rustedwarfare.modstudio.ui.screens.EditorScreen
import com.rustedwarfare.modstudio.ui.screens.ExportScreen
import com.rustedwarfare.modstudio.ui.screens.HomeScreen
import com.rustedwarfare.modstudio.ui.screens.ImageScreen
import com.rustedwarfare.modstudio.ui.screens.NewModWizardScreen
import com.rustedwarfare.modstudio.ui.screens.ProjectScreen
import com.rustedwarfare.modstudio.ui.screens.SettingsScreen
import com.rustedwarfare.modstudio.ui.screens.ToolsScreen
import com.rustedwarfare.modstudio.ui.screens.ValidatorScreen

object Routes {
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val ARCHIVE = "archive"
    const val EDITOR = "editor"
    const val TOOLS = "tools"
    const val VALIDATOR = "validator"
    const val IMAGE = "image"
    const val AUDIO = "audio"
    const val WIZARD = "wizard"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ModStudioApplication).graph
        setContent {
            ModStudioTheme(settings = graph.settings) {
                RootApp(graph)
            }
        }
    }
}

private data class TopLevel(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)

@Composable
fun RootApp(graph: AppGraph) {
    val nav = rememberNavController()
    val topLevel = listOf(
        TopLevel(Routes.HOME, Icons.Filled.Home, R.string.nav_home),
        TopLevel(Routes.PROJECTS, Icons.Filled.FolderOpen, R.string.nav_projects),
        TopLevel(Routes.ARCHIVE, Icons.Filled.Archive, R.string.nav_archive),
        TopLevel(Routes.EDITOR, Icons.Filled.Edit, R.string.nav_editor),
        TopLevel(Routes.TOOLS, Icons.Filled.MoreHoriz, R.string.nav_more)
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in topLevel.map { it.route }) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(graph, nav) }
            composable(Routes.PROJECTS) { ProjectScreen(graph, nav) }
            composable(Routes.ARCHIVE) { ArchiveScreen(graph, nav) }
            composable(Routes.EDITOR) { EditorScreen(graph, nav) }
            composable(Routes.TOOLS) { ToolsScreen(graph, nav) }
            composable(Routes.VALIDATOR) { ValidatorScreen(graph, nav) }
            composable(Routes.IMAGE) { ImageScreen(graph, nav) }
            composable(Routes.AUDIO) { AudioScreen(graph, nav) }
            composable(Routes.WIZARD) { NewModWizardScreen(graph, nav) }
            composable(Routes.EXPORT) { ExportScreen(graph, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(graph, nav) }
        }
    }
}
