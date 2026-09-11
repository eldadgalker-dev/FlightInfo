// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MetricsAndSettingsScreens
// Version 4.4
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import org.skytrack.R
import org.skytrack.data.AltitudeUnit
import org.skytrack.data.DistanceUnit
import org.skytrack.data.ResourceSnapshot
import org.skytrack.data.Settings
import org.skytrack.data.SpeedUnit
import org.skytrack.data.ThemeMode
import org.skytrack.fusion.Confidence
import org.skytrack.fusion.FlightMetrics
import org.skytrack.fusion.FusionMode
import org.skytrack.map.AerialPack
import org.skytrack.net.UpdateInfo
import org.skytrack.net.Updater
import kotlinx.coroutines.launch

@Composable
fun MetricsScreen(m: FlightMetrics?, s: Settings, baroAvailable: Boolean?, resources: ResourceSnapshot?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.metrics), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        if (m == null) { Text(stringResource(R.string.no_flight_hint)); return }
        val e = m.estimate

        Section(stringResource(R.string.now), Accent.motion) {
            Grid(listOf(
                Triple(stringResource(R.string.ground_speed), Format.speed(e.groundSpeedMps, s.speedUnit), e.speedConfidence),
                Triple(stringResource(R.string.altitude), Format.altitude(e.altM, s.altitudeUnit), e.altitudeConfidence),
                Triple(stringResource(R.string.track), Format.heading(e.trackDeg), e.trackConfidence),
                Triple(stringResource(R.string.vertical_rate), Format.verticalRate(e.verticalRateMps, s.altitudeUnit), e.altitudeConfidence),
                Triple(stringResource(R.string.cabin_altitude), m.cabinAltM?.let { Format.altitude(it, s.altitudeUnit) } ?: "--", null),
                Triple(stringResource(R.string.utc_time), Format.time(m.nowUtc.atZone(java.time.ZoneOffset.UTC), s.use24h), null),
                Triple(stringResource(R.string.elapsed), Format.duration(m.elapsedS), null),
                Triple(stringResource(R.string.total_flight_time), Format.duration(m.totalFlightS), m.eteConfidence),
                Triple(stringResource(R.string.progress), Format.percent(m.percentComplete), e.positionConfidence)
            ), Accent.motion)
        }
        Section("${stringResource(R.string.origin)}: ${m.origin.iata} ${m.origin.city}", Accent.time) {
            Grid(listOf(
                Triple(stringResource(R.string.local_time), "${Format.time(m.nowAtOrigin, s.use24h)} ${Format.offset(m.nowAtOrigin)}", null),
                Triple(if (m.takeoffUtc != null) stringResource(R.string.takeoff_time) else stringResource(R.string.expected_takeoff),
                    Format.time(m.takeoffAtOrigin ?: m.expectedTakeoffUtc?.atZone(m.originZone), s.use24h), null),
                Triple(stringResource(R.string.flown), Format.distance(m.flownM, s.distanceUnit), e.positionConfidence),
                Triple(stringResource(R.string.route_length), Format.distance(m.routeLengthM, s.distanceUnit), null)
            ), Accent.time)
        }
        Section("${stringResource(R.string.destination)}: ${m.destination.iata} ${m.destination.city}", Accent.distance) {
            Grid(listOf(
                Triple(stringResource(R.string.local_time), "${Format.time(m.nowAtDestination, s.use24h)} ${Format.offset(m.nowAtDestination)}", null),
                Triple(stringResource(R.string.eta), Format.time(m.etaAtDestination, s.use24h), m.eteConfidence),
                Triple(stringResource(R.string.remaining), Format.distance(m.remainingM, s.distanceUnit), e.positionConfidence),
                Triple(stringResource(R.string.ete), Format.duration(m.eteS), m.eteConfidence)
            ), Accent.distance)
        }
        Section(stringResource(R.string.estimator), Accent.status) {
            val mode = if (m.estimateOnly) stringResource(R.string.mode_estimate_only) else when (e.mode) {
                FusionMode.GNSS_TRACKING -> stringResource(R.string.mode_tracking)
                FusionMode.ROUTE_CONSTRAINED -> stringResource(R.string.mode_constrained)
                FusionMode.PREDICTED_ONLY -> stringResource(R.string.mode_predicted)
            }
            Grid(listOf(
                Triple(stringResource(R.string.mode), mode, null),
                Triple(stringResource(R.string.phase), phaseName(e.phase), null),
                Triple(stringResource(R.string.satellites), "${e.satsUsed}/${e.satsVisible}", null),
                Triple(stringResource(R.string.fix_age_label), Format.ageSeconds(e.lastFixAgeMs), null),
                Triple(stringResource(R.string.uncertainty_along), Format.distance(e.sigmaAlongM * 2, s.distanceUnit), null),
                Triple(stringResource(R.string.sensor_level), stringResource(when (e.sensorLevel) {
                    3 -> R.string.sensor_level3; 2 -> R.string.sensor_level2; 1 -> R.string.sensor_level1; else -> R.string.sensor_level0 }), null),
                Triple(stringResource(R.string.gnss_accuracy), if (e.mode == FusionMode.GNSS_TRACKING) Format.altitude(e.sigmaAlongM, s.altitudeUnit) else "--", null),
                Triple(stringResource(R.string.replans), "${e.replanCount}", null),
                Triple(stringResource(R.string.visual_fixes), "${e.visualFixCount}", null),
                Triple(stringResource(R.string.barometer), when (baroAvailable) {
                    null -> "--"; true -> stringResource(R.string.present); false -> stringResource(R.string.absent) }, null),
                Triple(stringResource(R.string.over_country_label), m.overflownCountry ?: "--", null),
                Triple(stringResource(R.string.ground_ref_label), m.groundReference?.let { gr ->
                    gr.gnssBiasM?.let { Format.altitude(it, s.altitudeUnit) } ?: stringResource(R.string.present) } ?: stringResource(R.string.absent_short), null),
                Triple(stringResource(R.string.maneuvering_label), stringResource(if (e.maneuvering) R.string.yes else R.string.no), null),
                Triple(stringResource(R.string.position), String.format(java.util.Locale.US, "%.3f, %.3f", e.lat, e.lon), e.positionConfidence)
            ), Accent.status)
            if (baroAvailable == false) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.no_barometer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        resources?.let { r ->
            Section(stringResource(R.string.resources_title), Accent.status) {
                Grid(listOf(
                    Triple(stringResource(R.string.res_java_heap), String.format(java.util.Locale.US, "%.0f MB", r.javaHeapMb), null),
                    Triple(stringResource(R.string.res_native_heap), String.format(java.util.Locale.US, "%.0f MB", r.nativeHeapMb), null),
                    Triple(stringResource(R.string.res_cpu_total), String.format(java.util.Locale.US, "%.1f%%", r.cpuPercentSinceStart), null),
                    Triple(stringResource(R.string.res_cpu_recent), r.cpuPercentRecent?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "--", null),
                    Triple(stringResource(R.string.res_disk_internal), String.format(java.util.Locale.US, "%.1f MB", r.filesMb), null),
                    Triple(stringResource(R.string.res_disk_external), String.format(java.util.Locale.US, "%.1f MB", r.externalMb), null),
                    Triple(stringResource(R.string.res_battery), r.batteryPercent?.let { "$it%" + (if (r.charging == true) " \u26A1" else "") } ?: "--", null),
                    Triple(stringResource(R.string.res_current), r.currentMa?.let { "$it mA" } ?: "--", null)
                ), Accent.status)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.res_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, accent: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            // Coloured header band identifies the section at a glance.
            Row(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            }
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}

@Composable
private fun Grid(items: List<Triple<String, String, Confidence?>>, accent: Color) {
    val rows = items.chunked(2)
    Column {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (i % 2 == 1) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else Color.Transparent)
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (it in r) LabeledValue(it.first, it.second, it.third, modifier = Modifier.weight(1f), accent = accent)
                if (r.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SettingsScreen(s: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit, onHelp: () -> Unit,
                   onShareLog: () -> Unit, onDeleteLogs: () -> Unit, logCount: Int, aerial: AerialPack, updater: Updater,
                   onExit: (keepTracking: Boolean) -> Unit, onLogs: () -> Unit = {}, onFeedback: () -> Unit = {}) {
    var exitDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
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
        ChipRow(stringResource(R.string.language), listOf("system", "he", "en"), s.language) {
            onChange(s.copy(language = it))
        }
        HorizontalDivider()
        ToggleRow(stringResource(R.string.clock_24h), s.use24h) { onChange(s.copy(use24h = it)) }
        ToggleRow(stringResource(R.string.auto_follow), s.autoFollow) { onChange(s.copy(autoFollow = it)) }
        ToggleRow(stringResource(R.string.track_up_default), s.trackUp) { onChange(s.copy(trackUp = it)) }
        ToggleRow(stringResource(R.string.rotate_gestures), s.rotateGestures) { onChange(s.copy(rotateGestures = it)) }
        HorizontalDivider()
        ToggleRow(stringResource(R.string.use_network), s.useNetwork) { onChange(s.copy(useNetwork = it)) }
        Text(stringResource(R.string.use_network_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        AerialSection(s, onChange, aerial)
        HorizontalDivider()
        ToggleRow(stringResource(R.string.log_flights), s.logFlights) { onChange(s.copy(logFlights = it)) }
        Text(stringResource(R.string.log_flights_hint, logCount), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLogs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_title)) }
            OutlinedButton(onClick = onShareLog, enabled = logCount > 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share_log)) }
        }
        OutlinedButton(onClick = onFeedback, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feedback_title)) }
        HorizontalDivider()
        UpdateSection(updater)
        HorizontalDivider()
        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.help)) }
        OutlinedButton(onClick = { exitDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.exit_app)) }
        AboutBlock()
        if (exitDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { exitDialog = false },
                title = { Text(stringResource(R.string.exit_app)) },
                text = { Text(stringResource(R.string.exit_question)) },
                confirmButton = { TextButton(onClick = { exitDialog = false; onExit(true) }) { Text(stringResource(R.string.exit_keep_tracking)) } },
                dismissButton = { TextButton(onClick = { exitDialog = false; onExit(false) }) { Text(stringResource(R.string.exit_close_all)) } }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Copyright / licence / data credits as separate start-aligned lines. Mixed
 * Hebrew and Latin runs are isolated in the string resources (LRI/PDI), so
 * the bidi algorithm cannot reorder or split them across lines.
 */
@Composable
private fun AboutBlock() {
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        // Latin-only line: force LTR so the bidi algorithm cannot reverse its word order under a Hebrew locale.
        Text(stringResource(R.string.about_copyright), style = small.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.about_beta), style = small, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        for (id in listOf(R.string.about_license, R.string.about_map, R.string.about_airports, R.string.about_fonts, R.string.about_offline)) {
            Text(stringResource(id), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Manual update: check GitHub Releases, download the APK, hand it to the installer. */
@Composable
private fun UpdateSection(updater: Updater) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    val sigMismatch = stringResource(R.string.update_signature_mismatch)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.update_current, updater.currentVersion()), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val i = info
        when {
            error != null -> Text(stringResource(R.string.update_error, error ?: ""), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            i != null && i.isNewer -> Text(stringResource(R.string.update_available, i.latestVersion), color = MaterialTheme.colorScheme.primary)
            i != null && checked -> Text(stringResource(R.string.update_none), style = MaterialTheme.typography.bodySmall)
        }
        if (i != null && i.isNewer && i.notes.isNotBlank()) {
            Text(i.notes.take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val p = progress
        if (p != null) {
            if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { info = updater.check(); checked = true } catch (e: Exception) { error = e.message ?: "error" }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text(stringResource(if (busy && progress == null) R.string.update_checking else R.string.update_check)) }
            OutlinedButton(
                onClick = {
                    val url = i?.apkUrl ?: return@OutlinedButton
                    busy = true; error = null; progress = -1
                    scope.launch {
                        try {
                            val f = updater.download(url) { pct -> progress = pct }
                            if (updater.signatureMatches(f) == false) error = sigMismatch
                            else updater.install(f)
                        } catch (e: Exception) { error = e.message ?: "error" }
                        progress = null; busy = false
                    }
                },
                enabled = !busy && i != null && i.isNewer && i.apkUrl != null, modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.update_install)) }
        }
        Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Optional Blue Marble imagery pack: status, download with progress, show/hide, delete. */
@Composable
private fun AerialSection(s: Settings, onChange: (Settings) -> Unit, aerial: AerialPack) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(aerial.installed) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.aerial_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(if (installed) R.string.aerial_installed else R.string.aerial_not_installed, aerial.sizeMb),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.aerial_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val p = progress
        if (p != null) {
            if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(if (p >= 0) "$p%" else "...", style = MaterialTheme.typography.labelSmall)
        }
        error?.let {
            val msg = if (it.contains("404")) stringResource(R.string.aerial_not_published) else stringResource(R.string.aerial_error, it)
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    error = null; progress = -1
                    scope.launch {
                        val err = aerial.download { pct -> progress = pct }
                        progress = null
                        if (err != null) error = err else { installed = true; onChange(s.copy(aerial = true)) }
                    }
                },
                enabled = progress == null, modifier = Modifier.weight(1f)
            ) { Text(stringResource(if (installed) R.string.aerial_redownload else R.string.aerial_download)) }
            OutlinedButton(onClick = { aerial.delete(); installed = false; onChange(s.copy(aerial = false)) },
                enabled = installed && progress == null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.aerial_delete)) }
        }
        if (installed) ToggleRow(stringResource(R.string.aerial_show), s.aerial) { onChange(s.copy(aerial = it)) }
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
