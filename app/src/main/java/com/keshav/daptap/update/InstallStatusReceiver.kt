package com.keshav.daptap.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keshav.daptap.BuildConfig
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.Notifications

/** PackageInstaller reports here on the session the updater committed. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_INSTALL_STATUS) return
        (context.applicationContext as DapTapApp).updater.onInstallStatus(intent)
    }
}

/**
 * Runs in the new version right after an update replaced the app. A self-update closes DapTap with nothing
 * else on screen, so this brings it back (allowed when "Display over other apps" is on) and posts a
 * notification that does the same in one tap.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifications.updated(context, BuildConfig.VERSION_NAME)
        // With "Display over other apps" allowed, Android lets DapTap bring itself back; otherwise the
        // notification above does it in one tap (MainActivity clears it once open).
        if (android.provider.Settings.canDrawOverlays(context)) runCatching {
            context.startActivity(
                Intent(context, com.keshav.daptap.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
