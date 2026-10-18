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
        val alarm = intent?.getBooleanExtra(EXTRA_ALARM, false) ?: false
        startForeground(NOTE_ID, build(text, pct, safe, alarm))
        return START_NOT_STICKY      // never resurrect a sync we cannot resume safely
    }

    private fun build(text: String, pct: Int, safe: Boolean, alarm: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)
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
        val b = Notification.Builder(this, if (alarm) ALARM_CHANNEL else CHANNEL)
            .setContentTitle(when {
                alarm -> "ECU IS NOT LOGGING"
                safe -> "Safe to unplug - ECU is logging"
                else -> "MiniLogSync"
            })
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

        /**
         * The NOT-LOGGING alarm needs its own channel. It used to share the progress
         * channel, created at IMPORTANCE_LOW; warn() re-created that same id at HIGH, but
         * Android ignores importance changes to an existing channel - so the one warning
         * that survives this app's screen closing arrived silently, with no heads-up.
         */
        private const val ALARM_CHANNEL = "minilogsync.alarm"

        private fun createChannels(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Log sync", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while copying logs off the ECU" }
            )
            nm.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL, "ECU not logging", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "The ECU's card is with the phone, or logging is unconfirmed" }
            )
        }
        private const val NOTE_ID = 1
        const val EXTRA_TEXT = "text"
        const val EXTRA_PERCENT = "pct"
        const val EXTRA_SAFE = "safe"
        const val EXTRA_ALARM = "alarm"

        /**
         * Android 12+ forbids STARTING a foreground service from the background, and
         * USB detach is not on the exemption list. Thrown from inside a BroadcastReceiver
         * this would crash the process - so the warning path would kill the app instead
         * of warning, on exactly the event the warning exists for. Fall back to a plain
         * notification, which needs no service.
         */
        fun update(
            ctx: Context, text: String, percent: Int = -1, safe: Boolean = false,
            alarm: Boolean = false
        ) {
            val i = Intent(ctx, SyncKeepAlive::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_SAFE, safe)
                .putExtra(EXTRA_ALARM, alarm)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Throwable) {
                warn(ctx, text, safe)
            }
        }

        /** Post the message as an ordinary notification - no service involved. */
        fun warn(ctx: Context, text: String, safe: Boolean = false) {
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                createChannels(nm)
                val open = PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                nm.notify(
                    NOTE_ID + 1,
                    Notification.Builder(ctx, if (safe) CHANNEL else ALARM_CHANNEL)
                        .setContentTitle(if (safe) "Safe to unplug" else "MiniLogSync")
                        .setContentText(text)
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build()
                )
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncKeepAlive::class.java))
        }

        /** Clear a fallback warning posted by [warn] once the ECU is confirmed logging. */
        fun clearWarning(ctx: Context) {
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTE_ID + 1)
            }
        }
    }
}
