package app.bpeq.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.bpeq.core.device.DeviceBackup
import app.bpeq.core.device.DeviceIoException
import app.bpeq.core.device.DeviceState
import app.bpeq.core.device.WritePlan
import app.bpeq.core.device.WriteResult
import app.bpeq.core.device.HidChannel
import app.bpeq.core.device.MaxSession
import app.bpeq.core.device.SelfTest
import app.bpeq.core.device.SimulatedDongle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** What the UI shows about the dongle. */
sealed interface DeviceStatus {
    data object NoDevice : DeviceStatus
    data object NeedsPermission : DeviceStatus
    data object PermissionDenied : DeviceStatus
    data class Ready(val name: String) : DeviceStatus
    data object Simulated : DeviceStatus
    data class Busy(val what: String) : DeviceStatus
    data class Error(val message: String) : DeviceStatus

    val canTalk: Boolean get() = this is Ready || this is Simulated
}

/** The result of a write, for the UI. */
data class WriteOutcome(val label: String, val ok: Boolean, val lines: List<String>, val timeMs: Long = System.currentTimeMillis())

/**
 * Owns all device I/O. Every operation runs on one dedicated thread, so USB calls never overlap
 * and never block the UI. Tracks attach/detach and USB permission.
 */
class DeviceManager(private val context: Context, val log: AppLog) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "bpeq-usb") }.asCoroutineDispatcher()
    private val usb = context.getSystemService(UsbManager::class.java)

    val simulator = SimulatedDongle()

    private val _simulate = MutableStateFlow(false)
    val simulate: StateFlow<Boolean> = _simulate.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus>(DeviceStatus.NoDevice)
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _lastSelfTest = MutableStateFlow<SelfTest.Report?>(null)
    val lastSelfTest: StateFlow<SelfTest.Report?> = _lastSelfTest.asStateFlow()

    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    private val _lastState = MutableStateFlow<DeviceState?>(null)
    /** The last state read from the device in this app run. */
    val lastState: StateFlow<DeviceState?> = _lastState.asStateFlow()

    private val _originalBackup = MutableStateFlow(prefs.getString(KEY_ORIGINAL, null)?.let { runCatching { DeviceBackup.fromJson(it) }.getOrNull() })
    /** The very first state ever read from the real dongle (never overwritten): a safe restore point. */
    val originalBackup: StateFlow<DeviceBackup?> = _originalBackup.asStateFlow()

    private val _firstWriteDone = MutableStateFlow(prefs.getBoolean(KEY_FIRST_WRITE, false))
    /** False until one write to the real dongle succeeded; until then every write shows a byte preview first. */
    val firstWriteDone: StateFlow<Boolean> = _firstWriteDone.asStateFlow()

    private val _lastWrite = MutableStateFlow<WriteOutcome?>(null)
    val lastWrite: StateFlow<WriteOutcome?> = _lastWrite.asStateFlow()

    @Volatile private var busy: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoReadFor: String? = null
    @Volatile private var denied = false
    private var knownDevice: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.usbDevice()
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (dev != null && UsbHidChannel.isProtocolMax(dev)) {
                    log.info("USB attached: ${dev.deviceName}")
                    denied = false
                    refreshStatus()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (dev != null && UsbHidChannel.isProtocolMax(dev)) {
                    log.info("USB detached: ${dev.deviceName}")
                    refreshStatus()
                }
                ACTION_PERMISSION -> {
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log.info("USB permission ${if (granted) "granted" else "denied"}")
                    denied = !granted
                    refreshStatus()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshStatus()
    }

    fun setSimulate(on: Boolean) {
        _simulate.value = on
        log.info(if (on) "simulated dongle ON" else "simulated dongle OFF")
        refreshStatus()
    }

    /** The attached Protocol Max, if any. */
    fun findDevice(): UsbDevice? = runCatching { usb.deviceList.values.firstOrNull(UsbHidChannel::isProtocolMax) }.getOrNull()

    /** Recomputes [status] from the current USB state (cheap; no I/O). */
    fun refreshStatus() {
        busy?.let { _status.value = DeviceStatus.Busy(it); return }
        if (_simulate.value) {
            _status.value = DeviceStatus.Simulated
            if (autoReadFor != SIM) { autoReadFor = SIM; scope.launch { read() } }
            return
        }
        if (autoReadFor == SIM) { autoReadFor = null; _lastState.value = null }
        val d = findDevice()
        if (d?.deviceName != knownDevice) {
            knownDevice = d?.deviceName
            if (d != null) UsbHidChannel.describe(d, log)
        }
        _status.value = when {
            d == null -> DeviceStatus.NoDevice
            usb.hasPermission(d) -> DeviceStatus.Ready(d.productName ?: "Protocol Max")
            denied -> DeviceStatus.PermissionDenied
            else -> DeviceStatus.NeedsPermission
        }
        if (d == null) { autoReadFor = null; _lastState.value = null }
        // Read-only read once per connection, so the app knows what's on the dongle.
        if (d != null && usb.hasPermission(d) && autoReadFor != d.deviceName) {
            autoReadFor = d.deviceName
            scope.launch { read() }
        }
    }

    /** Called when the activity was launched by plugging in the dongle (permission is then implicit). */
    fun onAttachIntent(intent: Intent) {
        val d = intent.usbDevice() ?: return
        if (UsbHidChannel.isProtocolMax(d)) {
            log.info("opened by USB attach: ${d.deviceName}, permission=${usb.hasPermission(d)}")
            denied = false
            refreshStatus()
        }
    }

    fun requestPermission() {
        val d = findDevice() ?: return
        if (usb.hasPermission(d)) { refreshStatus(); return }
        log.info("requesting USB permission for ${d.deviceName}")
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun openChannel(): HidChannel {
        if (_simulate.value) return simulator
        val d = findDevice() ?: throw DeviceIoException("No Protocol Max connected")
        if (!usb.hasPermission(d)) throw DeviceIoException("USB permission not granted")
        return UsbHidChannel.open(usb, d, log)
    }

    /** Runs [block] with an open session on the IO thread; status shows [what] meanwhile. */
    suspend fun <T> withSession(what: String, block: (MaxSession) -> T): T = withContext(io) {
        busy = what
        refreshStatus()
        try {
            openChannel().use { ch ->
                log.info("session open: ${ch.description}")
                block(MaxSession(ch, log))
            }
        } catch (e: Exception) {
            log.error("$what failed: ${e.message}", e)
            throw e
        } finally {
            busy = null
            refreshStatus()
        }
    }

    suspend fun runSelfTest(): SelfTest.Report {
        val report = try {
            withSession("Self-test") { SelfTest.run(it, log) }
        } catch (e: Exception) {
            SelfTest.Report(listOf(SelfTest.Check(SelfTest.Status.FAIL, e.message ?: e.javaClass.simpleName)), null)
        }
        report.state?.let(::remember)
        _lastSelfTest.value = report
        return report
    }

    /** Remembers [s] as the last read state, and as the original backup if this is the first real read. */
    private fun remember(s: DeviceState) {
        _lastState.value = s
        if (!_simulate.value && _originalBackup.value == null) {
            val b = DeviceBackup.of(s)
            prefs.edit().putString(KEY_ORIGINAL, b.toJson()).apply()
            _originalBackup.value = b
            log.info("saved original device state as a restore point")
        }
    }

    /** Read-only: reads everything from the device. */
    suspend fun read(): Result<DeviceState> = runCatching { withSession("Reading") { it.readState() } }.onSuccess(::remember)

    /**
     * Writes [plan]: fresh read first (slot byte, backup), then write -> verify -> commit -> verify.
     * [expectedSlot] is the slot shown in the preview; if the device now reports another, nothing is written.
     */
    suspend fun write(plan: WritePlan, expectedSlot: Int?, label: String): WriteOutcome {
        log.info("=== write \"$label\" ===")
        val outcome = try {
            withSession("Writing") { s ->
                val before = s.readState()
                remember(before)
                if (expectedSlot != null && before.slot != expectedSlot) {
                    throw DeviceIoException("slot byte changed since the preview (0x%02x -> 0x%02x); nothing written".format(expectedSlot, before.slot))
                }
                when (val r = s.write(plan, before.slot, commit = true)) {
                    is WriteResult.Committed -> { remember(r.state); WriteOutcome(label, true, listOf(
                        "Written, verified (all ${plan.registers.size} bands, slot, preamp), saved to the dongle, verified again.")) }
                    is WriteResult.Mismatch -> { remember(r.state); WriteOutcome(label, false,
                        listOf("Read-back did not match, so nothing was saved to the dongle's memory:") + r.differences) }
                    is WriteResult.Applied -> WriteOutcome(label, true, listOf("Applied temporarily (not saved)."))
                }
            }
        } catch (e: Exception) {
            WriteOutcome(label, false, listOf("Failed: ${e.message ?: e.javaClass.simpleName}"))
        }
        if (outcome.ok && !_simulate.value && !_firstWriteDone.value) {
            prefs.edit().putBoolean(KEY_FIRST_WRITE, true).apply()
            _firstWriteDone.value = true
        }
        log.info("=== write \"$label\": ${if (outcome.ok) "OK" else "FAILED"} ===")
        _lastWrite.value = outcome
        return outcome
    }

    companion object {
        const val ACTION_PERMISSION = "app.bpeq.USB_PERMISSION"
        private const val SIM = "simulator"
        private const val KEY_ORIGINAL = "original_backup"
        private const val KEY_FIRST_WRITE = "first_write_done"

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
