package com.keshav.daptap.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The ticket wallet: a carrier-navy ground, passes in frosted glass over it, tab-cap labels, carbon numerals,
 * and jet red only for what is live or stamped.
 */
object Wallet {
    val navyTop = Color(0xFF17295C)
    val navyBottom = Color(0xFF0A1330)
    /** The navigation bar and anything docked to the bottom edge. */
    val dock = Color(0xFF0C1736)
    val ink = Color.White
    /** Secondary text on navy and on glass: 0.78 white clears 4.5:1 over the lightest glass on navy. */
    val inkSoft = Color.White.copy(alpha = 0.78f)
    /** Values (phone numbers, times): the carbon-copy voice. About 8:1 on navy. */
    val carbon = Color(0xFFC3B8FF)
    /** Live state and stamps. About 5:1 on navy, and still 4.5:1 through the lightest glass. */
    val jet = Color(0xFFFF6A58)
    /** Text on a jet fill. */
    val onJet = Color(0xFF16161A)
    val perforation = Color.White.copy(alpha = 0.34f)

    val ground = Brush.verticalGradient(listOf(navyTop, navyBottom))

    /** Tracked caps for every label: field heads, the pass head, the stub. */
    val tabCaps = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em, color = inkSoft)
}

/** The wallet's navy ground with a faint security-dot screen, the paper a pass is printed on. */
fun Modifier.walletGround(): Modifier = background(Wallet.ground).drawBehind {
    val step = 9.dp.toPx()
    val r = 0.7.dp.toPx()
    val dot = Color.White.copy(alpha = 0.045f)
    var y = step / 2
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width) { drawCircle(dot, r, Offset(x, y)); x += step }
        y += step; row++
    }
}

/**
 * A pass outline: rounded corners, and two half-circle notches [stubHeight] above the bottom edge, where the
 * stub tears off. With no stub it is a plain rounded rectangle.
 */
class PassShape(private val corner: Dp, private val stubHeight: Dp?, private val notchR: Dp = 11.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val body = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(c))) }
        val y = stubHeight?.let { size.height - with(density) { it.toPx() } } ?: return Outline.Generic(body)
        val r = with(density) { notchR.toPx() }
        val holes = Path().apply {
            addOval(Rect(Offset(0f, y), r))
            addOval(Rect(Offset(size.width, y), r))
        }
        return Outline.Generic(Path().apply { op(body, holes, PathOperation.Difference) })
    }
}

/**
 * A frosted glass pass. The person's colour glows through the glass from behind (blurred where Android can
 * blur, a soft radial light elsewhere), the face is a white frost, and a bright edge catches the light at the
 * top. [stubHeight] places the perforation and notches that far above the bottom; [stack] draws that many
 * older passes peeking out below; [shine] (0..1, or <0 for none) sweeps a band of light across the glass.
 */
@Composable
fun GlassPass(
    ground: Ground,
    modifier: Modifier = Modifier,
    stubHeight: Dp? = null,
    stack: Int = 0,
    shine: Float = -1f,
    corner: Dp = 26.dp,
    /** False where drawing must stay cheap (the swap cover): no live blur behind the glass. */
    live: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = PassShape(corner, stubHeight)
    Box(modifier) {
        // Older passes in the wallet: only their lower edges show, peeking out under this one, never through it.
        if (stack > 0) Canvas(Modifier.matchParentSize()) {
            val c = corner.toPx()
            val cover = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(c))) }
            for (i in stack.coerceAtMost(2) downTo 1) {
                val inset = (12 * i).dp.toPx()
                val drop = (16 * i).dp.toPx()
                val peek = Path().apply {
                    addRoundRect(RoundRect(Rect(inset, size.height - c * 3, size.width - inset, size.height + drop), CornerRadius(c)))
                }
                val visible = Path().apply { op(peek, cover, PathOperation.Difference) }
                drawPath(visible, Color.White.copy(alpha = if (i == 1) 0.10f else 0.06f))
                drawPath(visible, Color.White.copy(alpha = 0.16f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
        // The person's colour, glowing through the glass as a soft light, not a shape.
        Box(
            Modifier.matchParentSize().padding(horizontal = 36.dp, vertical = 48.dp)
                .let { if (live && Build.VERSION.SDK_INT >= 31) it.blur(56.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else it }
                .background(
                    Brush.radialGradient(listOf(ground.color.copy(alpha = 0.9f), ground.color.copy(alpha = 0.45f), Color.Transparent)),
                    androidx.compose.foundation.shape.RoundedCornerShape(48.dp),
                )
        )
        Box(
            Modifier.matchParentSize()
                .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.55f))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f))))
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.22f))),
                    shape,
                )
        )
        if (shine in 0f..1f) {
            Canvas(Modifier.matchParentSize().clip(shape)) {
                // A diagonal band of light crossing the glass from where the phones touch.
                val span = size.width + size.height
                val at = -size.height + span * shine
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent, 0.45f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.30f * (1f - shine * 0.6f)),
                        0.55f to Color.Transparent, 1f to Color.Transparent,
                        start = Offset(at, 0f), end = Offset(at + size.height, size.height),
                    )
                )
            }
        }
        if (stubHeight != null) {
            Canvas(Modifier.matchParentSize()) {
                val y = size.height - stubHeight.toPx()
                val inset = 20.dp.toPx()
                drawLine(
                    Wallet.perforation, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx())), cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}


/** A field head in tab caps over its value. */
@Composable
fun PassField(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Wallet.ink) {
    Column(modifier) {
        Text(label, style = Wallet.tabCaps)
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * A rubber stamp: jet red outline caps, set at an angle, as SENT / SAVED / RECEIVE ONLY land on a pass.
 * [color] defaults to jet; a muted stamp (VOID) passes ink soft.
 */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, color: Color = Wallet.jet, angle: Float = -8f) {
    Box(
        modifier.rotate(angle)
            .border(2.5.dp, color, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 0.12.em)
    }
}

/** Head row of a pass: kind on the left, issuer or number on the right, both in tab caps. */
@Composable
fun PassHead(left: String, right: String?, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, style = Wallet.tabCaps, modifier = Modifier.weight(1f))
        if (right != null) Text(right, style = Wallet.tabCaps.copy(color = if (right.startsWith("NO.")) Wallet.carbon else Wallet.inkSoft))
    }
}
