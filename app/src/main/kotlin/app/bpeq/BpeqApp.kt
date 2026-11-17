package app.bpeq

import android.app.Application
import app.bpeq.device.AppLog
import app.bpeq.device.DeviceManager

class BpeqApp : Application() {
    lateinit var log: AppLog; private set
    lateinit var devices: DeviceManager; private set

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
    }

    companion object {
        lateinit var instance: BpeqApp; private set
    }
}
