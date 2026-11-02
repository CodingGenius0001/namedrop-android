package com.keshav.daptap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The visible construction grid behind every poster. [wave] (0..1, or <0 for none) sends a ring of lit
 * cells outward from [origin]: the tap ripple, starting where the phones' NFC coils meet.
 */
@Composable
fun ConstructionGrid(
    pitch: Dp,
    line: Color,
    modifier: Modifier = Modifier,
    wave: Float = -1f,
    waveColor: Color = Color.White,
    origin: Offset = Offset(0.5f, 0.55f),
) {
    Canvas(modifier) {
        val p = pitch.toPx().coerceAtLeast(4f)
        val stroke = 1f
        var x = 0f
        while (x <= size.width) { drawRect(line, Offset(x, 0f), Size(stroke, size.height)); x += p }
        var y = 0f
        while (y <= size.height) { drawRect(line, Offset(0f, y), Size(size.width, stroke)); y += p }
        if (wave in 0f..1f) {
            val ox = origin.x * size.width
            val oy = origin.y * size.height
            // The ring ends at the farthest corner from where the phones met, so it is on screen the whole way.
            val far = maxOf(hypot(ox, oy), hypot(size.width - ox, oy), hypot(ox, size.height - oy), hypot(size.width - ox, size.height - oy))
            val ring = wave * far
            val band = p * 3.5f
            val cols = (size.width / p).toInt() + 1
            val rows = (size.height / p).toInt() + 1
            // Bright for most of the trip, fading only as it reaches the edges.
            val fade = 1f - wave * wave
            // A soft light riding the wavefront, so the ripple reads as energy moving through the card.
            val glowR = ring + band * 2
            if (ring > 1f) {
                val inner = ((ring - band * 2) / glowR).coerceIn(0f, 0.98f)
                val mid = (ring / glowR).coerceIn(inner + 0.01f, 0.99f)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Transparent, inner to Color.Transparent,
                        mid to waveColor.copy(alpha = 0.28f * fade), 1f to Color.Transparent,
                        center = Offset(ox, oy), radius = glowR,
                    ),
                    radius = glowR, center = Offset(ox, oy),
                )
            }
            for (r in 0 until rows) for (c in 0 until cols) {
                val cx = c * p + p / 2
                val cy = r * p + p / 2
                val d = abs(hypot(cx - ox, cy - oy) - ring)
                if (d > band) continue
                val a = (1f - d / band) * 0.55f * fade
                val inset = p * 0.12f
                drawRoundRect(
                    waveColor.copy(alpha = a),
                    Offset(c * p + inset, r * p + inset),
                    Size(p - inset * 2, p - inset * 2),
                    CornerRadius(p * 0.2f),
                )
            }
        }
    }
}
