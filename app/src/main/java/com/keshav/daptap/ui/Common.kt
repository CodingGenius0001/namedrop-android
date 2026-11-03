package com.keshav.daptap.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.data.Photos

@Composable
fun rememberBitmap(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) { bytes?.let { Photos.decodeBytes(it)?.asImageBitmap() } }

/** The rest of the name after the first word (the first word is drawn in cells). */
fun ContactCard.restOfName(): String = name.trim().substringAfter(' ', "").trim()

/** Contact detail rows on a ground: icon, value. Only the fields that exist. */
@Composable
fun DetailRows(card: ContactCard, ink: Color, inkSoft: Color, modifier: Modifier = Modifier) {
    val rows = buildList {
        if (card.phone.isNotBlank()) add(Icons.Outlined.Phone to card.phone)
        if (card.email.isNotBlank()) add(Icons.Outlined.MailOutline to card.email)
        if (card.instagram.isNotBlank()) add(Icons.Outlined.AlternateEmail to card.instagram)
        if (card.website.isNotBlank()) add(Icons.Outlined.Language to card.website.removePrefix("https://").removePrefix("http://"))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { (icon, value) -> DetailRow(icon, value, ink, inkSoft) }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, value: String, ink: Color, inkSoft: Color) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = inkSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(value, color = ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Photo as a grid-aligned rounded square, or the person's initials on a darker plate. */
@Composable
fun PhotoTile(photo: ImageBitmap?, card: ContactCard, ground: Ground, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.14f)).background(ground.raised),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = "Photo of ${card.name}", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(card.initials, color = ground.ink, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
        }
    }
}

/** Small live-state dot used in status chips. */
@Composable
fun StateDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Title band for People and Settings: the wallet's navy, so every tab sits in the same world. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroundTopBar(title: String) {
    Box(Modifier.walletGround()) {
        androidx.compose.material3.TopAppBar(
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp) },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent, titleContentColor = Wallet.ink,
            ),
        )
    }
}

