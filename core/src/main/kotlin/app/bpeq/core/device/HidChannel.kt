package app.bpeq.core.device

import java.io.Closeable
import java.io.IOException

/** A raw HID report pipe. Implemented by the Android USB transport and by [SimulatedDongle]. */
interface HidChannel : Closeable {
    /** Sends one full 64-byte output report (report ID at [0]). Throws [IOException] on failure/timeout. */
    fun send(report: ByteArray)

    /** One input report (report ID at [0]), or null after [timeoutMs]. Throws [IOException] if the device is gone. */
    fun receive(timeoutMs: Int): ByteArray?

    /** Human-readable description for the log. */
    val description: String
}

/** The device went away (unplugged) or stopped answering. */
class DeviceIoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Diagnostics sink. Implementations must be thread-safe. */
interface DeviceLog {
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String, t: Throwable? = null)
    fun tx(report: ByteArray)
    fun rx(report: ByteArray)

    companion object {
        val NONE = object : DeviceLog {
            override fun info(msg: String) {}
            override fun warn(msg: String) {}
            override fun error(msg: String, t: Throwable?) {}
            override fun tx(report: ByteArray) {}
            override fun rx(report: ByteArray) {}
        }

        /** Hex dump trimmed of trailing zeros (keeps logs readable), with the total length. */
        fun hex(b: ByteArray, trimZeros: Boolean = true): String {
            var end = b.size
            if (trimZeros) while (end > 1 && b[end - 1].toInt() == 0) end--
            val s = (0 until end).joinToString(" ") { "%02x".format(b[it].toInt() and 0xFF) }
            return if (end < b.size) "$s …(${b.size}B)" else s
        }
    }
}

/** Thread-safe in-memory log with a size cap; used by tests and wrapped by the app. */
class MemoryLog(private val cap: Int = 5000, private val clock: () -> Long = System::currentTimeMillis) : DeviceLog {
    enum class Level { INFO, WARN, ERROR, TX, RX }
    data class Entry(val timeMs: Long, val level: Level, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized fun snapshot(): List<Entry> = entries.toList()
    @Synchronized fun clear() { entries.clear(); notifyListeners() }
    @Synchronized fun addListener(l: () -> Unit) { listeners += l }

    private fun add(level: Level, text: String) {
        synchronized(this) {
            entries.addLast(Entry(clock(), level, text))
            while (entries.size > cap) entries.removeFirst()
        }
        notifyListeners()
    }

    private fun notifyListeners() { val ls = synchronized(this) { listeners.toList() }; ls.forEach { it() } }

    override fun info(msg: String) = add(Level.INFO, msg)
    override fun warn(msg: String) = add(Level.WARN, msg)
    override fun error(msg: String, t: Throwable?) =
        add(Level.ERROR, if (t == null) msg else "$msg\n${t.stackTraceToString().lines().take(25).joinToString("\n")}")
    override fun tx(report: ByteArray) = add(Level.TX, DeviceLog.hex(report))
    override fun rx(report: ByteArray) = add(Level.RX, DeviceLog.hex(report))
}
