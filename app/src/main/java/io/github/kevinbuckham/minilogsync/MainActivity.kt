package io.github.kevinbuckham.minilogsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var history: SyncHistory
    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var warnView: TextView
    private lateinit var destView: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** True while the card is handed to the phone - the ECU is NOT logging. */
    private var mountedToPhone = false

    /** Non-null while a sync is running; the Sync button becomes Cancel. */
    private var runningJob: SyncJob? = null

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
        history = SyncHistory(this)
        prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        warnView = findViewById(R.id.warning)
        destView = findViewById(R.id.dest)

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

        findViewById<Button>(R.id.btnDest).setOnClickListener { pickDestination.launch(null) }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            val job = runningJob
            if (job != null) {
                job.cancel()
                log("Cancelling after the current chunk...")
            } else {
                startSync()
            }
        }

        findViewById<Button>(R.id.btnForget).setOnClickListener {
            history.forgetAll()
            log("Sync history cleared - the next sync re-copies everything")
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
        refresh()
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

    private val pickDestination =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val previous = prefs.getString("dest", null)
            prefs.edit().putString("dest", uri.toString()).apply()
            log("Destination set: ${prettyDest(uri)}")
            if (previous != null && previous != uri.toString() && history.count > 0) {
                // Sync history is global, not per-folder: files already copied
                // elsewhere will NOT be copied again into the new folder.
                log(
                    "Note: ${history.count} file(s) are already marked synced, so only NEW " +
                        "logs will land here. Tap 'Forget sync history' to backfill this folder."
                )
            }
            refresh()
        }

    /** Best-effort readable name for a SAF tree uri (provider + last path segment). */
    private fun prettyDest(uri: Uri): String {
        val leaf = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
        val provider = uri.authority
            ?.removePrefix("com.android.")
            ?.removeSuffix(".documents")
            ?.removeSuffix(".storage.documents")
            ?: "?"
        return if (leaf != null) "$leaf  ($provider)" else provider
    }

    private fun destinationUri(): Uri? =
        prefs.getString("dest", null)?.let(Uri::parse)

    private fun startSync() {
        if (!link.isOpen) { log("Not connected - tap Connect first"); return }
        val dest = destinationUri()
        if (dest == null) { log("Pick a destination folder first"); return }

        val job = SyncJob(this, link, history)
        runningJob = job
        mountedToPhone = true
        refresh()
        log("=== Sync started ===")
        io.execute {
            val ok = job.run(dest) { line -> runOnUiThread { log(line) } }
            runOnUiThread {
                runningJob = null
                mountedToPhone = false
                log(
                    when {
                        job.cancelled.get() -> "=== Sync cancelled (safe to unplug) ==="
                        ok -> "=== Sync complete ==="
                        else -> "=== Sync finished with problems ==="
                    }
                )
                refresh()
            }
        }
    }

    private fun refresh() {
        destView.text = destinationUri()?.let { "Saving to: ${prettyDest(it)}" }
            ?: getString(R.string.dest_unset)
        findViewById<Button>(R.id.btnSync).text =
            getString(if (runningJob != null) R.string.btn_cancel else R.string.btn_sync)
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
