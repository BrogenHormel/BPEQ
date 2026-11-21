package app.bpeq

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.bpeq.data.Actions
import app.bpeq.ui.BpeqTheme
import app.bpeq.ui.ThemePrefs
import app.bpeq.ui.isDark
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import app.bpeq.ui.DeviceScreen
import app.bpeq.ui.DiagnosticsScreen
import app.bpeq.ui.EditorScreen
import app.bpeq.ui.LibraryScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String, val icon: ImageVector) {
    EDITOR("EQ", Icons.Filled.Edit),
    LIBRARY("Library", Icons.Filled.List),
    TUNE("Tune", Icons.Filled.Star),
    DEVICE("Device", Icons.Filled.Settings),
    DIAGNOSTICS("Log", Icons.Filled.Info),
}

class MainActivity : ComponentActivity() {
    /** Tab requested from outside Compose (e.g. after an import intent). */
    private val requestedTab = MutableStateFlow<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val themeMode by ThemePrefs.mode.collectAsState()
            val dark = themeMode.isDark()
            // Status/navigation bar icons follow the app's theme, not just the phone's setting.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            BpeqTheme(themeMode) {
                var tab by rememberSaveable { mutableStateOf(Tab.EDITOR) }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { requestedTab.collect { t -> if (t != null) { tab = t; requestedTab.value = null } } }
                LaunchedEffect(Unit) { BpeqApp.instance.tabRequest.collect { t -> if (t != null) { tab = t; BpeqApp.instance.tabRequest.value = null } } }
                LaunchedEffect(Unit) {
                    BpeqApp.instance.messages.flow.collect { m ->
                        val r = snackbar.showSnackbar(m.text, m.actionLabel, withDismissAction = m.actionLabel == null,
                            duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
                    }
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        Tab.EDITOR -> EditorScreen(m, onOpenLibrary = { tab = Tab.LIBRARY })
                        Tab.LIBRARY -> LibraryScreen(m, onOpen = { tab = Tab.EDITOR })
                        Tab.TUNE -> app.bpeq.ui.TuneScreen(m, onOpenEditor = { tab = Tab.EDITOR })
                        Tab.DEVICE -> DeviceScreen(m, onOpenDiagnostics = { tab = Tab.DIAGNOSTICS })
                        Tab.DIAGNOSTICS -> DiagnosticsScreen(m)
                    }
                }
            }
        }
    }

    /** Handles an incoming intent once, even if the activity is recreated with the same intent. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(HANDLED, false)) return
        intent.putExtra(HANDLED, true)
        BpeqApp.instance.devices.onAttachIntent(intent)
        if (Actions.handleIntent(this, intent)) requestedTab.value = Tab.EDITOR
    }

    companion object { private const val HANDLED = "app.bpeq.handled" }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        BpeqApp.instance.devices.refreshStatus()
    }
}
