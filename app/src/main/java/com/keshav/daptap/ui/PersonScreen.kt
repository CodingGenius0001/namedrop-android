package com.keshav.daptap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.ContactCard

/** What can be done with a card on screen. */
data class PersonActions(
    val saved: Boolean,
    val saving: Boolean,
    val onSave: () -> Unit,
    /** null hides "Send mine back" (no mailbox, or already sent). */
    val onShareBack: (() -> Unit)?,
    val sharingBack: Boolean = false,
    val sharedBack: Boolean = false,
    val onDelete: (() -> Unit)? = null,
    val onClose: () -> Unit,
)

/**
 * Someone else's card. Arriving from a tap it builds its name cell by cell from the middle of the grid,
 * on their own ground colour; from People it simply appears.
 */
@Composable
fun PersonScreen(card: ContactCard, photo: ImageBitmap?, title: String, animate: Boolean, actions: PersonActions) {
    val ground = Grounds.of(card.label)
    val reduce = reducedMotion(LocalContext.current)
    val build = remember(card) { Animatable(if (animate && !reduce) 0f else 1f) }
    LaunchedEffect(card) {
        if (build.value < 1f) build.animateTo(1f, tween(1000, delayMillis = 120, easing = FastOutSlowInEasing))
    }
    BackHandler(onBack = actions.onClose)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Poster(card, photo, ground, topInset = top, bottomInset = bottom + 140.dp, nameProgress = build.value)

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onClose) { Icon(Icons.Outlined.Close, "Close", tint = ground.ink) }
            Text(title, color = ground.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            actions.onDelete?.let { del ->
                IconButton(onClick = del) { Icon(Icons.Outlined.DeleteOutline, "Remove from People", tint = ground.ink) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ground.color)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = actions.onSave,
                enabled = !actions.saved && !actions.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ground.ink, contentColor = ground.color,
                    disabledContainerColor = ground.raised, disabledContentColor = ground.ink,
                ),
            ) {
                when {
                    actions.saving -> CircularProgressIndicator(Modifier.size(20.dp), color = ground.color, strokeWidth = 2.dp)
                    actions.saved -> { Icon(Icons.Outlined.Check, null); Text("  Saved to Contacts", fontSize = 16.sp) }
                    else -> { Icon(Icons.Outlined.PersonAddAlt, null); Text("  Save ${card.firstName.ifBlank { "contact" }}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (actions.onShareBack != null || actions.sharedBack) {
                OutlinedButton(
                    onClick = { actions.onShareBack?.invoke() },
                    enabled = actions.onShareBack != null && !actions.sharingBack && !actions.sharedBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    border = BorderStroke(1.dp, ground.inkSoft),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ground.ink, disabledContentColor = ground.inkSoft),
                ) {
                    Text(
                        when {
                            actions.sharedBack -> "Your card was sent back"
                            actions.sharingBack -> "Sending…"
                            else -> "Send mine back"
                        },
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
