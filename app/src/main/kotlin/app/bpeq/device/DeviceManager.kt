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
import app.bpeq.core.device.DeviceIoException
import app.bpeq.core.device.HidChannel
import app.bpeq.core.device.MaxSession
import app.bpeq.core.device.SelfTest
import app.bpeq.core.device.SimulatedDongle
import kotlinx.coroutines.asCoroutineDispatcher
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

    @Volatile private var busy: String? = null
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
        if (_simulate.value) { _status.value = DeviceStatus.Simulated; return }
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
        _lastSelfTest.value = report
        return report
    }

    companion object {
        const val ACTION_PERMISSION = "app.bpeq.USB_PERMISSION"

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
