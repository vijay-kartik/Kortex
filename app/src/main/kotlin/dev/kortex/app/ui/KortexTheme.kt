package dev.kortex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kortex.app.R

/**
 * Kortex visual identity — committed dark ("techy" is the brief), one accent.
 *
 * Palette: void (background) / panel (raised surfaces) / edge (hairlines) with
 * ink+muted text, and a single neural-periwinkle accent — "synapse" — used for
 * the user's voice, live activity, and the trace rail. Amber and alarm exist
 * only as log-level colors inside the trace readout.
 */
val Void = Color(0xFF0B0E14)
val Panel = Color(0xFF141A26)
val Edge = Color(0xFF242E42)
val Ink = Color(0xFFE9EDF5)
val Muted = Color(0xFF97A1B8)
val Synapse = Color(0xFF7C8CFF)
val SynapseDim = Color(0xFF232A52)
val Amber = Color(0xFFEFB358)
val Alarm = Color(0xFFFF7182)

/** Space Grotesk carries the conversation; JetBrains Mono carries the machinery. */
@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: FontWeight) = Font(
    R.font.space_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: FontWeight) = Font(
    R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Grotesk = FontFamily(
    grotesk(FontWeight.Normal),
    grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold),
    grotesk(FontWeight.Bold),
)

val Mono = FontFamily(
    mono(FontWeight.Normal),
    mono(FontWeight.Medium),
)

private val scheme = darkColorScheme(
    primary = Synapse,
    onPrimary = Void,
    primaryContainer = SynapseDim,
    onPrimaryContainer = Ink,
    background = Void,
    onBackground = Ink,
    surface = Void,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    surfaceContainerHighest = Panel,
    surfaceContainer = Panel,
    outline = Edge,
    outlineVariant = Edge,
    tertiary = Amber,
    error = Alarm,
)

private val base = Typography()
private val type = Typography(
    headlineSmall = base.headlineSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Grotesk, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Grotesk),
    bodySmall = base.bodySmall.copy(fontFamily = Grotesk),
    labelLarge = base.labelLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Medium),
    // labelSmall is the telemetry voice: mono, tracked out, always quiet.
    labelSmall = base.labelSmall.copy(
        fontFamily = Mono,
        fontSize = 10.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun KortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
