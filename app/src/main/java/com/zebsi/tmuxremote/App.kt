package com.zebsi.tmuxremote

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Sets up notifications and connects the link's background hooks to Android. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_KEEP, "Watching sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app stays connected in the background."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Session alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A watched session went quiet or rang the bell."
            }
        )
        val ui = Handler(Looper.getMainLooper())
        TmuxLink.keepAliveHook = { on -> ui.post { KeepAliveService.set(this, on) } }
        TmuxLink.busyHook = { busy -> ui.post { KeepAliveService.hold(busy) } }
        TmuxLink.alertSink = { host, alert -> ui.post { postAlert(host, alert) } }
    }

    private fun postAlert(host: Host, alert: Alert) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = Intent(this, TerminalActivity::class.java)
            .putExtra("host", host.id)
            .putExtra("session", alert.sessionName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = alertId(alert.sessionName)
        val (title, text) = when (alert.kind) {
            AlertKind.QUIET -> "${alert.sessionName} is quiet" to "It printed for a while and has now stopped."
            AlertKind.BELL -> "${alert.sessionName} rang the bell" to "Something in this session asks for you."
            AlertKind.LINK_ENDED -> "Connection to ${alert.sessionName} ended" to "Sessions are no longer watched. Open the app to connect again."
        }
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        nm.notify(id, n)
    }

    companion object {
        const val CH_KEEP = "keepalive"
        const val CH_ALERT = "alerts"
        fun alertId(sessionName: String) = 1000 + (sessionName.hashCode() and 0xFFFF)
    }
}

/**
 * Keeps the process and its SSH connection alive while sessions are watched and the app is not
 * on screen. Holds the CPU and Wi-Fi awake only while a watched session is still printing.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TmuxLink.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, HostsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, App.CH_KEEP)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Watching tmux sessions")
            .setContentText("Stays connected to tell you when a watched session goes quiet.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTE_ID, n)
        running = this
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        hold(false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.zebsi.tmuxremote.STOP"
        private const val NOTE_ID = 1
        private var running: KeepAliveService? = null
        private var wanted = false
        private var wake: PowerManager.WakeLock? = null
        private var wifi: WifiManager.WifiLock? = null

        /** Main thread. Starting needs the app on screen (Android rule); it always is at that moment. */
        fun set(ctx: Context, on: Boolean) {
            if (on == wanted && (on == (running != null))) return
            wanted = on
            val i = Intent(ctx, KeepAliveService::class.java)
            if (on) {
                try {
                    ctx.startForegroundService(i)
                } catch (e: Exception) {
                    wanted = false
                }
            } else {
                ctx.stopService(i)
                hold(false)
            }
        }

        /** Main thread. Keep CPU and Wi-Fi awake while a watched session is busy. */
        @Suppress("DEPRECATION")
        fun hold(on: Boolean) {
            val svc = running
            if (on && svc != null) {
                if (wake == null) {
                    wake = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tmuxremote:watch").apply { setReferenceCounted(false) }
                    wifi = (svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tmuxremote:watch").apply { setReferenceCounted(false) }
                }
                // Time limit as a safety net; renewed with every poll while still busy.
                wake?.acquire(10 * 60 * 1000L)
                runCatching { wifi?.acquire() }
            } else {
                runCatching { if (wake?.isHeld == true) wake?.release() }
                runCatching { if (wifi?.isHeld == true) wifi?.release() }
            }
        }
    }
}
