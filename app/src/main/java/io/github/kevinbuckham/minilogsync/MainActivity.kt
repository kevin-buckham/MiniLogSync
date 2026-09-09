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


    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var warnView: TextView
    private lateinit var destView: TextView
    private lateinit var summaryView: TextView

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** True while the card is handed to the phone - the ECU is NOT logging. */
    private var mountedToPhone = false

    companion object {
        /**
         * The USB link, the worker and the running job live at PROCESS scope, not on
         * the Activity.
         *
         * Android destroys and recreates a backgrounded Activity freely. When these
         * were Activity-owned, coming back after a couple of minutes - or simply
         * tapping the progress notification - built a second MainActivity whose
         * onCreate ran healIfLeftMounted(), saw the left_mounted flag that the RUNNING
         * sync had set, and sent `sdmode auto`. The ECU took its card back mid-copy
         * and the transfer died. Returning within a few seconds only resumed the
         * Activity, so it survived - which is exactly the reported symptom.
         *
         * Process scope also means the flag below distinguishes the two cases that
         * matter: a sync live in THIS process (never heal) versus a flag left behind
         * by a process that is gone (heal is correct).
         */
        @Volatile private var sharedLink: EcuLink? = null
        @Volatile private var sharedHistory: SyncHistory? = null

        fun historyFor(ctx: Context): SyncHistory =
            sharedHistory ?: SyncHistory(ctx.applicationContext).also { sharedHistory = it }
        private val io = Executors.newSingleThreadExecutor()
        @Volatile private var runningJob: SyncJob? = null

        /** Last lines of the log, so Activity recreation does not lose the history. */
        private val logBuffer = ArrayDeque<String>()
        private const val LOG_MAX = 400

        /**
         * The running job writes into these sinks rather than capturing an Activity.
         * A recreated Activity re-registers and picks the sync up live; without this,
         * reattaching would show a frozen dialog and a dead log.
         */
        @Volatile private var uiLog: ((String) -> Unit)? = null
        @Volatile private var uiProgress: ((SyncJob.Progress?) -> Unit)? = null
        @Volatile private var lastProgress: SyncJob.Progress? = null
        @Volatile private var uiSafeToUnplug: (() -> Unit)? = null
        @Volatile private var uiOutcome: ((SyncJob.Outcome) -> Unit)? = null
        @Volatile private var safeAnnounced = false

        /**
         * A foreground service stops the PROCESS being killed; it does not stop the
         * DEVICE suspending. With the screen off and no wake lock, USB bulk transfers
         * stall and the sync hangs - for hours - with the card still handed to the
         * phone and the ECU not logging. FLAG_KEEP_SCREEN_ON was no substitute: it
         * dies with the Activity and was not re-applied on the reattach path.
         */
        @Volatile private var wakeLock: android.os.PowerManager.WakeLock? = null
        @Volatile private var watchdog: Thread? = null

        /**
         * Turns a silent hang into an alarm.
         *
         * A USB read can block indefinitely; the foreground service keeps the process
         * alive and the notification keeps saying "Copying…", which reads as healthy
         * while the ECU sits mounted and NOT LOGGING. Nothing else notices - the wake
         * lock just expires at its cap and the device sleeps.
         */
        fun startWatchdog(app: Context, job: SyncJob) {
            watchdog?.interrupt()
            val w = Thread {
                val limitMs = 5 * 60_000L
                try {
                    while (runningJob === job) {
                        Thread.sleep(30_000)
                        if (runningJob !== job) break
                        val idle = System.currentTimeMillis() - job.lastActivityAt
                        if (idle > limitMs) {
                            emitLog("*** SYNC APPEARS STUCK - no progress for " +
                                    "${idle / 60000} minute(s) ***")
                            emitLog("The card may still be assigned to the phone, which means")
                            emitLog("the ECU is NOT LOGGING. Cancelling and returning the card.")
                            SyncKeepAlive.warn(
                                app, "Sync stuck - the ECU may NOT be logging. Open the app.",
                                safe = false
                            )
                            job.cancel()
                            break
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }
            w.isDaemon = true
            watchdog = w
            w.start()
        }

        fun stopWatchdog() {
            watchdog?.interrupt()
            watchdog = null
        }

        fun acquireWakeLock(ctx: Context) {
            if (wakeLock != null) return
            runCatching {
                val pm = ctx.applicationContext
                    .getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK, "MiniLogSync:sync"
                ).apply {
                    setReferenceCounted(false)
                    acquire(45 * 60 * 1000L)   // hard cap: a hang must not hold it forever
                }
            }
        }

        fun releaseWakeLock() {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }

        /**
         * Detach is registered at PROCESS scope, not on the Activity.
         *
         * The receiver used to live and die with the Activity while the job is
         * process-scoped, so a cable pull or ECU reset arriving with no Activity alive
         * was seen by nobody - the same "process-scoped work, Activity-scoped event
         * handling" split that caused the last three bugs. Detach is the one that can
         * silently cost a whole unlogged drive, so it gets its own permanent receiver.
         */
        @Volatile private var detachReceiver: BroadcastReceiver? = null

        fun ensureDetachReceiver(ctx: Context) {
            if (detachReceiver != null) return
            val app = ctx.applicationContext
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                    val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
                    // safeAnnounced: the owner has been TOLD to unplug, so a detach is
                    // expected and must not fire the alarm - crying wolf with the single
                    // most important warning text teaches him to ignore it.
                    val mounted = (prefs.getBoolean("left_mounted", false) || isSyncing)
                        && !safeAnnounced
                    emitLog("ECU detached" + if (mounted) " WHILE MOUNTED" else "")
                    if (mounted) {
                        // The ECU keeps PC mode across a cable pull (it is RAM state),
                        // so it is not logging and we cannot tell it anything now.
                        prefs.edit().putBoolean("left_mounted", true).apply()
                        SyncKeepAlive.update(
                            app, "CABLE PULLED WHILE MOUNTED - the ECU is NOT LOGGING. "
                                + "Reconnect and tap 'Force ECU logging'.", -1, safe = false
                        )
                    }
                }
            }
            val f = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, f)
            }
            detachReceiver = r
        }

        // SimpleDateFormat is not thread-safe and emitLog runs on both the io thread
        // (job callbacks) and the main thread (UI logging).
        private val logStamp = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.US)
        }

        fun emitLog(line: String) {
            val entry = "${logStamp.get()!!.format(Date())}  $line"
            synchronized(logBuffer) {
                logBuffer.addFirst(entry)
                while (logBuffer.size > LOG_MAX) logBuffer.removeLast()
            }
            uiLog?.invoke(entry)
        }

        fun emitProgress(p: SyncJob.Progress?) {
            lastProgress = p
            uiProgress?.invoke(p)
        }

        val isSyncing: Boolean get() = runningJob != null

        fun linkFor(ctx: Context): EcuLink =
            sharedLink ?: EcuLink(ctx.applicationContext).also { sharedLink = it }
    }

    /** Process-scoped so it survives Activity recreation mid-sync. */
    private val link: EcuLink get() = linkFor(this)

    /** Process-scoped: a per-Activity copy diverged from the running job's. */
    private val history: SyncHistory get() = historyFor(this)

    // This instance's sink lambdas, kept so onDestroy can tell whether it still owns
    // the process-scoped slots before clearing them.
    private var myLog: ((String) -> Unit)? = null
    private var myProgress: ((SyncJob.Progress?) -> Unit)? = null
    private var mySafe: (() -> Unit)? = null
    private var myOutcome: ((SyncJob.Outcome) -> Unit)? = null

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

        prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        // Recreated mid-sync: the card really is handed to the phone, so show it.
        mountedToPhone = prefs.getBoolean("left_mounted", false)
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
                    // Everything built for the sync flow - the keep-alive, the detach
                    // warning, the persisted flag - was attached to SYNC only. A manual
                    // mount left the ECU not logging with nothing holding the process
                    // and nothing on screen once the app was backgrounded.
                    safeAnnounced = false
                    acquireWakeLock(this)
                    SyncKeepAlive.update(
                        this, "Card is handed to the phone - THE ECU IS NOT LOGGING. "
                            + "Tap 'Return card to ECU' before driving.", -1, safe = false
                    )
                    log("Card handed to phone. Open Files to copy logs.")
                    log("*** THE ECU IS NOT LOGGING until you tap 'Return card to ECU' ***")
                }
            }
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            send(EcuLink.CMD_RESTORE_AUTO) { ok ->
                if (ok) {
                    mountedToPhone = false
                    setMountedFlag(false)
                    if (!isSyncing) {
                        SyncKeepAlive.stop(this)
                        releaseWakeLock()
                    }
                    log("Card returned to ECU - logging resumed.")
                }
            }
        }

        findViewById<Button>(R.id.btnRestoreEcu).setOnClickListener {
            send(EcuLink.CMD_RESTORE_ECU) { ok ->
                if (ok) {
                    mountedToPhone = false
                    setMountedFlag(false)
                    if (!isSyncing) {
                        SyncKeepAlive.stop(this)
                        releaseWakeLock()
                    }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // NOT merely cosmetic: the notification is the ONLY warning channel that
            // survives this Activity being destroyed, and it carries "ECU LOGGING NOT
            // CONFIRMED" and "CABLE PULLED WHILE MOUNTED". Denied, those alarms are
            // invisible and the owner can drive away unlogged with no indication.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        myLog = { entry -> runOnUiThread { appendToView(entry) } }
        myProgress = { p -> runOnUiThread { updateProgressDialog(p) } }
        mySafe = { runOnUiThread { announceSafeToUnplug() } }
        myOutcome = { o -> runOnUiThread { onSyncFinished(o) } }
        uiLog = myLog
        uiProgress = myProgress
        uiSafeToUnplug = mySafe
        uiOutcome = myOutcome
        ensureDetachReceiver(this)

        restoreLog()
        if (isSyncing) {
            log("Reattached to a sync already in progress")
            showProgressDialog(runningJob!!)
            if (safeAnnounced) announceSafeToUnplug()
            lastProgress?.let { updateProgressDialog(it) }
        } else {
            log("MiniLogSync ${appVersion()} ready")
        }
        refresh()
        checkNotificationsEnabled()
        connect()
    }

    override fun onStop() {
        super.onStop()
        // Home button / app switch: if nothing is in flight, give the port back rather
        // than holding it for the process lifetime.
        if (!isSyncing && !mountedToPhone) releaseIdleLink()
    }

    private fun releaseIdleLink() {
        if (isSyncing) return
        runCatching { sharedLink?.close() }
        sharedLink = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        // A sync in flight owns the USB link and the card. Tearing it down here would
        // abandon the ECU mounted and NOT LOGGING - and this ran on something as
        // ordinary as a screen rotation. The foreground service keeps the process
        // alive so the worker can finish and hand the card back.
        // Drop the UI sinks either way: they point at views that are now dead. The
        // job keeps running and the next Activity re-registers.
        // Only clear sinks we still own: a deferred onDestroy from the OLD instance
        // could otherwise null the sinks the NEW one just registered, reproducing the
        // frozen-dialog symptom these sinks exist to prevent.
        if (uiLog === myLog) uiLog = null
        if (uiProgress === myProgress) uiProgress = null
        if (uiSafeToUnplug === mySafe) uiSafeToUnplug = null
        if (uiOutcome === myOutcome) uiOutcome = null
        if (isSyncing) {
            // Link, worker and job are process-scoped now, so the copy carries on and
            // a recreated Activity simply reattaches to it.
            return
        }
        // Not syncing: release the CDC port so other apps (TunerStudio, CX File
        // Explorer) can reach the ECU. Guarded on isFinishing so a configuration
        // change does not churn the link; onStop covers the common home-button case.
        if (isFinishing) releaseIdleLink()
    }

    private fun connect() {
        if (isSyncing) {
            log("Sync already running - not reconnecting")
            refresh()
            return
        }
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
        if (isSyncing) {
            // The flag is set BY the running sync. Healing here would send
            // `sdmode auto` and yank the card out from under the copy in progress.
            log("Sync in progress - reattached to it")
            return
        }
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

        val job = SyncJob(applicationContext, link, history)
        runningJob = job
        mountedToPhone = true
        refresh()
        // A big sync takes minutes; do not let the screen sleep mid-transfer.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        safeAnnounced = false
        acquireWakeLock(this)
        startWatchdog(applicationContext, job)
        SyncKeepAlive.update(this, "Starting…")
        showProgressDialog(job)
        log("=== Sync started ===")
        setMountedFlag(true)
        io.execute {
            val app = applicationContext
            // isSyncing is process-scoped now, so a throw escaping here would wedge it
            // true for the process lifetime - disabling SYNC, connect() and, worst,
            // healIfLeftMounted(). The app would then actively refuse to hand the card
            // back until force-stopped. Throwable, not Exception: OOM is reachable
            // because the copy path allocates per chunk.
            var outcome: SyncJob.Outcome? = null
            try {
                outcome = job.run(
                dest,
                log = { line -> emitLog(line) },
                progress = { p ->
                    if (p != null) {
                        SyncKeepAlive.update(
                            app, "${p.fileName}  (${p.fileIndex} of ${p.fileCount})",
                            p.overallPercent, safe = safeAnnounced
                        )
                    }
                    emitProgress(p)
                },
                onSafeToUnplug = {
                    safeAnnounced = true
                    SyncKeepAlive.update(app, "Checking copied files - the ECU is not needed",
                                         -1, safe = true)
                    uiSafeToUnplug?.invoke()
                }
                )
            } catch (t: Throwable) {
                emitLog("SYNC CRASHED: ${t.javaClass.simpleName}: ${t.message}")
                // The card may still be handed to the phone. Try to give it back,
                // because nothing else will.
                emitLog("Attempting to return the card to the ECU…")
                val recovered = runCatching {
                    link.sendCommand(EcuLink.CMD_RESTORE_AUTO, attempts = 4).ok
                }.getOrDefault(false)
                outcome = SyncJob.Outcome(0, 0, 1, cancelled = true, restored = recovered)
            } finally {
                runningJob = null
                stopWatchdog()
            }
            val result = outcome ?: SyncJob.Outcome(0, 0, 1, cancelled = true, restored = false)
            // HIGH: stopping the service unconditionally deleted the ONLY warning
            // channel that survives Activity death. If logging was not confirmed the
            // notification must STAY, saying so - otherwise the progress notification
            // just vanishes, which reads as success, and the owner drives away with the
            // ECU mounted and not logging.
            if (result.restored) {
                SyncKeepAlive.stop(app)
            } else {
                SyncKeepAlive.update(
                    app, "ECU LOGGING NOT CONFIRMED - reopen the app and tap "
                        + "'Force ECU logging' before driving", -1, safe = false
                )
            }
            releaseWakeLock()

            // Persist the mounted state from the worker, so it is correct even if no
            // Activity is alive to receive the callback below.
            getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                .putBoolean("left_mounted", !result.restored).apply()
            emitLog(
                when {
                    !result.restored -> "=== FINISHED, BUT ECU LOGGING NOT CONFIRMED - see warning above ==="
                    result.cancelled -> "=== Sync cancelled; logging resumed, safe to unplug ==="
                    result.clean -> "=== Sync complete; logging resumed ==="
                    else -> "=== Sync finished with problems; logging resumed ==="
                }
            )
            // Delivered through the sink so it lands on the CURRENT Activity. Sent to
            // the launching instance, a recreated Activity was left with a modal
            // "syncing" dialog nobody ever dismissed, indistinguishable from a hang.
            uiOutcome?.invoke(result)
        }
    }

    /** Runs on whichever Activity is alive when the sync finishes. */
    private fun onSyncFinished(result: SyncJob.Outcome) {
        dismissProgressDialog()
        showSummary(result)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Only clear the "not logging" state if the ECU actually confirmed it took the
        // card back. Never tell the owner it is safe to drive away on an assumption -
        // that is how a whole drive goes unlogged.
        mountedToPhone = !result.restored
        refresh()
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
                // Once the card is back with the ECU, this button is just "close" -
                // cancelling then reported a fully successful sync as "cancelled",
                // which teaches the owner to distrust the summary.
                if (safeAnnounced) {
                    log("Closed - verification continues in the background")
                } else {
                    job.cancel()
                    log("Cancelling after the current chunk...")
                }
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

    /** Warn in-app if the only process-surviving alarm channel is muted. */
    private fun checkNotificationsEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val on = runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .areNotificationsEnabled()
        }.getOrDefault(true)
        if (!on) {
            warn("Notifications are OFF. The 'ECU NOT LOGGING' warning cannot reach you "
                + "once this screen is closed - please enable them.")
        }
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

    private fun log(line: String) = emitLog(line)

    private fun appendToView(entry: String) {
        logView.text = "$entry\n${logView.text}"
    }

    /** Repaint the log after the Activity has been recreated. */
    private fun restoreLog() {
        val text = synchronized(logBuffer) { logBuffer.joinToString("\n") }
        if (text.isNotEmpty()) logView.text = text
    }
}
