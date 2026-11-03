package com.keshav.daptap.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.applink.AppleLink
import com.keshav.daptap.applink.PairingService
import kotlinx.coroutines.delay

/**
 * The iPhone link setup, one plain step at a time. Each step opens the exact Settings page it needs, watches
 * for the change itself and moves on by itself, so the person only ever does the one thing on screen. Steps
 * already true (on Wi-Fi, Developer options on) are ticked and skipped. After pairing, DapTap does the rest.
 */
@Composable
fun SetupScreen(link: AppleLink, onClose: () -> Unit) {
    val context = LocalContext.current
    val reduce = reducedMotion(context)
    val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val steps = remember { Step.entries.filter { it != Step.AUTO_BLOCKER || samsung } }
    var blockerConfirmed by remember { mutableStateOf(!samsung) }
    var checks by remember { mutableStateOf(Checks()) }
    val state by link.state.collectAsState()

    // Watch every condition about once a second while this screen is up (and at once on return from Settings).
    LaunchedEffect(Unit) {
        while (true) {
            checks = Checks.read(context, link)
            delay(700)
        }
    }
    val done = { s: Step ->
        when (s) {
            Step.AUTO_BLOCKER -> blockerConfirmed
            Step.WIFI -> checks.wifi || checks.paired
            Step.DEVELOPER -> checks.developer || checks.paired
            Step.WIRELESS -> checks.wireless || checks.paired
            Step.PAIR -> checks.paired
            Step.START -> state.phase == AppleLink.Phase.RUNNING
        }
    }
    val current = steps.firstOrNull { !done(it) }
    // Pairing: listen for the pairing-code dialog as soon as Wireless debugging is on, so the notification is
    // ready by the time they open it. Starting: once paired, DapTap starts the link itself.
    LaunchedEffect(current) {
        if (current == Step.PAIR) PairingService.start(context)
        if (current == Step.START && state.phase != AppleLink.Phase.STARTING) link.start()
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().walletGround()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 16.dp, end = 16.dp),
        ) {
            Text("Set up NameDrop with iPhones", color = Wallet.ink, fontSize = 28.sp, lineHeight = 33.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            Text("About two minutes, once. DapTap checks each step for you and moves on by itself.",
                color = Wallet.inkSoft, fontSize = 16.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp))

            // Steps already behind them, ticked, so they can see how far they've come.
            steps.forEach { s ->
                if (s == current) return@forEach
                if (current != null && steps.indexOf(s) > steps.indexOf(current)) return@forEach
                DoneRow(s.short)
            }
            if (current == null) {
                Finished(onClose)
            } else {
                AnimatedContent(
                    current,
                    transitionSpec = {
                        if (reduce) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(240)) { -it / 3 } + fadeOut(tween(160)))
                    },
                    label = "step",
                ) { s ->
                    StepPass(
                        step = s, number = steps.indexOf(s) + 1, total = steps.size,
                        failure = if (s == Step.START && state.phase == AppleLink.Phase.STOPPED) state.message else null,
                        onAction = { s.open(context) },
                        onConfirm = if (s == Step.AUTO_BLOCKER) ({ blockerConfirmed = true }) else null,
                    )
                }
                // Later steps, quiet, so nothing comes as a surprise.
                steps.drop(steps.indexOf(current) + 1).forEach { UpcomingRow(it.short) }
            }
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close setup", tint = Wallet.ink) }
            Text("iPhone link", color = Wallet.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** What each step asks, in the person's words, and the one Settings page it opens. */
private enum class Step(val short: String, val title: String, val body: String, val action: String?, val waiting: String) {
    AUTO_BLOCKER(
        "Auto Blocker off",
        "Check Samsung Auto Blocker is off",
        "Auto Blocker switches off the settings the iPhone link runs on. Open Auto Blocker and make sure it's off, then come back.",
        "Open Security and privacy", "Find Auto Blocker on that page",
    ),
    WIFI(
        "On Wi-Fi",
        "Connect to Wi-Fi",
        "Setup uses Wireless debugging, which only runs on Wi-Fi. Any network works. You only need it now, and when the phone restarts.",
        "Open Wi-Fi", "Waiting for Wi-Fi",
    ),
    DEVELOPER(
        "Developer options on",
        "Turn on Developer options",
        "Tap Software information, then tap Build number 7 times. Enter your PIN if the phone asks. Then come back here.",
        "Open About phone", "Waiting for Developer options",
    ),
    WIRELESS(
        "Wireless debugging on",
        "Turn on Wireless debugging",
        "Scroll down to Wireless debugging and switch it on. Tap Allow when the phone asks about this network.",
        "Open Developer options", "Waiting for Wireless debugging",
    ),
    PAIR(
        "Paired",
        "Pair DapTap with your phone",
        "Tap the words Wireless debugging (not the switch), then Pair device with pairing code. A DapTap notification pops up: tap Enter code and type the 6 digits. Stay on that Settings screen while you type.",
        "Open Wireless debugging", "Waiting for the pairing code",
    ),
    START(
        "Link running",
        "Starting the iPhone link",
        "Nothing to do. DapTap is starting the link and will keep it running from now on, even after a restart.",
        null, "Starting…",
    );

    fun open(context: Context) {
        val intent = when (this) {
            AUTO_BLOCKER -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            DEVELOPER -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).putExtra(HIGHLIGHT, "build_number")
            WIRELESS, PAIR -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(HIGHLIGHT, "toggle_adb_wireless")
            START -> return
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    companion object {
        /** Settings scrolls to and highlights the row with this key, where it supports it. */
        const val HIGHLIGHT = ":settings:fragment_args_key"
    }
}

private data class Checks(val wifi: Boolean = false, val developer: Boolean = false, val wireless: Boolean = false, val paired: Boolean = false) {
    companion object {
        fun read(context: Context, link: AppleLink): Checks {
            val r = context.contentResolver
            val wifi = runCatching {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
            }.getOrDefault(false)
            return Checks(
                wifi = wifi,
                developer = Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                wireless = Settings.Global.getInt(r, AppleLink.ADB_WIFI, 0) == 1,
                paired = link.paired,
            )
        }
    }
}

/** The step in hand, as a pass: step count and what to do on the face, the live check on the stub. */
@Composable
private fun StepPass(step: Step, number: Int, total: Int, failure: String?, onAction: () -> Unit, onConfirm: (() -> Unit)?) {
    GlassPass(LocalGround.current, Modifier.fillMaxWidth().padding(vertical = 6.dp), stubHeight = STUB_HEIGHT) {
        Column {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
                PassHead("STEP $number OF $total", "IPHONE LINK")
                Spacer(Modifier.height(16.dp))
                Text(step.title, color = Wallet.ink, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(failure ?: step.body, color = Wallet.inkSoft, fontSize = 17.sp, lineHeight = 24.sp)
                if (step.action != null || failure != null) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Wallet.ink, contentColor = Wallet.navyTop),
                    ) { Text(step.action ?: "Open Settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (onConfirm != null) {
                    TextButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("It's off. Next", color = Wallet.ink, fontSize = 16.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(STUB_HEIGHT).padding(horizontal = 24.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WaitingDot()
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(if (onConfirm != null) "YOUR CALL" else "CHECKING FOR YOU", style = Wallet.tabCaps.copy(color = Wallet.ink, fontSize = 13.sp))
                    Text(step.waiting, color = Wallet.inkSoft, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun DoneRow(text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = Wallet.carbon, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Wallet.ink, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text("DONE", style = Wallet.tabCaps.copy(color = Wallet.carbon))
    }
}

@Composable
private fun UpcomingRow(text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { StateDot(Wallet.inkSoft.copy(alpha = 0.5f)) }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Wallet.inkSoft, fontSize = 15.sp)
    }
}

/** All steps done: the pass gets its stamp. */
@Composable
private fun Finished(onClose: () -> Unit) {
    val reduce = reducedMotion(LocalContext.current)
    val slam = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) slam.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f)) }
    GlassPass(LocalGround.current, Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PassHead("IPHONE LINK", "READY")
            Text("You're set", color = Wallet.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Hold your phone back to back with an iPhone and NameDrop starts. DapTap keeps the link running by " +
                "itself from now on. Leave Developer options and USB debugging on (no cable needed).",
                color = Wallet.inkSoft, fontSize = 17.sp, lineHeight = 24.sp)
            Stamp("READY", Modifier.graphicsLayer { scaleX = 1.8f - 0.8f * slam.value; scaleY = scaleX; alpha = slam.value })
            Button(
                onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Wallet.ink, contentColor = Wallet.navyTop),
            ) { Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun WaitingDot() {
    val pulse = rememberInfiniteTransition(label = "wait")
    val a by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    StateDot(Wallet.jet, Modifier.alpha(a))
}
