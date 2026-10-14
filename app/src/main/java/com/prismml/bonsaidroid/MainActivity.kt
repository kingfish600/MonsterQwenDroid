package com.prismml.bonsaidroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.prismml.bonsaidroid.ui.AppViewModel
import com.prismml.bonsaidroid.ui.ChatScreen
import com.prismml.bonsaidroid.ui.HarnessScreen
import com.prismml.bonsaidroid.ui.ModelsScreen
import com.prismml.bonsaidroid.ui.SettingsScreen
import com.prismml.bonsaidroid.ui.theme.BonsaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BonsaiTheme { Root() }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val TABS = listOf(
    Tab("chat", "Chat") { Icon(Icons.Filled.ChatBubble, null) },
    Tab("models", "Models") { Icon(Icons.Filled.Memory, null) },
    Tab("harness", "Harness") { Icon(Icons.Filled.Science, null) },
    Tab("settings", "Settings") { Icon(Icons.Filled.Settings, null) },
)

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigate(tab.route) { launchSingleTop = true } },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "chat",
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen(vm) }
            composable("models") { ModelsScreen(vm) }
            composable("harness") { HarnessScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
