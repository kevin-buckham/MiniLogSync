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
    private var lastDevice: UsbDevice? = null

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
        lastDevice = device

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
     * Release the USB device so the mass-storage layer can open it.
     *
     * Android will not hand a second UsbDeviceConnection to libaums while we
     * hold one for the CDC interface - that shows up as a null-message NPE
     * inside libaums' init(). The SD mode already requested on the ECU persists
     * across this, so dropping the serial link here is safe.
     */
    fun releaseForStorage() = close()

    /** Re-acquire the serial link after the storage phase, so we can restore logging. */
    fun reopen(): String {
        val device = lastDevice ?: findDevice() ?: return "No ECU found to reconnect"
        for (attempt in 1..5) {
            val msg = open(device)
            if (isOpen) return msg
            Thread.sleep(400L * attempt)
        }
        return "Could not reopen the serial link"
    }

    /**
     * Send one console command and wait for a CRC-VERIFIED reply, retrying on
     * failure. The link is flaky (this ECU drops ~1 in 3 mass-storage commands)
     * and the firmware also writes asynchronous console text to the same stream,
     * so: drain stale bytes first, read the WHOLE declared frame, and verify the
     * CRC before believing anything.
     */
    fun sendCommand(command: String, attempts: Int = 3): Result {
        var last = Result(false, "Not connected", "")
        for (attempt in 1..attempts) {
            last = sendOnce(command)
            if (last.ok) return if (attempt == 1) last
                else last.copy(message = last.message + " (attempt $attempt)")
            if (attempt < attempts) Thread.sleep(300L * attempt)
        }
        return last.copy(message = last.message + " after $attempts attempts")
    }

    private fun sendOnce(command: String): Result {
        val p = port ?: return Result(false, "Not connected", "")

        return try {
            // Discard anything already sitting in the buffer (async console
            // output, or the tail of a previous reply) so it cannot be misread
            // as the answer to THIS command.
            drain(p)

            p.write(TsPacket.execute(command), WRITE_TIMEOUT_MS)

            val buf = ByteArray(256)
            val chunk = ByteArray(256)
            var total = 0
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                val want = TsPacket.declaredFrameSize(buf, total)
                if (want in 1..total) break          // whole frame present
                val n = p.read(chunk, 250)
                if (n > 0) {
                    val take = minOf(n, buf.size - total)
                    chunk.copyInto(buf, total, 0, take)
                    total += take
                }
            }

            val raw = TsPacket.hex(buf, total)
            when (val reply = TsPacket.parseReply(buf, total)) {
                null -> Result(false, "No/short reply to '$command'", raw)
                else -> when {
                    !reply.crcOk ->
                        Result(false, "BAD CRC in reply to '$command'", raw)
                    reply.code == TsPacket.RESPONSE_OK ->
                        Result(true, "OK: $command", raw)
                    else ->
                        Result(false, "ECU replied 0x%02X to '%s'".format(reply.code, command), raw)
                }
            }
        } catch (e: Exception) {
            Result(false, "Send failed: ${e.message}", "")
        }
    }

    private fun drain(p: UsbSerialPort) {
        val scratch = ByteArray(256)
        repeat(4) {
            val n = try { p.read(scratch, 50) } catch (_: Exception) { 0 }
            if (n <= 0) return
        }
    }

    data class Result(val ok: Boolean, val message: String, val raw: String)
}
