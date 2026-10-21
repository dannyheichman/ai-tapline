package app.handsfree.copilot.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.handsfree.copilot.AppGraph
import app.handsfree.copilot.ui.calibrate.CalibrateScreen
import app.handsfree.copilot.ui.session.SessionScreen
import app.handsfree.copilot.ui.voice.VoiceScreen

@Composable
fun RootScreen(graph: AppGraph) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "session",
                    onClick = { nav.navigate("session") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text("Session") },
                )
                NavigationBarItem(
                    selected = route == "voice",
                    onClick = { nav.navigate("voice") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                    label = { Text("Voice") },
                )
                NavigationBarItem(
                    selected = route == "calibrate",
                    onClick = { nav.navigate("calibrate") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    label = { Text("Calibrate") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "session",
            modifier = Modifier.padding(padding),
        ) {
            composable("session") { SessionScreen(graph) }
            composable("voice") { VoiceScreen(graph) }
            composable("calibrate") { CalibrateScreen(graph) }
        }
    }
}
