package com.keshav.daptap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.ContactCard

/** Edit your own card. A live strip at the top shows the name exactly as the grid will build it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardScreen(
    initial: ContactCard,
    photo: ImageBitmap?,
    firstRun: Boolean,
    /** Pull the phone's own "My profile" card in as soon as the screen opens (first run). */
    autoFillFromProfile: Boolean = false,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onFillFromProfile: (apply: (ContactCard) -> Unit) -> Unit,
    onSave: (ContactCard) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var email by rememberSaveable { mutableStateOf(initial.email) }
    var company by rememberSaveable { mutableStateOf(initial.company) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var website by rememberSaveable { mutableStateOf(initial.website) }
    var instagram by rememberSaveable { mutableStateOf(initial.instagram) }
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var tried by rememberSaveable { mutableStateOf(false) }

    val draft = ContactCard(name, phone, email, company, title, website, instagram, label)
    val nameError = tried && name.isBlank()
    val canLeave = !firstRun
    if (canLeave) BackHandler(onBack = onCancel)
    val applyProfile: (ContactCard) -> Unit = { p ->
        if (name.isBlank()) name = p.name
        if (phone.isBlank()) phone = p.phone
        if (email.isBlank()) email = p.email
        if (company.isBlank()) company = p.company
        if (title.isBlank()) title = p.title
        if (website.isBlank()) website = p.website
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (autoFillFromProfile) onFillFromProfile(applyProfile) }

    // Reads the field states at click time. (Capturing `draft` here would freeze the first
    // composition's values inside the memoized click lambda and save an empty card.)
    val save: () -> Unit = {
        tried = true
        if (name.isNotBlank()) onSave(ContactCard(name, phone, email, company, title, website, instagram, label).sanitized())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (firstRun) "Make your card" else "Your card") },
                navigationIcon = {
                    if (canLeave) IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = { TextButton(onClick = save) { Text("Save", fontWeight = FontWeight.SemiBold) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            val ground = Grounds.of(label)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ground.color)
            ) {
                // Grid, padding, photo and letters all step in the same cell so the preview registers like the poster.
                val cell = 8.dp
                ConstructionGrid(cell, ground.line, Modifier.matchParentSize())
                Row(Modifier.padding(cell * 2), verticalAlignment = Alignment.Top) {
                    PhotoTile(photo, draft, ground, cell * 7, Modifier.clickable(
                        onClickLabel = if (photo == null) "Add photo" else "Change photo", role = Role.Button,
                        onClick = onPickPhoto,
                    ))
                    Spacer(Modifier.width(cell * 2))
                    Column(Modifier.weight(1f)) {
                        CellName(draft.firstName.ifBlank { "YOU" }, ground.ink, maxCell = cell, origin = Offset(0f, 0.5f))
                        Spacer(Modifier.height(cell))
                        Text(
                            draft.restOfName().ifBlank { draft.roleLine.ifBlank { "Your name, as they'll see it" } },
                            color = ground.inkSoft, fontSize = 14.sp, maxLines = 1,
                        )
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPickPhoto) { Text(if (photo == null) "Add photo" else "Change photo") }
                if (photo != null) TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onFillFromProfile(applyProfile) }) { Text("Fill from my profile") }
            }

            Text("Card colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Grounds.all.forEachIndexed { i, g ->
                    val sel = i == label
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(g.color)
                            .border(
                                if (sel) 3.dp else 1.dp,
                                if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                            .clickable(role = Role.RadioButton) { label = i }
                            .semantics { contentDescription = g.name; selected = sel },
                        contentAlignment = Alignment.Center,
                    ) { if (sel) Icon(Icons.Outlined.Check, null, tint = g.ink) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Field(name, { name = it }, "Name", KeyboardType.Text, KeyboardCapitalization.Words,
                isError = nameError, support = if (nameError) "Add your name so people know whose card it is" else null)
            Field(phone, { phone = it }, "Phone", KeyboardType.Phone)
            Field(email, { email = it }, "Email", KeyboardType.Email)
            Field(company, { company = it }, "Company or school", KeyboardType.Text, KeyboardCapitalization.Words)
            Field(title, { title = it }, "Title or major", KeyboardType.Text, KeyboardCapitalization.Sentences)
            Field(instagram, { instagram = it.removePrefix("@") }, "Instagram", KeyboardType.Ascii, prefix = "@")
            Field(website, { website = it }, "Website or LinkedIn", KeyboardType.Uri, imeAction = ImeAction.Done)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    caps: KeyboardCapitalization = KeyboardCapitalization.None,
    isError: Boolean = false,
    support: String? = null,
    prefix: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        prefix = prefix?.let { { Text(it) } },
        supportingText = support?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboard, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
