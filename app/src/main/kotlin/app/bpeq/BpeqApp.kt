package app.bpeq

import android.app.Application
import app.bpeq.data.Editor
import app.bpeq.data.Library
import app.bpeq.data.Messages
import app.bpeq.device.AppLog
import app.bpeq.device.DeviceManager

class BpeqApp : Application() {
    lateinit var log: AppLog; private set
    lateinit var devices: DeviceManager; private set
    lateinit var library: Library; private set
    lateinit var editor: Editor; private set
    val messages = Messages()

    override fun onCreate() {
        super.onCreate()
        instance = this
        log = AppLog()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log.error("UNCAUGHT on ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
        log.info("app start")
        devices = DeviceManager(this, log)
        library = Library(this)
        editor = Editor(library, devices, messages)
    }

    companion object {
        lateinit var instance: BpeqApp; private set
    }
}
