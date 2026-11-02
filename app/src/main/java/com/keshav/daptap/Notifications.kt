package com.keshav.daptap

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keshav.daptap.data.Person

object Notifications {
    const val CHANNEL_INCOMING = "incoming"
    const val CHANNEL_TAP = "tap"
    const val CHANNEL_UPDATES = "updates"
    private const val ID_INCOMING = 10
    private const val ID_TAP = 11
    private const val ID_UPDATED = 12

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "Cards you receive", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Someone shared their contact card with you" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TAP, "Taps", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Your card was shared by tapping phones" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "DapTap finished updating itself" })
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun incoming(context: Context, person: Person) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle("${person.card.name} shared their card")
            .setContentText("Tap to save ${person.card.firstName} to your contacts")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ID_INCOMING, n)
    }

    /** Used when Android refuses to open Quick Share from the background: one tap finishes the send. */
    fun sendPending(context: Context, intent: Intent) {
        if (!canPost(context)) return
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_TAP)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle("Send your card")
            .setContentText("Tap to AirDrop your card with Quick Share")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ID_TAP, n)
    }

    /** After a self-update closed the app: say it worked, and reopen it in one tap. */
    fun updated(context: Context, versionName: String) {
        if (!canPost(context)) return
        createChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_daptap)
            .setContentTitle("DapTap updated to $versionName")
            .setContentText("Tap to open it")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ID_UPDATED, n)
    }
}
