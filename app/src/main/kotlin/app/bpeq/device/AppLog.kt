package app.bpeq.device

import android.os.Build
import app.bpeq.BuildConfig
import app.bpeq.core.device.DeviceLog
import app.bpeq.core.device.MemoryLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-wide diagnostics log: everything USB-related lands here and is shown on the Diagnostics screen. */
class AppLog : DeviceLog {
    private val mem = MemoryLog(cap = 4000)
    private val _entries = MutableStateFlow<List<MemoryLog.Entry>>(emptyList())
    val entries: StateFlow<List<MemoryLog.Entry>> = _entries

    init {
        mem.addListener { _entries.value = mem.snapshot() }
    }

    override fun info(msg: String) = mem.info(msg)
    override fun warn(msg: String) = mem.warn(msg)
    override fun error(msg: String, t: Throwable?) = mem.error(msg, t)
    override fun tx(report: ByteArray) = mem.tx(report)
    override fun rx(report: ByteArray) = mem.rx(report)
    fun clear() = mem.clear()

    fun header(): String = buildString {
        appendLine("BPEQ ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Exported ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("----")
    }

    /** Plain-text export; [maxChars] keeps the newest entries when the log is huge. */
    fun export(maxChars: Int = Int.MAX_VALUE): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val lines = mem.snapshot().map { e -> "${fmt.format(Date(e.timeMs))} ${tag(e.level)} ${e.text}" }
        val body = StringBuilder()
        for (l in lines.asReversed()) {
            if (body.length + l.length + 1 > maxChars) { body.insert(0, "…(older entries trimmed)\n"); break }
            body.insert(0, l + "\n")
        }
        return header() + body
    }

    /**
     * Called from the uncaught-exception handler: the process is about to die and the in-memory log with it, so
     * the stack trace and the newest log entries are written to [file] synchronously, to be shown on next launch.
     */
    fun saveCrash(file: java.io.File, thread: Thread, e: Throwable) {
        runCatching {
            file.writeText(buildString {
                appendLine("BPEQ ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crashed at " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " on thread ${thread.name}")
                appendLine(e.stackTraceToString().lines().take(60).joinToString("\n"))
                appendLine("---- log before the crash ----")
                append(export(maxChars = 12_000).substringAfter("----\n"))
            })
        }
    }

    /** Moves a crash saved by [saveCrash] into this log. Returns true if there was one. */
    fun restoreCrash(file: java.io.File): Boolean {
        if (!file.exists()) return false
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        if (text.isNullOrBlank()) return false
        mem.error("PREVIOUS SESSION CRASHED\n$text", null)
        return true
    }

    companion object {
        fun tag(l: MemoryLog.Level) = when (l) {
            MemoryLog.Level.INFO -> "I"
            MemoryLog.Level.WARN -> "W"
            MemoryLog.Level.ERROR -> "E"
            MemoryLog.Level.TX -> ">>"
            MemoryLog.Level.RX -> "<<"
        }
    }
}
