package io.github.kevinbuckham.minilogsync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * USB CDC link to the rusEFI ECU, and the only three commands this app is
 * allowed to send (see docs/ANDROID_LOG_SYNC_SPEC.md, "Hard safety rules").
 *
 * NOTE: `sdmode format` exists in the firmware and WIPES THE CARD.
 * It must never appear in this codebase.
 */
class EcuLink(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "io.github.kevinbuckham.minilogsync.USB_PERMISSION"

        /** Hand the SD card to the host as USB mass storage. ECU stops logging. */
        const val CMD_MOUNT_PHONE = "sdmode pc"

        /** Return to the ECU's configured behaviour (with our config: logging). */
        const val CMD_RESTORE_AUTO = "sdmode auto"

        /** Explicitly give the card back to the ECU for logging. */
        const val CMD_RESTORE_ECU = "sdmode ecu"

        private const val BAUD = 115200
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 2000
    }

    private val usbManager get() =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null

    val isOpen: Boolean get() = port != null

    /**
     * Find the ECU. Tries the library's prober first, then falls back to any
     * device exposing a CDC interface - so we do not depend on rusEFI's VID/PID
     * being present in the prober's table.
     */
    fun findDevice(): UsbDevice? {
        UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull()
            ?.let { return it.device }

        return usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { i ->
                when (dev.getInterface(i).interfaceClass) {
                    UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA -> true
                    else -> false
                }
            }
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    /** Opens the CDC port. Returns a human-readable description of what happened. */
    fun open(device: UsbDevice): String {
        close()

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: CdcAcmSerialDriver(device)

        val connection = usbManager.openDevice(device)
            ?: return "Could not open device (permission denied?)"

        val p = driver.ports.firstOrNull()
            ?: return "Device exposes no serial port"

        return try {
            p.open(connection)
            // rusEFI's USB CDC ignores line coding, but the driver wants it set.
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = true
            p.rts = true
            port = p
            "Connected: ${device.productName ?: "USB device"} " +
                "(VID %04X PID %04X)".format(device.vendorId, device.productId)
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            "Open failed: ${e.message}"
        }
    }

    fun close() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    /**
     * Send one console command and wait for the ECU's reply.
     * Returns a Result describing the outcome for the UI log.
     */
    fun sendCommand(command: String): Result {
        val p = port ?: return Result(false, "Not connected", "")

        return try {
            val packet = TsPacket.execute(command)
            p.write(packet, WRITE_TIMEOUT_MS)

            // usb-serial-for-android's read() is read(dest, timeoutMs) - no offset/length
            // overload - so accumulate through a scratch chunk.
            val buf = ByteArray(64)
            val chunk = ByteArray(64)
            var total = 0
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (total < TsPacket.MIN_REPLY && System.currentTimeMillis() < deadline) {
                val n = p.read(chunk, 250)
                if (n > 0) {
                    val take = minOf(n, buf.size - total)
                    chunk.copyInto(buf, total, 0, take)
                    total += take
                }
            }

            val raw = TsPacket.hex(buf, total)
            when (val code = TsPacket.responseCode(buf, total)) {
                null -> Result(false, "No/short reply to '$command'", raw)
                TsPacket.RESPONSE_OK -> Result(true, "OK: $command", raw)
                else -> Result(false, "ECU replied 0x%02X to '%s'".format(code, command), raw)
            }
        } catch (e: Exception) {
            Result(false, "Send failed: ${e.message}", "")
        }
    }

    data class Result(val ok: Boolean, val message: String, val raw: String)
}
