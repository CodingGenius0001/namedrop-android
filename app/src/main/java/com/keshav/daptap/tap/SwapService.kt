package com.keshav.daptap.tap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.keshav.daptap.R

/**
 * Runs only while a NameDrop swap with an iPhone is in progress (a few seconds). Quick Share is on screen then
 * and DapTap is behind it; One UI freezes or throttles background apps, which stalled the QUIC exchange (the
 * helper looked dead and the iPhone sat on "waiting"). A foreground service keeps DapTap at full speed.
 * Starting it from the background is allowed: DapTap holds the overlay permission and its cover is showing.
 */
class SwapService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Swapping with an iPhone", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle("Swapping cards with an iPhone")
            .setContentText("Keep the phones close for a few seconds")
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "swap"
        private const val ID = 21

        fun set(context: Context, on: Boolean) {
            val intent = Intent(context, SwapService::class.java)
            runCatching { if (on) context.startForegroundService(intent) else context.stopService(intent) }
        }
    }
}
