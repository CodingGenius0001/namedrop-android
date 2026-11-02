package com.keshav.daptap.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.keshav.daptap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps DapTap current without downloading APKs by hand.
 *
 * After every release build, the DapTap site (GitHub Pages) publishes `version.json` next to `DapTap.apk`.
 * The app compares build numbers, downloads the APK straight from the site (no redirect to GitHub's asset
 * domain), checks its SHA-256, and hands it to Android's PackageInstaller. Android itself still refuses any
 * APK not signed with DapTap's key, so only real DapTap builds can replace the app.
 */
class Updater(
    private val context: Context,
    private val base: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    @Serializable
    data class Release(val versionCode: Int, val versionName: String, val sha256: String, val size: Long = 0)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        /** Android needs "Install unknown apps" allowed for DapTap before it can install the update. */
        data class NeedsPermission(val release: Release) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.cacheDir, "updates")
    private var lastCheck = 0L
    private var pending: Release? = null

    init {
        // Downloads of this version or older are spent once it is installed.
        dir.listFiles()?.forEach { f ->
            val code = f.name.removePrefix("DapTap-").removeSuffix(".apk").toIntOrNull()
            if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
        }
    }

    private fun busy() = _state.value.let {
        it is State.Checking || it is State.Downloading || it is State.Installing
    }

    /**
     * Looks for a newer build. Automatic checks run once per launch and then at most every six hours;
     * [userAsked] always checks and reports a failure instead of staying quiet.
     */
    suspend fun check(userAsked: Boolean = false) {
        if (busy()) return
        val now = System.currentTimeMillis()
        if (!userAsked && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        val before = _state.value
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
        result.onSuccess { lastCheck = now }
        _state.value = result.fold(
            onSuccess = { r -> if (r.versionCode > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate },
            onFailure = {
                if (userAsked) State.Failed("Couldn't reach the update site. Check your connection and try again.", null)
                else before.takeIf { it is State.Available } ?: State.Idle
            },
        )
    }

    private fun fetch(): Release {
        // The query string skips the site's ten-minute cache, so a build is offered as soon as it is published.
        val request = Request.Builder().url("${base}version.json?t=${System.currentTimeMillis() / 60_000}")
            .header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val release = json.decodeFromString<Release>(resp.body!!.string())
            check(release.sha256.length == 64) { "bad checksum field" }
            return release
        }
    }

    /** Downloads, verifies and installs [release]. Each step reports through [state]. */
    suspend fun update(release: Release) {
        if (busy()) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        _state.value = State.Downloading(release, 0f)
        val file = withContext(Dispatchers.IO) { runCatching { download(release) } }.getOrElse { e ->
            _state.value = State.Failed(
                if (e is ChecksumMismatch) "The download didn't match the published build. Try again in a few minutes."
                else "The download stopped. Check your connection and try again.",
                release,
            )
            return
        }
        _state.value = State.Installing(release)
        pending = release
        withContext(Dispatchers.IO) { runCatching { install(file) } }.onFailure {
            _state.value = State.Failed("Android couldn't start the install.", release)
        }
    }

    /** The user came back from the "Install unknown apps" screen. */
    suspend fun onPermissionResult() {
        val waiting = (_state.value as? State.NeedsPermission)?.release ?: return
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) update(waiting)
        else _state.value = State.Available(waiting)
    }

    private class ChecksumMismatch : Exception()

    private fun download(release: Release): File {
        dir.mkdirs()
        val file = File(dir, "DapTap-${release.versionCode}.apk")
        if (file.exists() && sha256(file).equals(release.sha256, true)) return file
        val partial = File(dir, file.name + ".part")
        val request = Request.Builder().url("${base}DapTap.apk?v=${release.versionCode}").build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _state.value = State.Downloading(release, pct / 100f) }
                    }
                }
            }
            if (!digest.digest().toHex().equals(release.sha256, true)) { partial.delete(); throw ChecksumMismatch() }
        }
        file.delete()
        check(partial.renameTo(file)) { "couldn't keep the download" }
        return file
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            // Once DapTap has installed itself, Android 12+ lets later updates skip the confirmation sheet.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("DapTap.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    /** PackageInstaller's verdict on a committed session. */
    fun onInstallStatus(intent: Intent) {
        val release = pending
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val shown = confirm != null && runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                // If Android won't show its sheet (DapTap went to the background), offer the update again.
                if (!shown) _state.value = release?.let { State.Available(it) } ?: State.Idle
            }
            PackageInstaller.STATUS_SUCCESS -> { pending = null; _state.value = State.UpToDate }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { State.Available(it) } ?: State.Idle
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                _state.value = State.Failed(
                    when (code) {
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "This build can't replace the DapTap you have (it was signed differently). Install it once from the website."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                        else -> "The update didn't install." + if (detail.isNotBlank()) " ($detail)" else ""
                    },
                    release,
                )
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.keshav.daptap.INSTALL_STATUS"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
