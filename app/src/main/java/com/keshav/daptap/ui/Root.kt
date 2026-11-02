package com.keshav.daptap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.keshav.daptap.BuildConfig
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.a11y.QuickSharePickerService
import com.keshav.daptap.contacts.ContactsWriter
import com.keshav.daptap.contacts.ProfileReader
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.data.Person
import com.keshav.daptap.data.Photos
import com.keshav.daptap.nfc.TapService
import com.keshav.daptap.share.QuickShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { CARD, PEOPLE, SETTINGS }

@Composable
fun DapTapRoot(app: DapTapApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val card by app.prefs.card.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val photoVersion by app.prefs.photoVersion.collectAsState()
    val people by app.history.people.collectAsState()
    val incoming by app.engine.incoming.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val log by app.engine.log.collectAsState()

    var myPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(photoVersion) { myPhotoBytes = withContext(Dispatchers.IO) { Photos.readMine(app) } }
    val myPhoto = rememberBitmap(myPhotoBytes)

    var tab by rememberSaveable { mutableStateOf(Tab.CARD) }
    var editing by rememberSaveable { mutableStateOf(!settings.onboarded || card.name.isBlank()) }
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var sharingBack by remember { mutableStateOf(false) }

    // System state, refreshed whenever we come back to the foreground (user may have toggled NFC etc.).
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val status = remember(resumeTick) { readStatus(context) }

    LaunchedEffect(Unit) { app.engine.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(settings.tapEnabled) { setTapServiceEnabled(context, settings.tapEnabled) }

    // ---- launchers
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Photos.importMine(app, uri) }
            if (ok) app.prefs.photoChanged() else snackbar.showSnackbar("Couldn't use that photo. Try another one.")
        }
    }
    var pendingProfileApply by remember { mutableStateOf<((ContactCard) -> Unit)?>(null) }
    fun fillFromProfile(apply: (ContactCard) -> Unit) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { ProfileReader.read(app) }
            if (p != null) apply(p) else snackbar.showSnackbar("Your phone has no \"My profile\" card to copy from.")
        }
    }
    val readContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val apply = pendingProfileApply
        pendingProfileApply = null
        if (granted && apply != null) fillFromProfile(apply)
        else if (!granted) scope.launch { snackbar.showSnackbar("DapTap needs Contacts access to read your profile.") }
    }
    var pendingSave by remember { mutableStateOf<Pair<Person, ByteArray?>?>(null) }
    fun save(person: Person, photo: ByteArray?) {
        saving = true
        scope.launch {
            val uri = withContext(Dispatchers.IO) { ContactsWriter.insert(app, person.card, photo) }
            saving = false
            if (uri != null) {
                app.engine.markSaved(person)
                val r = snackbar.showSnackbar("${person.card.firstName.ifBlank { "Contact" }} saved to Contacts", "Open")
                if (r == SnackbarResult.ActionPerformed) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } else {
                runCatching { context.startActivity(ContactsWriter.insertIntent(person.card, photo)) }
                app.engine.markSaved(person)
            }
        }
    }
    val writeContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (person, photo) = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (granted) save(person, photo) else {
            // No permission: hand off to the Contacts app's own editor, pre-filled.
            runCatching { context.startActivity(ContactsWriter.insertIntent(person.card, photo)) }
            app.engine.markSaved(person)
        }
    }
    fun requestSave(person: Person, photo: ByteArray?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) save(person, photo)
        else { pendingSave = person to photo; writeContacts.launch(Manifest.permission.WRITE_CONTACTS) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    LaunchedEffect(settings.onboarded) {
        if (settings.onboarded && !status.notificationsAllowed && Build.VERSION.SDK_INT >= 33) requestNotifications()
    }

    DapTapTheme(Grounds.of(card.label)) {
        val inc = incoming
        val viewing = viewingId?.let { id -> people.firstOrNull { it.id == id } }
        val drenched = inc != null || (!editing && (viewing != null || tab == Tab.CARD))
        SystemBarIcons(lightIcons = drenched || androidx.compose.foundation.isSystemInDarkTheme())
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = when {
                    inc != null -> "incoming:" + inc.person.id
                    editing -> "edit"
                    viewing != null -> "person:" + viewing.id
                    else -> "tabs"
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "screen",
            ) { screen ->
                when {
                    screen.startsWith("incoming") && inc != null -> PersonScreen(
                        card = inc.person.card,
                        photo = rememberBitmap(inc.photo),
                        title = if (inc.person.via == "airdrop") "AirDropped card" else "New card",
                        animate = true,
                        actions = PersonActions(
                            saved = inc.person.saved,
                            saving = saving,
                            onSave = { requestSave(inc.person, inc.photo) },
                            onShareBack = if (inc.replyTo != null && !inc.sharedBack) ({
                                sharingBack = true
                                scope.launch {
                                    val ok = app.engine.shareBack()
                                    sharingBack = false
                                    snackbar.showSnackbar(if (ok) "Sent your card to ${inc.person.card.firstName}" else "Couldn't reach them. Check your connection and try again.")
                                }
                            }) else null,
                            sharingBack = sharingBack,
                            sharedBack = inc.sharedBack,
                            onClose = { app.engine.dismissIncoming() },
                        ),
                    )
                    screen == "edit" -> EditCardScreen(
                        initial = card,
                        photo = myPhoto,
                        firstRun = !settings.onboarded || card.name.isBlank(),
                        onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemovePhoto = { Photos.deleteMine(app); app.prefs.photoChanged() },
                        onFillFromProfile = { apply ->
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) fillFromProfile(apply)
                            else { pendingProfileApply = apply; readContacts.launch(Manifest.permission.READ_CONTACTS) }
                        },
                        onSave = { c ->
                            app.prefs.saveCard(c)
                            app.prefs.updateSettings { it.copy(onboarded = true) }
                            editing = false
                            tab = Tab.CARD
                        },
                        onCancel = { editing = false },
                    )
                    screen.startsWith("person") && viewing != null -> {
                        var bytes by remember(viewing.id) { mutableStateOf<ByteArray?>(null) }
                        LaunchedEffect(viewing.id) { bytes = withContext(Dispatchers.IO) { app.history.photoBytes(viewing) } }
                        PersonScreen(
                            card = viewing.card, photo = rememberBitmap(bytes), title = "People", animate = false,
                            actions = PersonActions(
                                saved = viewing.saved, saving = saving,
                                onSave = { requestSave(viewing, bytes) },
                                onShareBack = null,
                                onDelete = { app.history.remove(viewing.id); viewingId = null },
                                onClose = { viewingId = null },
                            ),
                        )
                    }
                    else -> Tabs(
                        tab = tab, onTab = { tab = it },
                        card = { padding ->
                            CardScreen(
                                card = card, photo = myPhoto, phase = phase, nfc = status.nfc, tapEnabled = settings.tapEnabled,
                                contentPadding = padding,
                                onEdit = { editing = true },
                                onSend = {
                                    if (!app.engine.sendWithQuickShare()) scope.launch { snackbar.showSnackbar("Couldn't open Quick Share.") }
                                },
                                onFixNfc = { context.openSettings(Settings.ACTION_NFC_SETTINGS) },
                                onEnableTap = { app.prefs.updateSettings { it.copy(tapEnabled = true) } },
                            )
                        },
                        people = { padding ->
                            PeopleScreen(people, { app.history.photoBytes(it) }, padding) { viewingId = it.id }
                        },
                        settings = { padding ->
                            SettingsScreen(
                                settings, status, log, padding,
                                SettingsActions(
                                    update = { app.prefs.updateSettings(it) },
                                    openNfc = { context.openSettings(Settings.ACTION_NFC_SETTINGS) },
                                    openOverlay = {
                                        context.startActivitySafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + context.packageName)))
                                    },
                                    openAccessibility = { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                                    requestNotifications = ::requestNotifications,
                                    copyLog = {
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(ClipData.newPlainText("DapTap log", log.joinToString("\n")))
                                    },
                                    clearLog = { app.engine.clearLog() },
                                    openSource = { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CodingGenius0001/namedrop-android"))) },
                                ),
                            )
                        },
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

@Composable
private fun Tabs(
    tab: Tab,
    onTab: (Tab) -> Unit,
    card: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
    people: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
    settings: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val ground = LocalGround.current
    val onCard = tab == Tab.CARD
    BackHandler(enabled = !onCard) { onTab(Tab.CARD) }
    Scaffold(
        containerColor = if (onCard) ground.color else MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = if (onCard) ground.raised else MaterialTheme.colorScheme.surfaceContainer) {
                val colors = if (onCard) NavigationBarItemDefaults.colors(
                    selectedIconColor = ground.color, selectedTextColor = ground.ink,
                    indicatorColor = ground.ink, unselectedIconColor = ground.inkSoft, unselectedTextColor = ground.inkSoft,
                ) else NavigationBarItemDefaults.colors()
                listOf(
                    Triple(Tab.CARD, "Card", Icons.Outlined.Badge),
                    Triple(Tab.PEOPLE, "People", Icons.Outlined.People),
                    Triple(Tab.SETTINGS, "Settings", Icons.Outlined.Settings),
                ).forEach { (t, label, icon) ->
                    NavigationBarItem(selected = tab == t, onClick = { onTab(t) }, icon = { Icon(icon, null) },
                        label = { Text(label) }, colors = colors)
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        when (tab) {
            Tab.CARD -> card(padding)
            Tab.PEOPLE -> Box(Modifier.padding(top = 0.dp)) { people(padding) }
            Tab.SETTINGS -> settings(padding)
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

private fun Context.openSettings(action: String) {
    val i = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    if (!startActivitySafe(i)) startActivitySafe(Intent(Settings.ACTION_SETTINGS))
}

private fun Context.startActivitySafe(i: Intent): Boolean =
    runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

private fun setTapServiceEnabled(context: Context, enabled: Boolean) {
    val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    runCatching {
        context.packageManager.setComponentEnabledSetting(ComponentName(context, TapService::class.java), state, PackageManager.DONT_KILL_APP)
    }
}

private fun readStatus(context: Context): SystemStatus {
    val adapter = NfcAdapter.getDefaultAdapter(context)
    val nfc = when { adapter == null -> NfcState.MISSING; adapter.isEnabled -> NfcState.ON; else -> NfcState.OFF }
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val a11y = enabledServices.split(':').any {
        ComponentName.unflattenFromString(it) == ComponentName(context, QuickSharePickerService::class.java)
    }
    val observe = if (Build.VERSION.SDK_INT >= 35 && adapter != null) runCatching { adapter.isObserveModeSupported }.getOrNull() else null
    return SystemStatus(
        nfc = nfc,
        overlayAllowed = Settings.canDrawOverlays(context),
        accessibilityOn = a11y,
        notificationsAllowed = com.keshav.daptap.Notifications.canPost(context),
        quickShare = QuickShare.findTarget(context)?.let { t ->
            if (t.component?.packageName == QuickShare.SAMSUNG_PACKAGE) "Samsung Quick Share (AirDrop-capable on Galaxy S26)" else "Google Quick Share"
        },
        observeModeSupported = observe,
        nameDropSupported = if (Build.VERSION.SDK_INT >= 37) com.keshav.daptap.nfc.NameDropBeacon.isSupported(adapter) else null,
        version = BuildConfig.VERSION_NAME,
    )
}

/** Light status/navigation icons on drenched grounds and in dark theme; dark icons on light surfaces. */
@Composable
private fun SystemBarIcons(lightIcons: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }
}
