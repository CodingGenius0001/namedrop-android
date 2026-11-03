package com.keshav.daptap.diag

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
import com.keshav.daptap.MainActivity
import com.keshav.daptap.R

/**
 * Keeps DapTap's process running while Testing mode is on, so the tap log keeps uploading and the interface
 * watcher keeps watching while another app (Quick Share) is on screen. One UI freezes background apps
 * otherwise. Started from the foreground only; stops when Testing mode is turned off.
 */
class TestingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Testing mode", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle("DapTap testing mode")
            .setContentText("Sending the tap log for debugging. Turn off in Settings › Diagnostics.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "testing"
        private const val ID = 20

        fun set(context: Context, on: Boolean) {
            val intent = Intent(context, TestingService::class.java)
            runCatching { if (on) context.startForegroundService(intent) else context.stopService(intent) }
        }
    }
}
