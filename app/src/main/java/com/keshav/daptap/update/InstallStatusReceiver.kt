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
 * Runs in the new version right after an update replaced the app. A self-update closes DapTap, and Android
 * won't let it reopen itself from the background, so a notification says it's done and opens it.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifications.updated(context, BuildConfig.VERSION_NAME)
    }
}
