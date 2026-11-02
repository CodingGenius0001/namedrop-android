package com.keshav.daptap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keshav.daptap.core.CellFont
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Cell size that fits [text] on one line in [width], capped at [maxCell]. */
fun fitCell(text: String, width: Dp, maxCell: Dp): Dp {
    val cols = CellFont.layout(text).columns.coerceAtLeast(1)
    return min(maxCell.value, width.value / cols).dp
}

/**
 * A word drawn in DapTap's grid alphabet, always on one line: the cell shrinks as the word gets longer.
 * [progress] 0..1 builds the word cell by cell outward from [origin] (fractions of the box), so a new
 * name visibly assembles on the grid. Words the alphabet can't draw (non-Latin scripts) fall back to type.
 */
@Composable
fun CellName(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxCell: Dp = 26.dp,
    progress: Float = 1f,
    origin: Offset = Offset(0.5f, 0.5f),
    center: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = text }) {
        if (!CellFont.canRender(text)) {
            Text(
                text, color = color, fontSize = 56.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 60.sp,
            )
            return@BoxWithConstraints
        }
        val layout = remember(text) { CellFont.layout(text) }
        val cell = fitCell(text, maxWidth, maxCell)
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxWidth().height(cell * CellFont.ROWS)) {
            val c = with(density) { cell.toPx() }
            val inset = c * 0.07f
            val radius = CornerRadius(c * 0.22f)
            val dx = if (center) (size.width - layout.columns * c) / 2f else 0f
            translate(left = dx) {
            val ox = origin.x * layout.columns * c
            val oy = origin.y * CellFont.ROWS * c
            val far = max(hypot(layout.columns * c, CellFont.ROWS * c), 1f)
            for ((col, row) in layout.cells) {
                val cx = col * c + c / 2
                val cy = row * c + c / 2
                val t = cellReveal(progress, hypot(cx - ox, cy - oy) / far)
                if (t <= 0f) continue
                val s = (c - inset * 2) * t
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * min(1f, t * 1.4f)),
                    topLeft = Offset(cx - s / 2, cy - s / 2),
                    size = Size(s, s),
                    cornerRadius = radius,
                )
            }
            }
        }
    }
}

/** Each cell starts after a delay proportional to its distance from the origin, then grows over 35% of the run. */
internal fun cellReveal(progress: Float, distance: Float): Float {
    if (progress >= 1f) return 1f
    val start = distance.coerceIn(0f, 1f) * 0.65f
    return ((progress - start) / 0.35f).coerceIn(0f, 1f)
}
