package app.bpeq.device

import android.content.Context
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
    data object Simulated : DeviceStatus
    data class Busy(val what: String) : DeviceStatus
    data class Error(val message: String) : DeviceStatus
}

/**
 * Owns all device I/O. Every operation runs on one dedicated thread, so USB calls never overlap
 * and never block the UI.
 */
class DeviceManager(private val context: Context, val log: AppLog) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "bpeq-usb") }.asCoroutineDispatcher()

    val simulator = SimulatedDongle()

    private val _simulate = MutableStateFlow(false)
    val simulate: StateFlow<Boolean> = _simulate.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus>(DeviceStatus.NoDevice)
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _lastSelfTest = MutableStateFlow<SelfTest.Report?>(null)
    val lastSelfTest: StateFlow<SelfTest.Report?> = _lastSelfTest.asStateFlow()

    fun setSimulate(on: Boolean) {
        _simulate.value = on
        log.info(if (on) "simulated dongle ON" else "simulated dongle OFF")
        refreshStatus()
    }

    fun refreshStatus() {
        _status.value = if (_simulate.value) DeviceStatus.Simulated else DeviceStatus.NoDevice
    }

    private fun openChannel(): HidChannel =
        if (_simulate.value) simulator else throw IllegalStateException("No dongle connected")

    /** Runs [block] with an open session on the IO thread; status shows [what] meanwhile. */
    suspend fun <T> withSession(what: String, block: (MaxSession) -> T): T = withContext(io) {
        val before = _status.value
        _status.value = DeviceStatus.Busy(what)
        try {
            openChannel().use { ch ->
                log.info("session open: ${ch.description}")
                block(MaxSession(ch, log))
            }
        } catch (e: Exception) {
            log.error("$what failed: ${e.message}", e)
            throw e
        } finally {
            _status.value = before
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
}
