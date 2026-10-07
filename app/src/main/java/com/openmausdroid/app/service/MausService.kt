package com.openmausdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openmausdroid.app.MainActivity
import com.openmausdroid.app.R
import com.openmausdroid.app.core.Omb
import com.openmausdroid.app.core.Proot
import com.openmausdroid.app.core.Runtime
import com.openmausdroid.app.core.Sessions
import com.openmausdroid.app.core.Setup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the Ubuntu environment alive: extracts/bootstrap on first start,
 * then supervises the OpenMausBot harness and the web terminal, enforces the
 * immutable system prompt and pumps the harness event stream to the UI.
 */
class MausService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervisor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        acquireWakeLock()
        if (supervisor == null) {
            supervisor = scope.launch { runEnvironment() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Omb.stopEvents()
        Sessions.stopAll()
        Proot.stopAll()
        runCatching { wakeLock?.release() }
        wakeLock = null
        Runtime.serverReady.value = false
        Runtime.harnessPid.value = null
        Runtime.phase.value = Runtime.Phase.STOPPED
        Runtime.append("environment stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runEnvironment() {
        val setup = Setup.prepare(applicationContext)
        if (setup.isFailure || !isActive) return

        Runtime.phase.value = Runtime.Phase.STARTING
        Runtime.append("starting harness and terminal")
        Proot.launch("harness", "/usr/local/bin/maus-harness")
        Proot.launch("ttyd", "/usr/local/bin/maus-ttyd")

        val ready = Omb.waitUntilReady(180_000)
        if (!isActive) return
        if (!ready) {
            Runtime.append("harness not answering yet, retrying in background")
        }
        Runtime.serverReady.value = ready
        if (ready) {
            Runtime.phase.value = Runtime.Phase.READY
            runCatching { Omb.enforcePrompt() }
                .onFailure { Runtime.append("prompt not enforced: ${it.message}") }
            Omb.startEvents()
        } else {
            Runtime.phase.value = Runtime.Phase.STARTING
        }

        // Watchdog: keep the guests alive until Android tears the service down.
        while (isActive) {
            delay(15_000)
            if (!Proot.isAlive("harness")) {
                Runtime.append("harness exited, restarting")
                Proot.launch("harness", "/usr/local/bin/maus-harness")
            }
            if (!Proot.isAlive("ttyd")) {
                Proot.launch("ttyd", "/usr/local/bin/maus-ttyd")
            }
            Sessions.refresh()
            if (!Runtime.serverReady.value) {
                if (Omb.waitUntilReady(10_000)) {
                    Runtime.serverReady.value = true
                    Runtime.phase.value = Runtime.Phase.READY
                    runCatching { Omb.enforcePrompt() }
                    Omb.startEvents()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenMausDroid:env").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "maus_env"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, MausService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MausService::class.java))
        }
    }
}
