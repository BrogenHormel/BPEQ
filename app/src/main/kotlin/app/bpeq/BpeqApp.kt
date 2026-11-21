package app.bpeq

import android.app.Application
import app.bpeq.data.Editor
import app.bpeq.data.Library
import app.bpeq.data.Messages
import app.bpeq.data.TuneSession
import app.bpeq.device.AppLog
import app.bpeq.device.DeviceManager

class BpeqApp : Application() {
    lateinit var log: AppLog; private set
    lateinit var devices: DeviceManager; private set
    lateinit var library: Library; private set
    lateinit var editor: Editor; private set
    lateinit var tune: TuneSession; private set
    val messages = Messages()
    /** Tab to switch to, requested from outside the UI (e.g. "Open Log" after a crash). */
    val tabRequest = kotlinx.coroutines.flow.MutableStateFlow<Tab?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        log = AppLog()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val crashed = log.restoreCrash(crashFile)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log.error("UNCAUGHT on ${t.name}", e) }
            log.saveCrash(crashFile, t, e)
            previous?.uncaughtException(t, e)
        }
        log.info("app start")
        if (crashed) messages.show("BPEQ crashed last time. The details are in the Log.", "Open Log") { tabRequest.value = Tab.DIAGNOSTICS }
        devices = DeviceManager(this, log)
        library = Library(this)
        editor = Editor(library, devices, messages)
        tune = TuneSession(library, messages)
        devices.onConnectedRead = ::offerApplyOnPlugIn
    }

    /** Feature 10: on plug-in, one tap to apply the last profile (or confirm it is already there). */
    private fun offerApplyOnPlugIn(state: app.bpeq.core.device.DeviceState) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("plugin_prompt", true)) return
        val match = library.profiles.value.firstOrNull { app.bpeq.core.device.Planner.matchesDevice(it.saved, state) }
        if (match != null) { messages.show("Protocol Max connected \u00b7 \u201c${match.name}\u201d is on it"); return }
        val last = library.onDevice.value?.first?.let(library::get) ?: library.current() ?: return
        messages.show("Protocol Max connected. Apply \u201c${last.name}\u201d?", "Apply") { app.bpeq.data.Applier.applyInBackground(last.id) }
    }

    companion object {
        lateinit var instance: BpeqApp; private set
    }
}
