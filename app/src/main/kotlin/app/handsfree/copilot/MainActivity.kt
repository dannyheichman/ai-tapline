package app.handsfree.copilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.handsfree.copilot.ui.RootScreen
import app.handsfree.copilot.ui.theme.VoiceBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as VoiceBridgeApp).graph
        setContent {
            VoiceBridgeTheme {
                RootScreen(graph)
            }
        }
    }
}
