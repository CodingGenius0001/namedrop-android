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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExtendedFloatingActionButton
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

enum class NfcState { ON, OFF, MISSING }

/** Text on an accent fill (vermilion or yellow): about 6:1 on both. */
private val LiveInk = androidx.compose.ui.graphics.Color(0xFF16161A)

/** Home: your card as a poster. Bring phones together and the grid ripples, then the status says what happened. */
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
) {
    val ground = LocalGround.current
    val reduce = reducedMotion(LocalContext.current)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

    val wave = remember { Animatable(-1f) }
    val build = remember { Animatable(1f) }
    val touching = phase is TapEngine.Phase.Touching
    LaunchedEffect(touching) {
        if (!touching || reduce) { wave.snapTo(-1f); return@LaunchedEffect }
        while (true) { wave.snapTo(0f); wave.animateTo(1f, tween(1100, easing = LinearEasing)) }
    }
    LaunchedEffect(phase) {
        if (phase is TapEngine.Phase.Sent && !reduce) {
            build.snapTo(0f)
            build.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Poster(card, photo, ground, topInset = top, bottomInset = contentPadding.calculateBottomPadding() + 72.dp,
            nameProgress = build.value, wave = wave.value, onAddPhoto = onAddPhoto)

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DapTap", color = ground.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send my card with Quick Share", tint = ground.ink)
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusChip(phase, nfc, tapEnabled, onFixNfc, onEnableTap, Modifier.weight(1f))
            // At large font sizes the label would squeeze the status out, so the button folds to its icon.
            val roomy = LocalDensity.current.fontScale < 1.15f
            ExtendedFloatingActionButton(
                onClick = onEdit,
                expanded = roomy,
                containerColor = ground.ink, contentColor = ground.color,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = if (roomy) null else "Edit card") },
                text = { Text("Edit card", fontWeight = FontWeight.SemiBold) },
            )
        }
    }
}

@Composable
private fun StatusChip(
    phase: TapEngine.Phase,
    nfc: NfcState,
    tapEnabled: Boolean,
    onFixNfc: () -> Unit,
    onEnableTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ground = LocalGround.current
    val (text, action) = when {
        nfc == NfcState.MISSING -> "This phone has no NFC" to null
        nfc == NfcState.OFF -> "NFC is off. Turn it on" to onFixNfc
        !tapEnabled -> "Tap sharing is off. Turn on" to onEnableTap
        phase is TapEngine.Phase.Touching -> "Hold steady…" to null
        phase is TapEngine.Phase.Sent && phase.airDrop -> "Sent. They tap Accept" to null
        phase is TapEngine.Phase.Sent && phase.peer == TapEngine.Peer.ANDROID -> "Cards swapped" to null
        phase is TapEngine.Phase.Sent -> "Your card was read" to null
        else -> "Ready. Hold phones back to back" to null
    }
    val armed = nfc == NfcState.ON && tapEnabled
    // While a tap is live the whole chip becomes the accent: the one place the second colour appears.
    val live = armed && phase !is TapEngine.Phase.Ready
    val fill by animateColorAsState(if (live) ground.accent else ground.raised, tween(180), label = "fill")
    val ink by animateColorAsState(if (live) LiveInk else ground.ink, tween(180), label = "ink")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
    Surface(
        modifier = modifier.heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }
            .let { if (action != null) it.clickable(onClick = action) else it },
        shape = RoundedCornerShape(24.dp),
        color = fill,
        contentColor = ink,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                phase is TapEngine.Phase.Sent && armed ->
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                else -> StateDot(
                    when { live -> ink; armed -> ground.accent; else -> ground.inkSoft },
                    Modifier.alpha(if (phase is TapEngine.Phase.Touching) a else 1f),
                )
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(text, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "status") {
                Text(it, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            }
        }
    }
}
