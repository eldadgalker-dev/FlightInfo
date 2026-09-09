// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Theme
// Version 2.4
// Purpose : Material 3 colour schemes (night default) and small shared
//           composables: confidence marker, labelled value.
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skytrack.fusion.Confidence

private val NightScheme = darkColorScheme(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF7AA2C4),
    tertiary = Color(0xFF5AD1C6),
    background = Color(0xFF0B1622),
    surface = Color(0xFF13202D),
    onSurface = Color(0xFFE6ECF2),
    surfaceVariant = Color(0xFF1F2A36),
    onSurfaceVariant = Color(0xFFB8C4D0)
)

private val DayScheme = lightColorScheme(
    primary = Color(0xFFD9581E),
    secondary = Color(0xFF2E5F8A),
    tertiary = Color(0xFF0F8F83),
    background = Color(0xFFF4F1EA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E2D4),
    onSurfaceVariant = Color(0xFF4A4A4A)
)

/** Semantic value colours: distances, times, motion (speed / altitude / track), status. */
object Accent {
    val distance @Composable get() = MaterialTheme.colorScheme.primary
    val time @Composable get() = MaterialTheme.colorScheme.secondary
    val motion @Composable get() = MaterialTheme.colorScheme.tertiary
    val status @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

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
 * A small label above a value. The value is a single non-wrapping line with
 * tabular digits; labels are clipped with an ellipsis rather than colliding
 * with the neighbouring cell. Digits and units stay LTR under RTL locales.
 */
@Composable
fun LabeledValue(label: String, value: String, confidence: Confidence? = null, big: Boolean = false,
                 modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier = modifier.padding(horizontal = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (confidence != null) {
                Text(confidenceGlyph(confidence), color = confidenceColor(confidence), fontSize = if (big) 11.sp else 9.sp)
                Spacer(Modifier.width(3.dp))
            }
            Text(
                value,
                fontSize = if (big) 20.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
