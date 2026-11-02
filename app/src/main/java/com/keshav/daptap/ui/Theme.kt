package com.keshav.daptap.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** A card's ground: the saturated field the poster is printed on, and its one accent for live state. */
@Immutable
data class Ground(val name: String, val color: Color, val accent: Color) {
    val ink: Color get() = Color.White
    val inkSoft: Color get() = Color.White.copy(alpha = 0.76f)
    val line: Color get() = Color.White.copy(alpha = 0.10f)
    val raised: Color get() = lerp(color, Color.Black, 0.18f)
}

object Grounds {
    val all = listOf(
        Ground("Ultramarine", Color(0xFF1F3BD1), Color(0xFFFF5A36)),
        Ground("Signal", Color(0xFFC0301E), Color(0xFFFFD23F)),
        Ground("Emerald", Color(0xFF0B7A55), Color(0xFFFFD23F)),
        Ground("Ink", Color(0xFF161616), Color(0xFFFF5A36)),
        Ground("Rust", Color(0xFFB0460F), Color(0xFFFFD23F)),
        Ground("Teal", Color(0xFF00707F), Color(0xFFFF5A36)),
    )

    fun of(index: Int) = all[index.coerceIn(0, all.lastIndex)]
}

val LocalGround = staticCompositionLocalOf { Grounds.all[0] }

/** True when the user turned animations off (Settings > Accessibility > Remove animations). */
fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun DapTapTheme(ground: Ground, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val g = ground.color
    val scheme = if (dark) {
        val primary = lerp(g, Color.White, 0.45f)
        darkColorScheme(
            primary = primary, onPrimary = Color(0xFF0E0E12),
            primaryContainer = lerp(g, Color.Black, 0.35f), onPrimaryContainer = Color.White,
            secondaryContainer = lerp(Color(0xFF1A1A1E), g, 0.30f), onSecondaryContainer = Color.White,
            background = lerp(Color(0xFF0F0F12), g, 0.06f), onBackground = Color(0xFFECECF0),
            surface = lerp(Color(0xFF0F0F12), g, 0.06f), onSurface = Color(0xFFECECF0),
            surfaceVariant = lerp(Color(0xFF1C1C21), g, 0.12f), onSurfaceVariant = Color(0xFFC4C4CE),
            surfaceContainer = lerp(Color(0xFF17171B), g, 0.08f),
            surfaceContainerHigh = lerp(Color(0xFF1E1E23), g, 0.10f),
            outline = Color(0xFF8E8E99), outlineVariant = Color(0xFF3A3A42),
            error = Color(0xFFFFB4A9), onError = Color(0xFF680003),
        )
    } else {
        lightColorScheme(
            primary = g, onPrimary = Color.White,
            primaryContainer = lerp(Color.White, g, 0.16f), onPrimaryContainer = lerp(g, Color.Black, 0.55f),
            secondaryContainer = lerp(Color.White, g, 0.12f), onSecondaryContainer = lerp(g, Color.Black, 0.6f),
            background = lerp(Color(0xFFF8F8FA), g, 0.03f), onBackground = Color(0xFF16161A),
            surface = lerp(Color(0xFFF8F8FA), g, 0.03f), onSurface = Color(0xFF16161A),
            surfaceVariant = lerp(Color(0xFFEDEDF1), g, 0.06f), onSurfaceVariant = Color(0xFF4A4A55),
            surfaceContainer = lerp(Color(0xFFF1F1F4), g, 0.05f),
            surfaceContainerHigh = lerp(Color(0xFFEAEAEE), g, 0.06f),
            outline = Color(0xFF75757F), outlineVariant = Color(0xFFD3D3DA),
            error = Color(0xFFB3261E), onError = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGround provides ground, content = content)
    }
}
