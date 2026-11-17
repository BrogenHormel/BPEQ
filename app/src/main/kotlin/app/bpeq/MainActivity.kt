package app.bpeq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.bpeq.ui.BpeqTheme
import app.bpeq.ui.DeviceScreen
import app.bpeq.ui.DiagnosticsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    DEVICE("Device", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Info),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BpeqTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        Tab.DEVICE -> DeviceScreen(m, onOpenDiagnostics = { tab = Tab.DIAGNOSTICS })
                        Tab.DIAGNOSTICS -> DiagnosticsScreen(m)
                    }
                }
            }
        }
    }
}
