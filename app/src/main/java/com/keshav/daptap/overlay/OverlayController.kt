package com.keshav.daptap.overlay

import com.keshav.daptap.ui.walletGround

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.MainActivity
import com.keshav.daptap.data.Person
import com.keshav.daptap.tap.TapEngine
import com.keshav.daptap.ui.CellName
import com.keshav.daptap.ui.ConstructionGrid
import com.keshav.daptap.ui.Ground
import com.keshav.daptap.ui.Grounds
import com.keshav.daptap.ui.reducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A banner drawn over whatever app is open when a tap happens with DapTap closed: the grid ripple,
 * then what happened. Tapping it opens DapTap. Needs "Display over other apps".
 */
class OverlayController(private val app: DapTapApp) {
    private val wm = app.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var hideJob: Job? = null
    private val content = mutableStateOf<OverlayContent?>(null)

    private sealed interface OverlayContent {
        data class Tap(val phase: TapEngine.Phase, val ground: Ground, val seq: Long) : OverlayContent
        data class Card(val person: Person, val ground: Ground) : OverlayContent
        /** Full screen, over Quick Share while it holds the Apple link up for an iPhone swap. */
        data class Cover(val ground: Ground, val person: Person? = null, val photo: androidx.compose.ui.graphics.ImageBitmap? = null) : OverlayContent
    }
    private val covering get() = content.value is OverlayContent.Cover

    fun showCover() {
        detach()
        content.value = OverlayContent.Cover(Grounds.of(app.prefs.card.value.label))
        attach(fullScreen = true)
        scheduleHide(COVER_MAX_MS) { cancelSwap() }
    }

    fun hideCover() { if (covering) detach() }

    /** The iPhone's card arrived mid-swap: show it on the cover now (DapTap comes forward once the swap ends). */
    fun showCoverCard(person: Person, photo: ByteArray?): Boolean {
        val c = content.value as? OverlayContent.Cover ?: return false
        val bitmap = photo?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        content.value = c.copy(person = person, photo = bitmap)
        return true
    }

    fun show(phase: TapEngine.Phase) {
        if (covering) return
        val ground = Grounds.of(app.prefs.card.value.label)
        content.value = OverlayContent.Tap(phase, ground, System.nanoTime())
        attach()
        scheduleHide(if (phase is TapEngine.Phase.Sent) 4_500 else 6_000)
    }

    fun showIncoming(person: Person) {
        if (covering) return // DapTap itself comes forward with the card
        content.value = OverlayContent.Card(person, Grounds.of(person.card.label))
        attach()
        scheduleHide(7_000)
    }

    private fun scheduleHide(ms: Long, then: () -> Unit = {}) {
        hideJob?.cancel()
        hideJob = app.scope.launch { delay(ms); hideJob = null; detach(); then() }
    }

    private fun attach(fullScreen: Boolean = false) {
        if (view != null) return
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { if (fullScreen) Cover() else Banner() }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (fullScreen) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        runCatching { wm.addView(v, lp) }.onSuccess { view = v; owner = o }.onFailure { o.stop() }
    }

    private fun detach() {
        hideJob?.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
        content.value = null
    }

    /** Ends the swap in the engine (which brings DapTap back and takes the cover down), and opens DapTap. */
    private fun cancelSwap() {
        app.engine.cancelAppleLink()
        openApp()
    }

    private fun openApp() {
        detach()
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** DapTap's own screen over Quick Share during an iPhone swap. Tapping Cancel returns to DapTap. */
    @Composable
    private fun Cover() {
        val c = content.value as? OverlayContent.Cover ?: return
        val ground = c.ground
        val reduce = remember { reducedMotion(app) }
        val person0 = c.person
        Box(Modifier.fillMaxSize().walletGround().clickable(enabled = false) {}) {
            com.keshav.daptap.ui.GlassPass(
                if (person0 != null) com.keshav.daptap.ui.Grounds.of(person0.card.label) else ground,
                Modifier.align(androidx.compose.ui.Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp),
                stubHeight = com.keshav.daptap.ui.STUB_HEIGHT,
                // Kept cheap while the swap runs (no live blur, no looping light): DapTap is behind Quick Share
                // then, and every frame it draws competes with the exchange for CPU.
                live = false,
            ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
                val person = c.person
                com.keshav.daptap.ui.PassHead("NAMEDROP", "IPHONE")
                Spacer(Modifier.height(20.dp))
                if (person == null) {
                    CellName("SWAP", com.keshav.daptap.ui.Wallet.ink, maxCell = 14.dp, origin = Offset(0f, 0.5f))
                    Spacer(Modifier.height(20.dp))
                    Text("Swapping with the iPhone", color = com.keshav.daptap.ui.Wallet.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Keep the phones close.", color = com.keshav.daptap.ui.Wallet.inkSoft, fontSize = 16.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.height(com.keshav.daptap.ui.STUB_HEIGHT), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                        Column {
                            Text("WAITING FOR THEM", style = com.keshav.daptap.ui.Wallet.tabCaps.copy(color = com.keshav.daptap.ui.Wallet.ink, fontSize = 13.sp))
                            Text("Ask them to tap Share on their iPhone", color = com.keshav.daptap.ui.Wallet.inkSoft, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    c.photo?.let { img ->
                        androidx.compose.foundation.Image(img, null, Modifier.height(120.dp).aspectRatio(1f).clip(RoundedCornerShape(24.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Spacer(Modifier.height(20.dp))
                    }
                    val first = person.card.firstName.ifBlank { person.card.name }.ifBlank { "GOT IT" }
                    CellName(first.uppercase(), com.keshav.daptap.ui.Wallet.ink, maxCell = 14.dp, origin = Offset(0f, 0.5f))
                    Spacer(Modifier.height(20.dp))
                    Text(if (person.card.name.isBlank()) "Got their card" else "Got ${person.card.name}'s card",
                        color = com.keshav.daptap.ui.Wallet.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(24.dp))
                    androidx.compose.foundation.layout.Row(Modifier.height(com.keshav.daptap.ui.STUB_HEIGHT).fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FINISHING THE SWAP", style = com.keshav.daptap.ui.Wallet.tabCaps.copy(color = com.keshav.daptap.ui.Wallet.ink, fontSize = 13.sp))
                            Text("Sending yours back…", color = com.keshav.daptap.ui.Wallet.inkSoft, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        com.keshav.daptap.ui.Stamp("RECEIVED")
                    }
                }
            }
            }
            Text("Cancel", color = com.keshav.daptap.ui.Wallet.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding()
                    .padding(bottom = 28.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = ::cancelSwap)
                    .padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }

    @Composable
    private fun Banner() {
        val c = content.value ?: return
        val ground = when (c) { is OverlayContent.Tap -> c.ground; is OverlayContent.Card -> c.ground; is OverlayContent.Cover -> return }
        val reduce = remember { reducedMotion(app) }
        val wave = remember { Animatable(0f) }
        val build = remember { Animatable(if (reduce) 1f else 0f) }
        LaunchedEffect(c) {
            if (reduce) return@LaunchedEffect
            launch { wave.snapTo(0f); wave.animateTo(1f, tween(1100, easing = LinearEasing)) }
            build.snapTo(0f)
            build.animateTo(1f, tween(900, delayMillis = 250, easing = FastOutSlowInEasing))
        }
        // Over another app the backdrop is unknown, so the glass sits on its own navy plate.
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp)
                .clip(RoundedCornerShape(22.dp)).background(com.keshav.daptap.ui.Wallet.ground)
                .clickable(onClick = ::openApp)
        ) {
            com.keshav.daptap.ui.GlassPass(ground, Modifier.matchParentSize(), corner = 22.dp,
                shine = if (reduce) -1f else wave.value) {}
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                val (word, title, body) = when (c) {
                    is OverlayContent.Card -> Triple(c.person.card.firstName.ifBlank { c.person.card.name },
                        "${c.person.card.name} shared their card", "Tap to save them")
                    is OverlayContent.Cover -> return@Column
                    is OverlayContent.Tap -> when (val p = c.phase) {
                        is TapEngine.Phase.Sent -> when {
                            p.airDrop -> Triple("SENT", "Your card is on its way", "They'll see an AirDrop request. Pick their iPhone in Quick Share if asked.")
                            p.peer == TapEngine.Peer.ANDROID -> Triple("SWAP", "Cards swapped", "Tap to see theirs")
                            else -> Triple("SENT", "Your card was read", "Tap to open DapTap")
                        }
                        else -> Triple("DAP", "Hold steady", "Sharing your card…")
                    }
                }
                CellName(word, com.keshav.daptap.ui.Wallet.ink, maxCell = 9.dp, progress = build.value, origin = Offset(0.5f, 1f))
                Spacer(Modifier.height(14.dp))
                Text(title, color = com.keshav.daptap.ui.Wallet.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = com.keshav.daptap.ui.Wallet.inkSoft, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }

    private companion object {
        /** Give up covering after this long (the iPhone never tapped Share) and return to DapTap. */
        const val COVER_MAX_MS = 75_000L
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() {
            saved.performRestore(Bundle())
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
