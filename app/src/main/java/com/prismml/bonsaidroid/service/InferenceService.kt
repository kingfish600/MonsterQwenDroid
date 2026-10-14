package com.prismml.bonsaidroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.prismml.bonsaidroid.MainActivity
import com.prismml.bonsaidroid.R

/**
 * Foreground wrapper that keeps the 27B runtime (and its mmap'd page cache)
 * resident while the app is backgrounded. Android's oom_adj is unforgiving to
 * cached processes holding ~8 GB of page cache; FGS + partial wake lock is the
 * supported way to say "this memory pressure is intentional".
 */
class InferenceService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val profileName = intent?.getStringExtra(EXTRA_PROFILE) ?: "Bonsai"
                startAsForeground(profileName)
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // Engine teardown lives in the app-scoped singleton; service teardown
        // only releases OS-level keep-alives.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun startAsForeground(profileName: String) {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, InferenceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_channel_inference))
            .setContentText(getString(R.string.notif_server_running, profileName))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bonsaidroid:inference")
            .apply { acquire(WAKE_TIMEOUT_MS) }
        // A single acquisition expires after WAKE_TIMEOUT_MS; a long agent session
        // would then be throttled mid-run, so re-acquire well before the cap.
        handler.removeCallbacks(renewWakeLock)
        handler.postDelayed(renewWakeLock, RENEW_WAKE_LOCK_MS)
    }

    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            acquireWakeLock()
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_inference),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "inference"
        private const val NOTIF_ID = 42
        private const val WAKE_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4h cap per acquisition
        private const val RENEW_WAKE_LOCK_MS = 3 * 60 * 60 * 1000L // renew before the cap
        const val ACTION_STOP = "com.prismml.bonsaidroid.STOP_INFERENCE"
        const val EXTRA_PROFILE = "profile"

        fun start(context: Context, profileName: String) {
            val intent = Intent(context, InferenceService::class.java)
                .putExtra(EXTRA_PROFILE, profileName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, InferenceService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
