package com.keshav.daptap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
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

/** The cell pitch for a name: it fills [width] on one line, capped at [maxCell]. */
fun posterPitch(firstWord: String, width: Dp, maxCell: Dp = 26.dp): Dp {
    val cols = if (CellFont.canRender(firstWord)) CellFont.layout(firstWord).columns else 12
    return min(maxCell.value, width.value / cols.coerceAtLeast(1)).dp
}

/** Side margin of the wallet, and the inner padding of a pass. */
private val WALLET_MARGIN = 16.dp
private val PASS_PADDING = 24.dp
/** Height of the tear-off stub under the perforation. */
val STUB_HEIGHT = 92.dp

/**
 * A person's card as a contact pass in the wallet: a frosted glass pass on the navy ground, its head in tab
 * caps, the first name built in white cells on one line, everything else in plain type. [stub] fills the
 * tear-off part under the perforation (status, last swap, stamps); [stack] older passes peek out below.
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
    /** While phones touch, 0..1 sweeps light across the glass. */
    wave: Float = -1f,
    /** Your own card with no photo yet: show a tile that adds one (it travels in every AirDrop and tap). */
    onAddPhoto: (() -> Unit)? = null,
    /** Shown in cells when the card has no name: "YOU" on your own card, a greeting on someone else's. */
    placeholder: String = "YOU",
    head: String = "CONTACT PASS",
    headRight: String? = null,
    stack: Int = 0,
    stub: (@Composable () -> Unit)? = null,
    passModifier: Modifier = Modifier,
) {
    val first = card.firstName.ifBlank { card.name.ifBlank { placeholder } }
    BoxWithConstraints(modifier.fillMaxSize().walletGround()) {
        val inner = maxWidth - WALLET_MARGIN * 2 - PASS_PADDING * 2
        val pitch = posterPitch(first, inner)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = WALLET_MARGIN, end = WALLET_MARGIN, top = topInset + 8.dp, bottom = bottomInset + 24.dp)
        ) {
            GlassPass(
                ground, passModifier.fillMaxWidth(), stubHeight = if (stub != null) STUB_HEIGHT else null,
                stack = stack, shine = wave,
            ) {
                Column {
                    Column(Modifier.padding(start = PASS_PADDING, end = PASS_PADDING, top = 20.dp, bottom = 24.dp)) {
                        PassHead(head, headRight)
                        Spacer(Modifier.height(16.dp))
                        PassPhoto(card, photo, ground, onAddPhoto)
                        CellName(first, Wallet.ink, maxCell = pitch, progress = nameProgress, origin = Offset(0.5f, 0.5f))
                        Spacer(Modifier.height(14.dp))
                        val rest = card.restOfName()
                        if (rest.isNotEmpty() && CellFont.canRender(rest)) {
                            // The surname is built on the same pitch, a beat after the first name, in whole cells
                            // when it fits and otherwise in halves or thirds of one, so it stays on one line.
                            val cols = CellFont.layout(rest).columns
                            val split = ceil(cols * pitch.value / inner.value).coerceAtLeast(1f)
                            CellName(rest, Wallet.ink, maxCell = pitch / split, origin = Offset(0f, 0.5f),
                                progress = ((nameProgress - 0.2f) / 0.8f).coerceIn(0f, 1f))
                            Spacer(Modifier.height(10.dp))
                        } else if (rest.isNotEmpty()) {
                            Text(rest, color = Wallet.ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (card.roleLine.isNotEmpty()) {
                            Text(card.roleLine, color = Wallet.inkSoft, fontSize = 17.sp, lineHeight = 23.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        DetailRows(card, Wallet.ink, Wallet.inkSoft, Modifier.fillMaxWidth())
                    }
                    if (stub != null) {
                        Box(Modifier.fillMaxWidth().height(STUB_HEIGHT).padding(horizontal = PASS_PADDING), contentAlignment = Alignment.CenterStart) {
                            stub()
                        }
                    }
                }
            }
            // Room for the older passes peeking out under this one.
            if (stack > 0) Spacer(Modifier.height((12 * stack.coerceAtMost(2)).dp))
        }
    }
}

@Composable
private fun PassPhoto(card: ContactCard, photo: ImageBitmap?, ground: Ground, onAddPhoto: (() -> Unit)?) {
    if (photo != null) {
        PhotoTile(photo, card, ground, 84.dp)
        Spacer(Modifier.height(18.dp))
    } else if (onAddPhoto != null) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Add your photo", role = Role.Button, onClick = onAddPhoto),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = Wallet.ink, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.padding(end = 8.dp)) {
                Text("Add your photo", color = Wallet.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("It goes with your card, so they see your face", color = Wallet.inkSoft, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
