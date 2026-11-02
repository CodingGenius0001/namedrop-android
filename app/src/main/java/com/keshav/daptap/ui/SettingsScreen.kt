package com.keshav.daptap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.data.Settings

/** Live system state the settings screen reports on (re-read every time the app resumes). */
data class SystemStatus(
    val nfc: NfcState,
    val overlayAllowed: Boolean,
    val accessibilityOn: Boolean,
    val notificationsAllowed: Boolean,
    val quickShare: String?,
    val observeModeSupported: Boolean?,
    /** Android 17 reader-mode annotations: can this phone emit the NameDrop frame? null = Android < 17. */
    val nameDropSupported: Boolean?,
    val version: String,
)

class SettingsActions(
    val update: ((Settings) -> Settings) -> Unit,
    val openNfc: () -> Unit,
    val openOverlay: () -> Unit,
    val openAccessibility: () -> Unit,
    val requestNotifications: () -> Unit,
    val copyLog: () -> Unit,
    val clearLog: () -> Unit,
    val openSource: () -> Unit,
    val updateStatus: String,
    val updateAction: Pair<String, () -> Unit>?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    status: SystemStatus,
    log: List<String>,
    contentPadding: PaddingValues,
    actions: SettingsActions,
) {
    Scaffold(
        topBar = { GroundTopBar("Settings") },
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Tapping phones")
            Toggle("Share when phones touch", "Your phone acts as an NFC card while it's unlocked.",
                settings.tapEnabled) { v -> actions.update { it.copy(tapEnabled = v) } }
            Status(
                "NFC",
                when (status.nfc) { NfcState.ON -> "On"; NfcState.OFF -> "Off. DapTap can't be tapped"; NfcState.MISSING -> "This phone has no NFC" },
                if (status.nfc == NfcState.OFF) "Turn on" to actions.openNfc else null,
            )

            Section("iPhones")
            Toggle("NameDrop handshake",
                when (status.nameDropSupported) {
                    true -> "While DapTap is open, your phone sends the same NFC frame an iPhone sends to start NameDrop, so the iPhone plays its own NameDrop animation."
                    false -> "This phone's NFC chip can't send custom polling frames, so iPhones won't play the NameDrop animation. Taps still work."
                    null -> "Needs Android 17 (One UI 9). Taps still work without it."
                },
                settings.nameDropBeacon, enabled = status.nameDropSupported == true) { v -> actions.update { it.copy(nameDropBeacon = v) } }
            Toggle("Send with AirDrop after a tap",
                "Opens Quick Share with your card so the iPhone gets a normal AirDrop request. Their AirDrop must be set to Everyone for 10 minutes.",
                settings.airDropOnTap) { v -> actions.update { it.copy(airDropOnTap = v) } }
            Status("Quick Share", status.quickShare ?: "Not found. DapTap will use the share sheet", null)
            Toggle("Pick their iPhone for me",
                if (status.accessibilityOn) "Taps the iPhone in Quick Share's list when it's the only one."
                else "Needs DapTap's accessibility helper. On sideloaded apps, allow restricted settings in App info first.",
                settings.autoPickIphone, enabled = settings.airDropOnTap) { v ->
                actions.update { it.copy(autoPickIphone = v) }
                if (v && !status.accessibilityOn) actions.openAccessibility()
            }
            Toggle("Web card for iPhones without AirDrop",
                "The tap also shows a banner on the iPhone that opens your card in Safari, where they can save it and send theirs back.",
                settings.webFallback) { v -> actions.update { it.copy(webFallback = v) } }

            Section("When DapTap is closed")
            Toggle("Show the tap over other apps", "Plays the grid ripple and tells you what happened.",
                settings.overlay) { v ->
                actions.update { it.copy(overlay = v) }
                if (v && !status.overlayAllowed) actions.openOverlay()
            }
            if (settings.overlay && !status.overlayAllowed) {
                Status("Display over other apps", "Not allowed yet", "Allow" to actions.openOverlay)
            }
            Status("Notifications", if (status.notificationsAllowed) "Allowed" else "Off. You won't hear about cards sent back",
                if (!status.notificationsAllowed) "Allow" to actions.requestNotifications else null)

            Section("Diagnostics")
            Status("Apple NFC frame detection", when (status.observeModeSupported) {
                true -> "Supported. Sees iPhones before they read the card"
                false -> "Not supported on this phone. Falls back to timing"
                null -> "Needs Android 15"
            }, null)
            Toggle("Observe Apple frames while open",
                "Experimental. Puts NFC in observe mode between polls so the iPhone's reply frames show up in the log. Turn off if taps stop working.",
                settings.observeFrames, enabled = status.observeModeSupported == true) { v -> actions.update { it.copy(observeFrames = v) } }
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tap log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = actions.copyLog, enabled = log.isNotEmpty()) { Text("Copy") }
                        TextButton(onClick = actions.clearLog, enabled = log.isNotEmpty()) { Text("Clear") }
                    }
                    if (log.isEmpty()) {
                        Text("Nothing yet. Tap another phone and every NFC exchange shows up here.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        SelectionContainer {
                            Text(log.takeLast(60).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }

            Section("About")
            Status("DapTap", "Version ${status.version}", "Source" to actions.openSource)
            Status("Updates", actions.updateStatus, actions.updateAction)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.heightIn(min = 56.dp),
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun Status(title: String, value: String, action: Pair<String, () -> Unit>?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = action?.let { (label, go) -> { TextButton(onClick = go) { Text(label) } } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}
