package com.keshav.daptap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.tap.TapEngine
import kotlinx.coroutines.launch

enum class NfcState { ON, OFF, MISSING }

/** Exponential-style ease-out: most of the distance in the first moment, like a burst. */
private val BurstOut = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.7f, 0.35f, 1f)

/**
 * Home: your contact pass. While phones touch, light sweeps across the glass and the pass gives a little; when
 * the card goes, a stamp lands on the stub, and when the next tap is ready the old stub tears away.
 */
@Composable
fun CardScreen(
    card: ContactCard,
    photo: ImageBitmap?,
    phase: TapEngine.Phase,
    nfc: NfcState,
    tapEnabled: Boolean,
    contentPadding: PaddingValues,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onFixNfc: () -> Unit,
    onEnableTap: () -> Unit,
    onAddPhoto: () -> Unit,
    /** "Najib · 10:00" for the newest person in People, or null. */
    lastSwap: String? = null,
    /** How many people are in the wallet (older passes peek out under yours). */
    peopleCount: Int = 0,
) {
    val ground = LocalGround.current
    val reduce = reducedMotion(LocalContext.current)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

    val shine = remember { Animatable(-1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val build = remember { Animatable(1f) }
    val touching = phase is TapEngine.Phase.Touching
    LaunchedEffect(touching) {
        if (!touching || reduce) { shine.snapTo(-1f); return@LaunchedEffect }
        // The first sweep bursts across from where the phones met; the ones after keep a steady pulse.
        var first = true
        while (true) {
            shine.snapTo(0f)
            shine.animateTo(1f, if (first) tween(800, easing = BurstOut) else tween(1200, easing = LinearEasing))
            first = false
        }
    }
    // The pass gives a little under the other phone, then springs back when the card goes.
    val press by animateFloatAsState(
        if (touching && !reduce) 0.975f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 420f), label = "press",
    )
    LaunchedEffect(phase) {
        if (phase is TapEngine.Phase.Sent && !reduce) {
            build.snapTo(0f)
            build.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Poster(card, photo, ground, topInset = top, bottomInset = contentPadding.calculateBottomPadding() + 80.dp,
            nameProgress = build.value, wave = shine.value, onAddPhoto = onAddPhoto,
            stack = minOf(peopleCount, 2),
            headRight = "NO. %04d".format(peopleCount + 1),
            stub = {
                Stub(phase, nfc, tapEnabled, lastSwap, onFixNfc, onEnableTap, reduce, onTear = {
                    // The stub going: one sweep of light along the glass edge.
                    scope.launch { shine.snapTo(0f); shine.animateTo(1f, tween(520, easing = BurstOut)); shine.snapTo(-1f) }
                })
            },
            passModifier = Modifier.graphicsLayer {
                scaleX = press; scaleY = press
                transformOrigin = TransformOrigin(TapOrigin.x, TapOrigin.y)
            })

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DapTap", color = Wallet.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send my card with Quick Share", tint = Wallet.ink)
            }
        }

        Button(
            onClick = onEdit,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp, start = 16.dp, end = 16.dp)
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Wallet.ink, contentColor = Wallet.navyTop),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Edit card", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** What the stub says: a tab-caps head, a plain line under it, and the stamp a finished swap leaves. */
private data class StubText(val head: String, val line: String, val stamp: String?, val action: (() -> Unit)?, val armed: Boolean)

private fun stubText(
    phase: TapEngine.Phase, nfc: NfcState, tapEnabled: Boolean, lastSwap: String?,
    onFixNfc: () -> Unit, onEnableTap: () -> Unit,
): StubText = when {
    nfc == NfcState.MISSING -> StubText("NO NFC", "This phone can't tap to share", null, null, false)
    nfc == NfcState.OFF -> StubText("NFC IS OFF", "Tap here to turn it on", null, onFixNfc, false)
    !tapEnabled -> StubText("TAP SHARING IS OFF", "Tap here to turn it on", null, onEnableTap, false)
    phase is TapEngine.Phase.Touching -> StubText("HOLD STEADY", "Keep the phones together", null, null, true)
    phase is TapEngine.Phase.Sent && phase.airDrop -> StubText("ON ITS WAY", "They tap Accept on their iPhone", "SENT", null, true)
    phase is TapEngine.Phase.Sent && phase.peer == TapEngine.Peer.ANDROID -> StubText("CARDS SWAPPED", "You have each other's cards", "SWAPPED", null, true)
    phase is TapEngine.Phase.Sent -> StubText("CARD READ", "Your card went to their phone", "SENT", null, true)
    else -> StubText("READY TO TAP", lastSwap?.let { "Last swap: $it" } ?: "Hold phones back to back", null, null, true)
}

/**
 * The tear-off stub. A stamp slams onto it when a swap lands; when the next tap is ready the stamped stub
 * tears away (drops, tilts and fades) and a fresh one takes its place.
 */
@Composable
private fun Stub(
    phase: TapEngine.Phase, nfc: NfcState, tapEnabled: Boolean, lastSwap: String?,
    onFixNfc: () -> Unit, onEnableTap: () -> Unit, reduce: Boolean, onTear: () -> Unit,
) {
    val want = stubText(phase, nfc, tapEnabled, lastSwap, onFixNfc, onEnableTap)
    var shown by remember { mutableStateOf(want) }
    val tear = remember { Animatable(0f) }
    val slam = remember { Animatable(1f) }
    // Keyed on what the stub says (StubText carries lambdas, which differ on every composition).
    LaunchedEffect(want.head, want.line, want.stamp) {
        val tearing = shown.stamp != null && want.stamp == null && !reduce
        if (tearing) {
            onTear()
            tear.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            shown = want
            tear.snapTo(-0.4f)
            tear.animateTo(0f, tween(260, easing = BurstOut))
        } else {
            // A tap that lands mid-tear must not leave the stub half gone.
            tear.snapTo(0f)
            val newStamp = want.stamp != null && want.stamp != shown.stamp
            shown = want
            if (newStamp && !reduce) {
                slam.snapTo(0f)
                slam.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
            }
        }
    }
    val t = tear.value
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 48.dp)
            .let { m -> want.action?.let { a -> m.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = a) } ?: m }
            .graphicsLayer {
                // Tearing away: the stub drops and tilts from its left end. Arriving (t < 0): it rises into place.
                translationY = t * 36.dp.toPx()
                rotationZ = if (t > 0f) t * 5f else 0f
                alpha = 1f - kotlin.math.abs(t).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !shown.armed -> StateDot(Wallet.inkSoft)
                    phase is TapEngine.Phase.Touching && !reduce -> PulsingDot(Wallet.jet)
                    else -> StateDot(Wallet.jet)
                }
                Spacer(Modifier.width(8.dp))
                Text(shown.head, style = Wallet.tabCaps.copy(color = Wallet.ink, fontSize = 13.sp))
            }
            Text(shown.line, color = Wallet.inkSoft, fontSize = 15.sp, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp))
        }
        shown.stamp?.let { s ->
            Stamp(s, Modifier.graphicsLayer {
                val k = slam.value
                scaleX = 1.8f - 0.8f * k; scaleY = scaleX
                alpha = k.coerceIn(0f, 1f)
            })
        }
    }
}

/**
 * The dot breathes only while phones are touching. It lives in its own composable so its animation clock
 * exists only then: an infinite transition left running wakes the CPU on every frame even when unread.
 */
@Composable
private fun PulsingDot(color: androidx.compose.ui.graphics.Color) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
    StateDot(color, Modifier.alpha(a))
}
