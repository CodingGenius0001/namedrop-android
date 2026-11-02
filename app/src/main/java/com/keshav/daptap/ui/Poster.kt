package com.keshav.daptap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.CellFont
import com.keshav.daptap.core.ContactCard
import kotlin.math.ceil
import kotlin.math.min

/** Where the NFC coils meet on a Galaxy held back-to-back: roughly the middle of the phone. */
val TapOrigin = Offset(0.5f, 0.45f)

/** The cell pitch for a poster: the first name fills one line with one free cell either side. */
fun posterPitch(firstWord: String, width: Dp, maxCell: Dp = 26.dp): Dp {
    val cols = if (CellFont.canRender(firstWord)) CellFont.layout(firstWord).columns else 12
    return min(maxCell.value, width.value / (cols + 2)).dp
}

/**
 * A person's card printed as a poster: drenched ground, visible construction grid, first name built in
 * cells on one line, everything else in plain type. Content sits on grid lines so the cells register.
 */
@Composable
fun Poster(
    card: ContactCard,
    photo: ImageBitmap?,
    ground: Ground,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    nameProgress: Float = 1f,
    wave: Float = -1f,
    /** Your own card with no photo yet: show a tile that adds one (it travels in every AirDrop and tap). */
    onAddPhoto: (() -> Unit)? = null,
) {
    val first = card.firstName.ifBlank { card.name.ifBlank { "YOU" } }
    BoxWithConstraints(modifier.fillMaxSize().background(ground.color)) {
        val pitch = posterPitch(first, maxWidth)
        val lineWidth = maxWidth - pitch * 2
        val gridPitch = if (pitch < 9.dp) pitch * 2 else pitch
        ConstructionGrid(gridPitch, ground.line, Modifier.fillMaxSize(), wave = wave, origin = TapOrigin)
        val alignedTop = pitch * (ceil(topInset.value / pitch.value) + 1f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = pitch, end = pitch, top = alignedTop, bottom = bottomInset + 24.dp)
        ) {
            val photoCells = ceil(84f / pitch.value).coerceAtLeast(3f)
            if (photo != null) {
                PhotoTile(photo, card, ground, pitch * photoCells)
                Spacer(Modifier.height(pitch))
            } else if (onAddPhoto != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Add your photo", role = Role.Button, onClick = onAddPhoto),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(pitch * photoCells).clip(RoundedCornerShape(22)).background(ground.raised),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = ground.ink, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.width(pitch))
                    Column(Modifier.padding(end = 8.dp)) {
                        Text("Add your photo", color = ground.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("It goes with your card, so they see your face", color = ground.inkSoft, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(pitch))
            }
            CellName(first, ground.ink, maxCell = pitch, progress = nameProgress, origin = Offset(0.5f, 0.5f))
            Spacer(Modifier.height(pitch))
            val rest = card.restOfName()
            if (rest.isNotEmpty() && CellFont.canRender(rest)) {
                // The surname is built on the same grid, a beat after the first name, in whole cells when it
                // fits and otherwise in halves or thirds of one, so it stays on one line and still registers.
                val cols = CellFont.layout(rest).columns
                val split = ceil(cols * pitch.value / lineWidth.value).coerceAtLeast(1f)
                CellName(rest, ground.ink, maxCell = pitch / split, origin = Offset(0f, 0.5f),
                    progress = ((nameProgress - 0.2f) / 0.8f).coerceIn(0f, 1f))
                Spacer(Modifier.height(pitch))
            } else if (rest.isNotEmpty()) {
                Text(rest, color = ground.ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (card.roleLine.isNotEmpty()) {
                Text(card.roleLine, color = ground.inkSoft, fontSize = 17.sp, lineHeight = 23.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))
            DetailRows(card, ground.ink, ground.inkSoft, Modifier.fillMaxWidth())
            Box(Modifier.height(88.dp))
        }
    }
}
