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
    private lateinit var summaryView: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** True while the card is handed to the phone - the ECU is NOT logging. */
    private var mountedToPhone = false

    /** Non-null while a sync is running. */
    private var runningJob: SyncJob? = null

    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var dlgFile: TextView? = null
    private var dlgName: TextView? = null
    private var dlgDetail: TextView? = null
    private var dlgBar: android.widget.ProgressBar? = null
    private var dlgUnplug: TextView? = null
    private var dlgWarn: TextView? = null

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
        summaryView = findViewById(R.id.summary)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }

        findViewById<Button>(R.id.btnMount).setOnClickListener {
            send(EcuLink.CMD_MOUNT_PHONE) { ok ->
                if (ok) {
                    mountedToPhone = true
                    setMountedFlag(true)
                    log("Card handed to phone. Open Files to copy logs.")
                }
            }
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            send(EcuLink.CMD_RESTORE_AUTO) { ok ->
                if (ok) {
                    mountedToPhone = false
                    setMountedFlag(false)
                    log("Card returned to ECU - logging resumed.")
                }
            }
        }

        findViewById<Button>(R.id.btnRestoreEcu).setOnClickListener {
            send(EcuLink.CMD_RESTORE_ECU) { ok ->
                if (ok) {
                    mountedToPhone = false
                    setMountedFlag(false)
                    log("Card explicitly assigned to ECU logging.")
                }
            }
        }

        findViewById<Button>(R.id.btnDest).setOnClickListener { pickDestination.launch(null) }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            if (runningJob == null) startSync()
        }

        selectTab(sync = true)
        findViewById<Button>(R.id.tabSync).setOnClickListener { selectTab(sync = true) }
        findViewById<Button>(R.id.tabLog).setOnClickListener { selectTab(sync = false) }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("MiniLogSync log", logView.text)
            )
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
        }

        val advanced = findViewById<android.widget.LinearLayout>(R.id.advanced)
        findViewById<Button>(R.id.btnAdvanced).setOnClickListener { b ->
            val show = advanced.visibility != android.view.View.VISIBLE
            advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            (b as Button).text =
                getString(if (show) R.string.btn_advanced_hide else R.string.btn_advanced_show)
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

        log("MiniLogSync ${appVersion()} ready")
        refresh()
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
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
                if (link.isOpen) healIfLeftMounted()
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

    /**
     * Human-readable destination. Cloud providers hand out opaque document ids
     * (Drive's look like "acc=1;doc=encoded=6lBSW..."), so ask the provider for
     * the folder's display name instead of parsing the uri.
     */
    private fun prettyDest(uri: Uri): String {
        val folder = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name
        }.getOrNull()

        val provider = when (uri.authority) {
            "com.google.android.apps.docs.storage" -> "Google Drive"
            "com.android.externalstorage.documents" -> "Device storage"
            "com.android.providers.downloads.documents" -> "Downloads"
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive"
            else -> uri.authority
                ?.removePrefix("com.android.")
                ?.removeSuffix(".documents")
                ?: "storage"
        }
        return if (!folder.isNullOrBlank()) "$folder  ($provider)" else provider
    }

    /** Simple two-page switcher: the diagnostic log gets its own space. */
    private fun selectTab(sync: Boolean) {
        findViewById<android.view.View>(R.id.pageSync).visibility =
            if (sync) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.pageLog).visibility =
            if (sync) android.view.View.GONE else android.view.View.VISIBLE

        val on = androidx.core.content.ContextCompat.getColor(this, R.color.action_primary)
        val off = androidx.core.content.ContextCompat.getColor(this, R.color.action_neutral)
        findViewById<Button>(R.id.tabSync).backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (sync) on else off)
        findViewById<Button>(R.id.tabLog).backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (sync) off else on)
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun destinationUri(): Uri? =
        prefs.getString("dest", null)?.let(Uri::parse)

    /** Survives process death so a crashed/killed sync can be healed next launch. */
    private fun setMountedFlag(mounted: Boolean) =
        prefs.edit().putBoolean("left_mounted", mounted).apply()

    /**
     * If a previous session ended without confirming the ECU had its card back
     * (crash, process kill, battery death, cable yank), put it right now.
     */
    private fun healIfLeftMounted() {
        if (!prefs.getBoolean("left_mounted", false)) return
        log("Previous session may have left the ECU not logging - restoring...")
        io.execute {
            val r = link.sendCommand(EcuLink.CMD_RESTORE_AUTO, attempts = 3)
            runOnUiThread {
                if (r.ok) {
                    setMountedFlag(false)
                    mountedToPhone = false
                    log("Recovered: ECU logging restored")
                } else {
                    mountedToPhone = true
                    log("Could not restore logging (${r.message}) - tap 'Force ECU logging'")
                }
                refresh()
            }
        }
    }

    private fun startSync() {
        if (!link.isOpen) { log("Not connected - tap Connect first"); return }
        val dest = destinationUri()
        if (dest == null) { log("Pick a destination folder first"); return }

        val job = SyncJob(this, link, history)
        runningJob = job
        mountedToPhone = true
        refresh()
        // A big sync takes minutes; do not let the screen sleep mid-transfer.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showProgressDialog(job)
        log("=== Sync started ===")
        setMountedFlag(true)
        io.execute {
            val outcome = job.run(
                dest,
                log = { line -> runOnUiThread { log(line) } },
                progress = { p -> runOnUiThread { updateProgressDialog(p) } },
                onSafeToUnplug = { runOnUiThread { announceSafeToUnplug() } }
            )
            runOnUiThread {
                runningJob = null
                dismissProgressDialog()
                showSummary(outcome)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Only clear the "not logging" state if the ECU actually confirmed
                // it took the card back. Never tell the owner it is safe to drive
                // away on an assumption - that is how a whole drive goes unlogged.
                mountedToPhone = !outcome.restored
                setMountedFlag(!outcome.restored)
                log(
                    when {
                        !outcome.restored -> "=== FINISHED, BUT ECU LOGGING NOT CONFIRMED - see warning above ==="
                        outcome.cancelled -> "=== Sync cancelled; logging resumed, safe to unplug ==="
                        outcome.clean -> "=== Sync complete; logging resumed ==="
                        else -> "=== Sync finished with problems; logging resumed ==="
                    }
                )
                refresh()
            }
        }
    }

    private fun showProgressDialog(job: SyncJob) {
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        dlgFile = view.findViewById(R.id.dlgFile)
        dlgName = view.findViewById(R.id.dlgName)
        dlgDetail = view.findViewById(R.id.dlgDetail)
        dlgBar = view.findViewById(R.id.dlgBar)
        dlgUnplug = view.findViewById(R.id.dlgUnplug)
        dlgWarn = view.findViewById(R.id.dlgWarn)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dlg_title)
            .setView(view)
            .setCancelable(false)                       // no accidental dismissal mid-transfer
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                job.cancel()
                log("Cancelling after the current chunk...")
            }
            .create()

        dialog.show()
        // make Cancel unmistakably the stop button
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.action_danger))
        progressDialog = dialog
    }

    /**
     * The ECU has its card back; everything left runs on the phone. Say so loudly -
     * this is worth several minutes of not standing next to the car.
     */
    private fun announceSafeToUnplug() {
        mountedToPhone = false
        setMountedFlag(false)

        dlgUnplug?.visibility = android.view.View.VISIBLE
        dlgWarn?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.action_primary))
        dlgWarn?.text = getString(R.string.unplug_detail)
        progressDialog?.setTitle(R.string.unplug_ok)
        // Cancel no longer risks anything on the car side.
        progressDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            ?.setText(R.string.btn_close)

        // A buzz, so it lands even if the phone is face-down on the seat.
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            vib.vibrate(android.os.VibrationEffect.createOneShot(400, 180))
        }

        android.widget.Toast.makeText(
            this, "Safe to unplug - ECU is logging", android.widget.Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    private fun updateProgressDialog(p: SyncJob.Progress?) {
        if (p == null) return
        dlgFile?.text = "File ${p.fileIndex} of ${p.fileCount}"
        dlgName?.text = p.fileName
        dlgDetail?.text = p.detail
        dlgBar?.progress = p.overallPercent.coerceIn(0, 100)
    }

    private fun dismissProgressDialog() {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        dlgFile = null; dlgName = null; dlgDetail = null; dlgBar = null
        dlgUnplug = null; dlgWarn = null
    }

    /** A plain-language result that stays on screen, instead of a buried log line. */
    private fun showSummary(o: SyncJob.Outcome) {
        val parts = mutableListOf<String>()
        parts += when (o.copied) {
            0 -> "No new logs"
            1 -> "1 new log copied"
            else -> "${o.copied} new logs copied"
        }
        if (o.skipped > 0) parts += "${o.skipped} already had"
        if (o.failed > 0) parts += "${o.failed} FAILED (will retry next sync)"
        if (o.cancelled) parts += "cancelled"

        summaryView.text = when {
            !o.restored -> "\u26a0 " + parts.joinToString(", ") +
                "\nECU LOGGING NOT CONFIRMED - reconnect and tap 'Force ECU logging'"
            else -> parts.joinToString(", ") + "\nCard returned to ECU, logging resumed."
        }
        summaryView.visibility = android.view.View.VISIBLE
    }

    private fun refresh() {
        destView.text = destinationUri()?.let { "Saving to: ${prettyDest(it)}" }
            ?: getString(R.string.dest_unset)
        findViewById<Button>(R.id.btnSync).isEnabled = runningJob == null
        val state = when {
            runningJob != null -> getString(R.string.status_syncing)
            !link.isOpen -> getString(R.string.status_disconnected)
            mountedToPhone -> getString(R.string.status_mounted)
            else -> getString(R.string.status_connected)
        }
        statusView.text = "$state\nbuild ${appVersion()}"
        warnView.visibility =
            if (mountedToPhone) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun warn(text: String) {
        warnView.text = text
        warnView.visibility = android.view.View.VISIBLE
        log(text)
    }

    private fun log(line: String) {
        logView.text = "${stamp.format(Date())}  $line\n${logView.text}"
    }
}
