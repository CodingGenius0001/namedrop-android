package com.keshav.daptap.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keshav.daptap.data.Person

/** Everyone whose card you've received, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    people: List<Person>,
    photoOf: (Person) -> ByteArray?,
    contentPadding: PaddingValues,
    onOpen: (Person) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("People") }) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
    ) { padding ->
        if (people.isEmpty()) {
            EmptyPeople(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(people, key = { it.id }) { p ->
                val ground = Grounds.of(p.card.label)
                ListItem(
                    modifier = Modifier.clickable { onOpen(p) },
                    leadingContent = { PhotoTile(rememberBitmap(photoOf(p)), p.card, ground, 48.dp) },
                    headlineContent = { Text(p.card.name, maxLines = 1) },
                    supportingContent = {
                        val when_ = DateUtils.getRelativeTimeSpanString(p.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        val how = when (p.via) {
                            "airdrop" -> "AirDrop"
                            "web" -> "Web card"
                            "share" -> "Shared"
                            "link" -> "Link"
                            else -> "Tap"
                        }
                        Text(listOf(p.card.roleLine, "$how · $when_").filter { it.isNotBlank() }.joinToString("\n"), maxLines = 2)
                    },
                    trailingContent = {
                        if (p.saved) Icon(Icons.Outlined.CheckCircle, contentDescription = "Saved to Contacts",
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@Composable
private fun EmptyPeople(modifier: Modifier) {
    val ground = LocalGround.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(ground.color),
            contentAlignment = Alignment.Center) {
            ConstructionGrid(12.dp, ground.line, Modifier.matchParentSize())
            CellName("HELLO", ground.ink, maxCell = 8.dp, modifier = Modifier.padding(horizontal = 24.dp), center = true)
        }
        Text("No one yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Cards you get from a tap, an AirDrop or a web reply land here, so you can save them later.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
