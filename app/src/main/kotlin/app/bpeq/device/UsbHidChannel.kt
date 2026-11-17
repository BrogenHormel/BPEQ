package app.bpeq.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import app.bpeq.core.device.DeviceIoException
import app.bpeq.core.device.DeviceLog
import app.bpeq.core.device.HidChannel
import app.bpeq.core.device.MaxProtocol

/**
 * The Protocol Max's HID interface over Android's USB host API. Only the HID interface is claimed;
 * the audio interfaces stay with the system, so playback continues. Output reports go to the interrupt OUT
 * endpoint if there is one, else HID SET_REPORT; input comes from the interrupt IN endpoint.
 * Every call has a timeout. Blocking: use from the single device thread only.
 */
class UsbHidChannel private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?,
    private val log: DeviceLog,
) : HidChannel {

    override val description: String =
        "USB ${device.productName ?: "device"} (${"%04x:%04x".format(device.vendorId, device.productId)}), HID if ${intf.id}, " +
            "IN 0x%02x".format(epIn.address) + (epOut?.let { ", OUT 0x%02x".format(it.address) } ?: ", OUT via SET_REPORT")

    private val inBuf = ByteArray(maxOf(epIn.maxPacketSize, MaxProtocol.REPORT_SIZE))
    @Volatile private var closed = false

    private fun stillAttached(): Boolean = manager.deviceList.values.any { it.deviceName == device.deviceName }

    override fun send(report: ByteArray) {
        if (closed) throw DeviceIoException("channel closed")
        val n = if (epOut != null) {
            connection.bulkTransfer(epOut, report, report.size, WRITE_TIMEOUT_MS)
        } else {
            // SET_REPORT: bmRequestType 0x21, bRequest 0x09, wValue = (Output=2)<<8 | report ID, wIndex = interface.
            connection.controlTransfer(0x21, 0x09, (0x02 shl 8) or MaxProtocol.REPORT_ID, intf.id, report, report.size, WRITE_TIMEOUT_MS)
        }
        if (n != report.size) {
            if (!stillAttached()) throw DeviceIoException("dongle unplugged")
            throw DeviceIoException("output report failed ($n of ${report.size} bytes)")
        }
    }

    override fun receive(timeoutMs: Int): ByteArray? {
        if (closed) throw DeviceIoException("channel closed")
        val t0 = SystemClock.elapsedRealtime()
        val n = connection.bulkTransfer(epIn, inBuf, inBuf.size, timeoutMs.coerceAtLeast(1))
        if (n > 0) return inBuf.copyOf(n)
        // -1 is both "timeout" and "error"; an early -1 or a vanished device means it's gone.
        if (!stillAttached()) throw DeviceIoException("dongle unplugged")
        if (SystemClock.elapsedRealtime() - t0 < timeoutMs / 4 && timeoutMs >= 40) {
            log.warn("IN transfer failed early (n=$n after ${SystemClock.elapsedRealtime() - t0} ms)")
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
        log.info("USB closed (interface ${intf.id} released)")
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 1000

        fun isProtocolMax(d: UsbDevice) = d.vendorId == MaxProtocol.VENDOR_ID && d.productId == MaxProtocol.PRODUCT_ID

        private fun epType(t: Int) = when (t) {
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            else -> "control"
        }

        /** Logs everything Android tells us about the device (no I/O). */
        fun describe(d: UsbDevice, log: DeviceLog) {
            log.info("device ${d.deviceName}: VID 0x%04x PID 0x%04x class ${d.deviceClass}, product \"${d.productName}\", maker \"${d.manufacturerName}\", version ${d.version}, configs ${d.configurationCount}, interfaces ${d.interfaceCount}".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                log.info("  interface ${f.id} alt ${f.alternateSetting}: class ${f.interfaceClass} sub ${f.interfaceSubclass} proto ${f.interfaceProtocol} \"${f.name ?: ""}\", ${f.endpointCount} endpoint(s)")
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    log.info("    ep 0x%02x %s %s, maxPacket %d, interval %d".format(
                        ep.address, if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", epType(ep.type), ep.maxPacketSize, ep.interval))
                }
            }
        }

        /**
         * Opens the single HID interface with a 64-byte interrupt IN endpoint. Fails closed: if it's ambiguous,
         * nothing is claimed. Also reads (read-only) the raw and HID report descriptors into the log.
         */
        fun open(manager: UsbManager, d: UsbDevice, log: DeviceLog): UsbHidChannel {
            if (!manager.hasPermission(d)) throw DeviceIoException("no USB permission")
            val hids = (0 until d.interfaceCount).map { d.getInterface(it) }.filter {
                it.interfaceClass == UsbConstants.USB_CLASS_HID && it.alternateSetting == 0
            }
            fun interruptEps(f: UsbInterface) = (0 until f.endpointCount).map { f.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
            val candidates = hids.filter { f ->
                interruptEps(f).any { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize >= MaxProtocol.REPORT_SIZE }
            }
            val intf = candidates.singleOrNull()
                ?: throw DeviceIoException("expected exactly one HID interface with a 64-byte interrupt IN endpoint, found ${candidates.size} (of ${hids.size} HID)")
            val eps = interruptEps(intf)
            val epIn = eps.first { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize >= MaxProtocol.REPORT_SIZE }
            val epOut = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.maxPacketSize >= MaxProtocol.REPORT_SIZE }
            val conn = manager.openDevice(d) ?: throw DeviceIoException("openDevice failed")
            try {
                if (!conn.claimInterface(intf, true)) throw DeviceIoException("claimInterface(${intf.id}) failed")
                logDescriptors(conn, intf, log)
            } catch (e: Exception) {
                runCatching { conn.close() }
                throw e
            }
            return UsbHidChannel(manager, d, conn, intf, epIn, epOut, log).also { log.info("USB open: ${it.description}") }
        }

        private var descriptorsLogged = mutableSetOf<String>()

        private fun logDescriptors(conn: UsbDeviceConnection, intf: UsbInterface, log: DeviceLog) {
            val raw = conn.rawDescriptors ?: return
            val key = DeviceLog.hex(raw, false)
            if (!descriptorsLogged.add(key)) return // once per app run
            log.info("raw descriptors (${raw.size} B): ${DeviceLog.hex(raw, false)}")
            // Find this interface's HID descriptor (type 0x21) -> report descriptor length.
            var i = 0; var inIntf = false; var len = 0
            while (i + 1 < raw.size) {
                val n = raw[i].toInt() and 0xFF
                if (n < 2 || i + n > raw.size) break
                when (raw[i + 1].toInt() and 0xFF) {
                    0x04 -> inIntf = n >= 3 && (raw[i + 2].toInt() and 0xFF) == intf.id
                    0x21 -> if (inIntf && n >= 9) len = (raw[i + 7].toInt() and 0xFF) or ((raw[i + 8].toInt() and 0xFF) shl 8)
                }
                i += n
            }
            if (len in 1..4096) {
                val buf = ByteArray(len)
                // GET_DESCRIPTOR (standard, interface recipient), type 0x22 = HID report descriptor. Read-only.
                val got = conn.controlTransfer(0x81, 0x06, 0x2200, intf.id, buf, len, 1000)
                if (got > 0) log.info("HID report descriptor ($got B): ${DeviceLog.hex(buf.copyOf(got), false)}")
                else log.warn("HID report descriptor read failed ($got)")
            }
        }
    }
}
