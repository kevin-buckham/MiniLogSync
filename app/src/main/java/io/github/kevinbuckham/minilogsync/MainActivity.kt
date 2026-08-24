package io.github.kevinbuckham.minilogsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MiniLogSync v1 - mount/unmount remote control for the rusEFI SD card.
 *
 * v1 scope: hand the card to the phone so Android's Files app can copy logs,
 * then give it back so the ECU resumes logging. File copying itself is
 * deliberately left to the OS (see docs/ANDROID_LOG_SYNC_SPEC.md).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var link: EcuLink
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var warnView: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** True while the card is handed to the phone - the ECU is NOT logging. */
    private var mountedToPhone = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                EcuLink.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log(if (granted) "USB permission granted" else "USB permission DENIED")
                    if (granted) connect()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("ECU attached")
                    connect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("ECU detached")
                    link.close()
                    if (mountedToPhone) {
                        // Cable pulled while mounted: the ECU keeps the PC mode until told
                        // otherwise, so remind loudly. Spec section 7.
                        warn("Cable pulled while mounted - reconnect and tap 'Return to ECU'")
                    }
                    refresh()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        link = EcuLink(this)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        warnView = findViewById(R.id.warning)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }

        findViewById<Button>(R.id.btnMount).setOnClickListener {
            send(EcuLink.CMD_MOUNT_PHONE) { ok ->
                if (ok) {
                    mountedToPhone = true
                    log("Card handed to phone. Open Files to copy logs.")
                }
            }
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            send(EcuLink.CMD_RESTORE_AUTO) { ok ->
                if (ok) {
                    mountedToPhone = false
                    log("Card returned to ECU - logging should resume.")
                }
            }
        }

        findViewById<Button>(R.id.btnRestoreEcu).setOnClickListener {
            send(EcuLink.CMD_RESTORE_ECU) { ok ->
                if (ok) {
                    mountedToPhone = false
                    log("Card explicitly assigned to ECU logging.")
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(EcuLink.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        log("MiniLogSync ready")
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        io.shutdown()
        link.close()
    }

    private fun connect() {
        val device = link.findDevice()
        if (device == null) {
            statusView.text = getString(R.string.status_no_device)
            log("No ECU found - check the cable and that the key is on")
            return
        }
        if (!link.hasPermission(device)) {
            log("Requesting USB permission...")
            link.requestPermission(device)
            return
        }
        io.execute {
            val msg = link.open(device)
            runOnUiThread {
                log(msg)
                refresh()
            }
        }
    }

    private fun send(command: String, onDone: (Boolean) -> Unit) {
        if (!link.isOpen) {
            log("Not connected - tap Connect first")
            return
        }
        log("-> $command")
        io.execute {
            val r = link.sendCommand(command)
            runOnUiThread {
                log(r.message + if (r.raw.isNotEmpty()) "   [${r.raw}]" else "")
                onDone(r.ok)
                refresh()
            }
        }
    }

    private fun refresh() {
        statusView.text = when {
            !link.isOpen -> getString(R.string.status_disconnected)
            mountedToPhone -> getString(R.string.status_mounted)
            else -> getString(R.string.status_connected)
        }
        warnView.text = if (mountedToPhone) getString(R.string.warn_not_logging) else ""
    }

    private fun warn(text: String) {
        warnView.text = text
        log(text)
    }

    private fun log(line: String) {
        logView.text = "${stamp.format(Date())}  $line\n${logView.text}"
    }
}
