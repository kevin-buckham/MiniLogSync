package io.github.kevinbuckham.minilogsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive for the duration of a sync.
 *
 * WHY: the copy runs on a plain background thread owned by MainActivity. The moment
 * the owner switches apps or the screen sleeps, that process becomes cacheable and
 * Android may kill it - mid-copy, with the card still handed to the phone, which
 * leaves the ECU NOT LOGGING until the app is next opened. A foreground service is
 * the only supported way to tell Android this work must finish.
 *
 * It deliberately does no work itself: it holds foreground importance and shows
 * progress, while the sync stays where it is. Less moving parts than relocating the
 * job, and it fixes the failure that actually happens.
 */
class SyncKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Copying logs from the ECU…"
        val pct = intent?.getIntExtra(EXTRA_PERCENT, -1) ?: -1
        val safe = intent?.getBooleanExtra(EXTRA_SAFE, false) ?: false
        startForeground(NOTE_ID, build(text, pct, safe))
        return START_NOT_STICKY      // never resurrect a sync we cannot resume safely
    }

    private fun build(text: String, pct: Int, safe: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Log sync", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while copying logs off the ECU" }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                // Resume the existing Activity. Without SINGLE_TOP, tapping the
                // notification built a NEW MainActivity whose onCreate healed the
                // left_mounted flag and killed the running sync.
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (safe) "Safe to unplug - ECU is logging" else "MiniLogSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (pct in 0..100) b.setProgress(100, pct, false)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "minilogsync.sync"
        private const val NOTE_ID = 1
        const val EXTRA_TEXT = "text"
        const val EXTRA_PERCENT = "pct"
        const val EXTRA_SAFE = "safe"

        fun update(ctx: Context, text: String, percent: Int = -1, safe: Boolean = false) {
            val i = Intent(ctx, SyncKeepAlive::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_SAFE, safe)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncKeepAlive::class.java))
        }
    }
}
