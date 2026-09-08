// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - Theme
// Version 1.1
// Purpose : Material 3 colour schemes (night default) and small shared
//           composables: confidence marker, labelled value.
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.skytrack.fusion.Confidence

private val NightScheme = darkColorScheme(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF7AA2C4),
    background = Color(0xFF0B1622),
    surface = Color(0xFF13202D),
    onSurface = Color(0xFFE6ECF2),
    surfaceVariant = Color(0xFF1F2A36),
    onSurfaceVariant = Color(0xFFB8C4D0)
)

private val DayScheme = lightColorScheme(
    primary = Color(0xFFD9581E),
    secondary = Color(0xFF2E5F8A),
    background = Color(0xFFF4F1EA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E2D4),
    onSurfaceVariant = Color(0xFF4A4A4A)
)

@Composable
fun SkyTrackTheme(night: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (night) NightScheme else DayScheme, content = content)
}

/** Unicode marker for a confidence level: full / half / hollow / dotted circle. */
fun confidenceGlyph(c: Confidence): String = when (c) {
    Confidence.MEASURED -> "\u25CF"
    Confidence.FUSED -> "\u25D0"
    Confidence.PREDICTED -> "\u25CB"
    Confidence.STALE -> "\u25CC"
}

@Composable
fun confidenceColor(c: Confidence): Color = when (c) {
    Confidence.MEASURED -> Color(0xFF6CCB7A)
    Confidence.FUSED -> MaterialTheme.colorScheme.primary
    Confidence.PREDICTED -> MaterialTheme.colorScheme.onSurfaceVariant
    Confidence.STALE -> Color(0xFF808080)
}

/**
 * A small label above a value. Values are forced LTR so digits and units
 * do not reorder under an RTL locale.
 */
@Composable
fun LabeledValue(label: String, value: String, confidence: Confidence? = null, big: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (confidence != null) {
                Text(confidenceGlyph(confidence), color = confidenceColor(confidence), fontSize = if (big) 14.sp else 10.sp)
                Text(" ")
            }
            Text(
                value,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                maxLines = 1
            )
        }
    }
}
