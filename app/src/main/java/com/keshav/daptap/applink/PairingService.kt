package com.keshav.daptap.applink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.MainActivity
import com.keshav.daptap.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-time pairing with Wireless debugging. Settings closes its pairing-code dialog if you switch apps, so
 * DapTap watches for the dialog (its mDNS service) and asks for the code in a notification you can type into
 * without leaving Settings. Once paired it starts the iPhone link helper.
 */
class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: AutoCloseable? = null
    @Volatile private var port = 0
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "iPhone link setup", NotificationManager.IMPORTANCE_HIGH))
        when (intent?.action) {
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter(Char::isDigit)
                if (!code.isNullOrEmpty()) submit(code)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> { stopSelf(); return START_NOT_STICKY }
        }
        val first = notify("Waiting for the pairing code", "In Wireless debugging, tap “Pair device with pairing code”.", ask = false)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, first)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (watch == null) {
            watch = AdbDiscovery.watch(this, AdbDiscovery.PAIRING) { p ->
                scope.launch {
                    if (busy || p == port) return@launch
                    port = p
                    notify("Enter the pairing code", "Type the 6-digit code Settings is showing.", ask = true)
                }
            }
            scope.launch { delay(TIMEOUT_MS); if (!busy) stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun submit(code: String) {
        if (busy) return
        val p = port
        if (p == 0) { notify("Open the pairing code first", "In Wireless debugging, tap “Pair device with pairing code”.", ask = false); return }
        busy = true
        notify("Pairing…", null, ask = false)
        val link = (application as DapTapApp).appleLink
        scope.launch {
            val r = link.pair(p, code)
            if (r.isFailure) {
                busy = false
                notify("That didn't work", "Check the code and type it again (${r.exceptionOrNull()?.message}).", ask = true)
                return@launch
            }
            notify("Paired. Starting the iPhone link…", null, ask = false)
            val started = link.start()
            done(if (started.isSuccess) "iPhone link is on" else "Paired, but the link didn't start",
                if (started.isSuccess) "You can turn off Wireless debugging. Tap phones with an iPhone to NameDrop."
                else started.exceptionOrNull()?.message)
        }
    }

    private fun done(title: String, text: String?) {
        watch?.close(); watch = null
        val nm = getSystemService(NotificationManager::class.java)
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(ID + 1, base(title, text).setOngoing(false).setAutoCancel(true).build())
        stopSelf()
    }

    private fun base(title: String, text: String?): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
    }

    private fun notify(title: String, text: String?, ask: Boolean): android.app.Notification {
        val b = base(title, text).setOngoing(true)
        if (ask) {
            val reply = PendingIntent.getService(this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_daptap, "Enter code", reply)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build())
                .build())
        }
        val cancel = PendingIntent.getService(this, 2, Intent(this, PairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        b.addAction(0, "Cancel", cancel)
        val n = b.build()
        getSystemService(NotificationManager::class.java).notify(ID, n)
        return n
    }

    override fun onDestroy() {
        watch?.close(); watch = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "applink"
        private const val ID = 30
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "com.keshav.daptap.applink.CODE"
        private const val ACTION_CANCEL = "com.keshav.daptap.applink.CANCEL"
        private const val TIMEOUT_MS = 10 * 60_000L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PairingService::class.java)) }
        }
    }
}
