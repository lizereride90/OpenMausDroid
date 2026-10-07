package com.openmausdroid.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openmausdroid.app.R
import com.openmausdroid.app.core.Setup

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("chat", R.string.tab_chat, Icons.Outlined.ChatBubbleOutline),
    Tab("bots", R.string.tab_bots, Icons.Outlined.SmartToy),
    Tab("terminal", R.string.tab_terminal, Icons.Outlined.Terminal),
    Tab("desktop", R.string.tab_desktop, Icons.Outlined.DesktopWindows),
    Tab("settings", R.string.tab_settings, Icons.Outlined.Settings),
)

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val start = if (Setup.rootfsReady) "chat" else "setup"

    Scaffold(
        bottomBar = {
            if (route != null && route != "setup") {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding),
        ) {
            composable("setup") {
                SetupScreen(
                    onOpenChat = {
                        nav.navigate("chat") {
                            popUpTo("setup") { inclusive = true }
                        }
                    },
                )
            }
            composable("chat") { ChatScreen(onOpenSetup = { nav.navigate("setup") }) }
            composable("bots") { BotsScreen(onOpenSetup = { nav.navigate("setup") }) }
            composable("terminal") { TerminalScreen(onOpenSetup = { nav.navigate("setup") }) }
            composable("desktop") { DesktopScreen(onOpenSetup = { nav.navigate("setup") }) }
            composable("settings") { SettingsScreen(onOpenSetup = { nav.navigate("setup") }) }
        }
    }
}
