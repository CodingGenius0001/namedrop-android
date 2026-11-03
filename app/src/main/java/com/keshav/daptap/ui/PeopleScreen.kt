package com.keshav.daptap.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.data.Person

/** One cell of the People strips: names and the grid behind them share it, so every name registers. */
private val STRIP_CELL = 7.dp

/**
 * Everyone whose card you've received, newest first. Each person is a strip of their own ground with their
 * first name built in cells, a run of small posters rather than a contact list.
 */
@Composable
fun PeopleScreen(
    people: List<Person>,
    photoOf: (Person) -> ByteArray?,
    contentPadding: PaddingValues,
    onOpen: (Person) -> Unit,
) {
    Scaffold(
        topBar = { GroundTopBar("People") },
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
    ) { padding ->
        if (people.isEmpty()) {
            EmptyPeople(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(people, key = { it.id }) { p -> PersonStrip(p, rememberBitmap(photoOf(p)), onOpen) }
        }
    }
}

@Composable
private fun PersonStrip(p: Person, photo: androidx.compose.ui.graphics.ImageBitmap?, onOpen: (Person) -> Unit) {
    val ground = Grounds.of(p.card.label)
    val cell = STRIP_CELL
    val ago = DateUtils.getRelativeTimeSpanString(p.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val how = when (p.via) {
        "namedrop" -> "NameDrop"
        "airdrop" -> "AirDrop"
        "web" -> "Web card"
        "share" -> "Shared"
        "link" -> "Link"
        else -> "Tap"
    }
    Box(
        Modifier.fillMaxWidth().background(ground.color)
            .clickable(onClickLabel = "Open ${p.card.firstName.ifBlank { "card" }}", role = Role.Button) { onOpen(p) }
    ) {
        ConstructionGrid(cell, ground.line, Modifier.matchParentSize())
        // A hairline of the raised ground separates neighbours that share a colour.
        Box(Modifier.fillMaxWidth().height(1.dp).background(ground.raised))
        Row(
            Modifier.padding(start = cell * 2, end = cell * 2, top = cell * 2, bottom = cell * 2),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                val first = p.card.firstName.ifBlank { p.card.name.ifBlank { "?" } }
                CellName(first, ground.ink, maxCell = cell, origin = Offset(0f, 0.5f))
                Spacer(Modifier.height(cell))
                val rest = p.card.restOfName()
                if (rest.isNotEmpty()) {
                    Text(rest, color = ground.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOf(p.card.roleLine, "$how · $ago").filter { it.isNotBlank() }.joinToString("\n"),
                    color = ground.inkSoft, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (photo != null || p.saved) {
                Spacer(Modifier.width(cell * 2))
                Column(horizontalAlignment = Alignment.End) {
                    if (photo != null) PhotoTile(photo, p.card, ground, cell * 8)
                    if (p.saved) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Saved to Contacts", tint = ground.ink,
                            modifier = Modifier.padding(top = if (photo != null) cell else 0.dp).size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPeople(modifier: Modifier) {
    val ground = LocalGround.current
    val cell = 8.dp
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(cell * 15).clip(RoundedCornerShape(20.dp)).background(ground.color)) {
            ConstructionGrid(cell, ground.line, Modifier.matchParentSize())
            CellName("HELLO", ground.ink, maxCell = cell, modifier = Modifier.padding(start = cell * 2, top = cell * 4))
        }
        Text("No one yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Cards you get from a tap, an AirDrop or a web reply land here, so you can save them later.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
