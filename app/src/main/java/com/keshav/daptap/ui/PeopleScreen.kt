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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.data.Person

/** One cell of the names on People passes. */
private val STRIP_CELL = 7.dp

/**
 * Everyone whose card you've received, newest first: the wallet's stack of passes. Each pass carries how and
 * when you met in tab caps, their first name in cells, and a SAVED stamp once they're in your contacts.
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
        containerColor = Wallet.navyBottom,
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
    ) { padding ->
        Box(Modifier.fillMaxSize().walletGround()) {
            if (people.isEmpty()) {
                EmptyPeople(Modifier.padding(padding))
                return@Box
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Rows never jump: each person keeps their pass, and only its content changes.
                items(people, key = { it.id }) { p -> PersonPass(p, rememberBitmap(photoOf(p)), onOpen, Modifier) }
            }
        }
    }
}

@Composable
private fun PersonPass(p: Person, photo: androidx.compose.ui.graphics.ImageBitmap?, onOpen: (Person) -> Unit, modifier: Modifier) {
    val ground = Grounds.of(p.card.label)
    val cell = STRIP_CELL
    val ago = DateUtils.getRelativeTimeSpanString(p.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val how = when (p.via) {
        "namedrop" -> "NAMEDROP"
        "airdrop" -> "AIRDROP"
        "web" -> "WEB CARD"
        "share" -> "SHARED"
        "link" -> "LINK"
        else -> "TAP"
    }
    val first = p.card.firstName.ifBlank { p.card.name }
    GlassPass(
        ground,
        modifier.fillMaxWidth().clip(PassShape(22.dp, null))
            .clickable(onClickLabel = "Open ${first.ifBlank { "card" }}", role = Role.Button) { onOpen(p) },
        corner = 22.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
            PassHead(how, ago.toString().uppercase())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (first.isBlank()) {
                        // An iPhone can share a number and photo but no name: say so, and what to do.
                        Text("No name yet", color = Wallet.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(p.card.phone.ifBlank { p.card.email }.ifBlank { "Open to add their name" },
                            color = Wallet.carbon, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                    } else {
                        CellName(first, Wallet.ink, maxCell = cell, origin = Offset(0f, 0.5f))
                        Spacer(Modifier.height(10.dp))
                    }
                    val rest = p.card.restOfName()
                    if (rest.isNotEmpty()) {
                        Text(rest, color = Wallet.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (p.card.roleLine.isNotBlank()) {
                        Text(p.card.roleLine, color = Wallet.inkSoft, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (photo != null) {
                    Spacer(Modifier.width(16.dp))
                    PhotoTile(photo, p.card, ground, 64.dp)
                }
            }
            if (p.saved) {
                Spacer(Modifier.height(10.dp))
                Stamp("SAVED", Modifier.semantics { contentDescription = "Saved to Contacts" }, angle = -5f)
            }
        }
    }
}

@Composable
private fun EmptyPeople(modifier: Modifier) {
    val cell = 8.dp
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassPass(LocalGround.current, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 28.dp)) {
                PassHead("YOUR WALLET", "EMPTY")
                Spacer(Modifier.height(20.dp))
                CellName("HELLO", Wallet.ink, maxCell = cell)
            }
        }
        Text("No one yet", color = Wallet.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp))
        Text(
            "Every card you get from a tap, an AirDrop or a web reply lands here as a pass, so you can save it later.",
            color = Wallet.inkSoft, fontSize = 15.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
