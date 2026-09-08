// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - MetricsAndSettingsScreens
// Version 1.4
// Purpose : Full-page metrics view (Origin / Now / Destination columns)
//           and the settings page (units, clock, theme, follow, gestures).
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skytrack.R
import org.skytrack.data.AltitudeUnit
import org.skytrack.data.DistanceUnit
import org.skytrack.data.Settings
import org.skytrack.data.SpeedUnit
import org.skytrack.data.ThemeMode
import org.skytrack.fusion.Confidence
import org.skytrack.fusion.FlightMetrics
import org.skytrack.fusion.FusionMode

@Composable
fun MetricsScreen(m: FlightMetrics?, s: Settings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.metrics), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        if (m == null) { Text(stringResource(R.string.no_flight_hint)); return }
        val e = m.estimate

        Section(stringResource(R.string.now)) {
            Grid(listOf(
                Triple(stringResource(R.string.ground_speed), Format.speed(e.groundSpeedMps, s.speedUnit), e.speedConfidence),
                Triple(stringResource(R.string.altitude), Format.altitude(e.altM, s.altitudeUnit), e.altitudeConfidence),
                Triple(stringResource(R.string.track), Format.heading(e.trackDeg), e.trackConfidence),
                Triple(stringResource(R.string.vertical_rate), Format.verticalRate(e.verticalRateMps, s.altitudeUnit), e.altitudeConfidence),
                Triple(stringResource(R.string.utc_time), Format.time(m.nowUtc.atZone(java.time.ZoneOffset.UTC), s.use24h), null),
                Triple(stringResource(R.string.elapsed), Format.duration(m.elapsedS), null),
                Triple(stringResource(R.string.total_flight_time), Format.duration(m.totalFlightS), m.eteConfidence),
                Triple(stringResource(R.string.progress), Format.percent(m.percentComplete), e.positionConfidence)
            ))
        }
        Section("${stringResource(R.string.origin)}: ${m.origin.iata} ${m.origin.city}") {
            Grid(listOf(
                Triple(stringResource(R.string.local_time), "${Format.time(m.nowAtOrigin, s.use24h)} ${Format.offset(m.nowAtOrigin)}", null),
                Triple(stringResource(R.string.takeoff_time), Format.time(m.takeoffAtOrigin, s.use24h), null),
                Triple(stringResource(R.string.flown), Format.distance(m.flownM, s.distanceUnit), e.positionConfidence),
                Triple(stringResource(R.string.route_length), Format.distance(m.routeLengthM, s.distanceUnit), null)
            ))
        }
        Section("${stringResource(R.string.destination)}: ${m.destination.iata} ${m.destination.city}") {
            Grid(listOf(
                Triple(stringResource(R.string.local_time), "${Format.time(m.nowAtDestination, s.use24h)} ${Format.offset(m.nowAtDestination)}", null),
                Triple(stringResource(R.string.eta), Format.time(m.etaAtDestination, s.use24h), m.eteConfidence),
                Triple(stringResource(R.string.remaining), Format.distance(m.remainingM, s.distanceUnit), e.positionConfidence),
                Triple(stringResource(R.string.ete), Format.duration(m.eteS), m.eteConfidence)
            ))
        }
        Section(stringResource(R.string.estimator)) {
            val mode = when (e.mode) {
                FusionMode.GNSS_TRACKING -> stringResource(R.string.mode_tracking)
                FusionMode.ROUTE_CONSTRAINED -> stringResource(R.string.mode_constrained)
                FusionMode.PREDICTED_ONLY -> stringResource(R.string.mode_predicted)
                FusionMode.OFF_ROUTE -> stringResource(R.string.mode_off_route)
            }
            Grid(listOf(
                Triple(stringResource(R.string.mode), mode, null),
                Triple(stringResource(R.string.phase), e.phase.name, null),
                Triple(stringResource(R.string.satellites), "${e.satsUsed}/${e.satsVisible}", null),
                Triple(stringResource(R.string.fix_age_label), Format.ageSeconds(e.lastFixAgeMs), null),
                Triple(stringResource(R.string.uncertainty_along), Format.distance(e.sigmaAlongM * 2, s.distanceUnit), null),
                Triple(stringResource(R.string.uncertainty_cross), Format.distance(e.sigmaCrossM * 2, s.distanceUnit), null),
                Triple(stringResource(R.string.position), String.format(java.util.Locale.US, "%.3f, %.3f", e.lat, e.lon), e.positionConfidence)
            ))
        }
        Text(stringResource(R.string.legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Grid(items: List<Triple<String, String, Confidence?>>) {
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (it in r) LabeledValue(it.first, it.second, it.third, modifier = Modifier.weight(1f))
                if (r.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SettingsScreen(s: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        ChipRow(stringResource(R.string.distance_unit), DistanceUnit.values().map { it.name }, s.distanceUnit.name) {
            onChange(s.copy(distanceUnit = DistanceUnit.valueOf(it)))
        }
        ChipRow(stringResource(R.string.altitude_unit), AltitudeUnit.values().map { it.name }, s.altitudeUnit.name) {
            onChange(s.copy(altitudeUnit = AltitudeUnit.valueOf(it)))
        }
        ChipRow(stringResource(R.string.speed_unit), SpeedUnit.values().map { it.name }, s.speedUnit.name) {
            onChange(s.copy(speedUnit = SpeedUnit.valueOf(it)))
        }
        ChipRow(stringResource(R.string.theme), ThemeMode.values().map { it.name }, s.theme.name) {
            onChange(s.copy(theme = ThemeMode.valueOf(it)))
        }
        HorizontalDivider()
        ToggleRow(stringResource(R.string.clock_24h), s.use24h) { onChange(s.copy(use24h = it)) }
        ToggleRow(stringResource(R.string.auto_follow), s.autoFollow) { onChange(s.copy(autoFollow = it)) }
        ToggleRow(stringResource(R.string.track_up_default), s.trackUp) { onChange(s.copy(trackUp = it)) }
        ToggleRow(stringResource(R.string.rotate_gestures), s.rotateGestures) { onChange(s.copy(rotateGestures = it)) }
        HorizontalDivider()
        AboutBlock()
    }
}

/**
 * Copyright / licence / data credits as separate start-aligned lines. Mixed
 * Hebrew and Latin runs are isolated in the string resources (LRI/PDI), so
 * the bidi algorithm cannot reorder or split them across lines.
 */
@Composable
private fun AboutBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (id in listOf(R.string.about_copyright, R.string.about_license, R.string.about_data, R.string.about_offline)) {
            Text(
                stringResource(id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) })
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
