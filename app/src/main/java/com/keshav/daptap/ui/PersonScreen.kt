package com.keshav.daptap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import kotlinx.coroutines.launch

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
    /** Names a card that came without one. */
    val onRename: ((String) -> Unit)? = null,
)

/**
 * Someone else's card. Arriving from a tap it builds its name cell by cell from the middle of the grid,
 * on their own ground colour; from People it simply appears.
 */
@Composable
fun PersonScreen(card: ContactCard, photo: ImageBitmap?, title: String, animate: Boolean, actions: PersonActions) {
    val ground = Grounds.of(card.label)
    val reduce = reducedMotion(LocalContext.current)
    val motion = animate && !reduce
    val build = remember(card) { Animatable(if (motion) 0f else 1f) }
    // The tap ripples out from where the phones met, and their name assembles behind it.
    val wave = remember(card) { Animatable(if (motion) 0f else -1f) }
    LaunchedEffect(card) {
        if (wave.value >= 0f) launch {
            wave.animateTo(1f, tween(1100, easing = LinearEasing))
            wave.snapTo(-1f)
        }
        if (build.value < 1f) build.animateTo(1f, tween(1000, delayMillis = 120, easing = FastOutSlowInEasing))
    }
    BackHandler(onBack = actions.onClose)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        val nameless = card.name.isBlank()
        var naming by remember(card) { mutableStateOf(false) }
        Poster(card, photo, ground, topInset = top, bottomInset = bottom + if (nameless) 200.dp else 140.dp,
            nameProgress = build.value, wave = wave.value, placeholder = "HELLO")
        if (naming) NameDialog(onDone = { n -> naming = false; if (n.isNotBlank()) actions.onRename?.invoke(n) }, onDismiss = { naming = false })

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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (nameless && actions.onRename != null) {
                // An iPhone can share just a number and photo; let them put a name to it before saving.
                OutlinedButton(
                    onClick = { naming = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    border = BorderStroke(1.dp, ground.inkSoft),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ground.ink),
                ) {
                    Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp))
                    Text("Add their name", fontSize = 16.sp)
                }
            }
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
                    actions.saved -> {
                        Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp))
                        Text("Saved to Contacts", fontSize = 16.sp)
                    }
                    else -> {
                        Icon(Icons.Outlined.PersonAddAlt, null); Spacer(Modifier.width(8.dp))
                        Text("Save ${card.firstName.ifBlank { "contact" }}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
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
                            actions.sharedBack -> "They have your card too"
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

@Composable
private fun NameDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Their name") },
        text = {
            Column {
                Text("Their iPhone shared a number and photo but no name.", fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
