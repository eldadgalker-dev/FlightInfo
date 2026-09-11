// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapScreen
// Version 4.0
// Purpose : Full-screen MapLibre view hosted in Compose, with floating
//           zoom / fit / recenter / orientation controls, a status strip
//           (GNSS, mode, fix age) and a collapsible metrics panel.
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView
import org.skytrack.R
import org.skytrack.data.GeoData
import org.skytrack.data.Settings
import org.skytrack.map.AerialPack
import org.skytrack.fusion.FlightMetrics
import org.skytrack.fusion.FusionMode
import org.skytrack.map.MapController
import org.skytrack.map.MapStyle
import org.skytrack.sensors.GnssQuality

@Composable
fun MapScreen(
    metrics: FlightMetrics?,
    settings: Settings,
    night: Boolean,
    onOpenMetrics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenHelp: () -> Unit,
    onToggleEstimateOnly: () -> Unit,
    visualFixCandidates: () -> List<Pair<GeoData.Place, Double>>,
    onVisualFix: (GeoData.Place, Boolean?, Int) -> Unit,
    onConfirmGround: () -> Unit,
    onSnapshot: (android.graphics.Bitmap) -> Unit,
    initialZoom: Double?,
    onZoomChanged: (Double) -> Unit,
    panelExpandedInitial: Boolean,
    onPanelExpandedChanged: (Boolean) -> Unit,
    hebrew: Boolean
) {
    val mapView = rememberMapViewWithLifecycle()
    val context = LocalContext.current
    val aerial = remember { AerialPack(context) }
    var showVisualFix by remember { mutableStateOf(false) }
    var snapshotTaken by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MapController?>(null) }
    var trackUp by rememberSaveable { mutableStateOf(settings.trackUp) }
    var panelExpanded by rememberSaveable { mutableStateOf(panelExpandedInitial) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                if (controller == null) {
                    mv.getMapAsync { map ->
                        val c = MapController(mv.context, map, initialZoom, onZoomChanged)
                        c.followEnabled = settings.autoFollow
                        c.trackUp = trackUp
                        c.setRotateGestures(settings.rotateGestures)
                        controller = c
                    }
                }
            }
        )

        val aerialUrl = if (settings.aerial && aerial.installed) aerial.tileUrl else null
        LaunchedEffect(controller, night, aerialUrl) { controller?.setPalette(if (night) MapStyle.NIGHT else MapStyle.DAY, aerialUrl) }
        LaunchedEffect(controller, settings) {
            controller?.followEnabled = settings.autoFollow
            controller?.setRotateGestures(settings.rotateGestures)
        }
        LaunchedEffect(controller, trackUp) { controller?.trackUp = trackUp }
        LaunchedEffect(controller, metrics) { if (metrics != null) controller?.update(metrics) }
        // One map snapshot when the flight ends, saved next to the flight log.
        LaunchedEffect(metrics?.estimate?.phase) {
            if (!snapshotTaken && metrics?.estimate?.phase == org.skytrack.sensors.FlightPhase.LANDED && !metrics.estimateOnly) {
                snapshotTaken = true
                try { mapView.snapshot { bmp -> onSnapshot(bmp) } } catch (e: Exception) { }
            }
        }

        // -- Status strip --
        StatusStrip(metrics, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 64.dp, end = 64.dp).fillMaxWidth())

        // -- GNSS warning / relief banner (live mode) --
        metrics?.let { m ->
            if (m.gnssWarnLevel > 0 || m.gnssRelief) {
                GnssBanner(m, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp, start = 64.dp, end = 64.dp).fillMaxWidth())
            }
        }

        // -- Floating controls: top corners, below the status strip, clear of the panel --
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallFloatingActionButton(onClick = { controller?.zoomIn() }) { Icon(Icons.Filled.Add, stringResource(R.string.zoom_in)) }
            SmallFloatingActionButton(onClick = { controller?.zoomOut() }) { Icon(Icons.Filled.Remove, stringResource(R.string.zoom_out)) }
            SmallFloatingActionButton(onClick = { controller?.fitRoute() }) { Icon(Icons.Filled.ZoomOutMap, stringResource(R.string.fit_route)) }
            SmallFloatingActionButton(onClick = { controller?.recenter() }) { Icon(Icons.Filled.CenterFocusStrong, stringResource(R.string.recenter)) }
            SmallFloatingActionButton(onClick = { trackUp = !trackUp; controller?.recenter() }) {
                Icon(if (trackUp) Icons.Filled.Navigation else Icons.Filled.Explore, stringResource(R.string.orientation))
            }
        }

        // -- Left controls: metrics, settings, setup, help, live/estimate --
        Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallFloatingActionButton(onClick = onOpenSetup) { Icon(Icons.Filled.EditNote, stringResource(R.string.flight_setup)) }
            SmallFloatingActionButton(onClick = onOpenMetrics) { Icon(Icons.Filled.TableChart, stringResource(R.string.metrics)) }
            SmallFloatingActionButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            SmallFloatingActionButton(onClick = onOpenHelp) { Icon(Icons.Filled.Help, stringResource(R.string.help)) }
            if (metrics != null) {
                // Live sensing (satellite) vs time-based estimate (clock)
                SmallFloatingActionButton(onClick = onToggleEstimateOnly) {
                    Icon(if (metrics.estimateOnly) Icons.Filled.Schedule else Icons.Filled.SatelliteAlt, stringResource(R.string.toggle_mode))
                }
                if (!metrics.estimateOnly) {
                    SmallFloatingActionButton(onClick = { showVisualFix = true }) {
                        Icon(Icons.Filled.Visibility, stringResource(R.string.visual_fix))
                    }
                }
            }
        }

        if (showVisualFix) {
            VisualFixDialog(
                candidates = remember(showVisualFix) { visualFixCandidates() },
                settings = settings, hebrew = hebrew,
                onConfirm = { place, side, dist -> onVisualFix(place, side, dist); showVisualFix = false },
                onDismiss = { showVisualFix = false }
            )
        }

        // -- Metrics panel --
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp)) {
            MetricsPanel(metrics, settings, panelExpanded, onConfirmGround) { panelExpanded = !panelExpanded; onPanelExpandedChanged(panelExpanded) }
        }
    }
}

@Composable
private fun StatusStrip(m: FlightMetrics?, modifier: Modifier) {
    // Fixed footprint between the two button columns: at most two single-line rows.
    Surface(modifier = modifier.clip(RoundedCornerShape(12.dp)), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (m == null) {
                StripText(stringResource(R.string.no_flight))
                return@Surface
            }
            val e = m.estimate
            if (m.estimateOnly) {
                StripText(stringResource(R.string.mode_estimate_only), MaterialTheme.colorScheme.primary)
                StripText(listOfNotNull(
                    stringResource(R.string.phase_label, phaseName(e.phase)),
                    m.overflownCountry?.let { stringResource(R.string.over_country, it) }
                ).joinToString("  \u00B7  "))
            } else {
                val gnss = if (m.positionSource != null) stringResource(R.string.source_adsb) else when (e.gnssQuality) {
                    GnssQuality.GOOD -> stringResource(R.string.gnss_good, e.satsUsed)
                    GnssQuality.DEGRADED -> stringResource(R.string.gnss_degraded, e.satsUsed)
                    GnssQuality.NONE -> stringResource(R.string.gnss_none)
                }
                val mode = when (e.mode) {
                    FusionMode.GNSS_TRACKING -> stringResource(R.string.mode_tracking)
                    FusionMode.ROUTE_CONSTRAINED -> stringResource(R.string.mode_constrained)
                    FusionMode.PREDICTED_ONLY -> stringResource(R.string.mode_predicted)
                }
                val line1 = when {
                    m.positionSource != null -> gnss
                    e.mode == FusionMode.GNSS_TRACKING -> "$gnss  \u00B7  ${stringResource(R.string.accuracy_m, e.sigmaAlongM.toInt())}"
                    e.mode == FusionMode.ROUTE_CONSTRAINED -> "${stringResource(R.string.gnss_none)}  \u00B7  ${Format.duration(m.gnssNoFixS)}  \u00B7  $mode"
                    else -> mode
                }
                StripText(line1, MaterialTheme.colorScheme.primary)
                val second = listOfNotNull(
                    stringResource(R.string.phase_label, phaseName(e.phase)),
                    if (e.maneuvering) stringResource(R.string.maneuvering) else null,
                    m.overflownCountry?.let { stringResource(R.string.over_country, it) }
                ).joinToString("  \u00B7  ")
                StripText(second)
            }
        }
    }
}

@Composable
private fun StripText(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
private fun MetricsPanel(m: FlightMetrics?, s: Settings, expanded: Boolean, onConfirmGround: () -> Unit, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onToggle() },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        if (m == null) {
            Text(stringResource(R.string.no_flight_hint), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            return@Surface
        }
        val e = m.estimate
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.originMismatchM?.let { d ->
                Text(stringResource(R.string.origin_mismatch, Format.distance(d, s.distanceUnit), m.origin.iata),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // Before takeoff, in live mode: one-tap ground confirmation / altitude calibration.
            if (!m.estimateOnly && e.phase == org.skytrack.sensors.FlightPhase.GROUND && expanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = onConfirmGround) { Text(stringResource(R.string.confirm_ground)) }
                    Text(
                        m.groundReference?.let { gr -> stringResource(R.string.ground_ref_done, gr.gnssBiasM?.let { Format.altitude(it, s.altitudeUnit) } ?: "--") }
                            ?: stringResource(R.string.ground_ref_hint),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            // Headline: three equal cells, values never wrap.
            Row(Modifier.fillMaxWidth()) {
                LabeledValue(stringResource(R.string.remaining), Format.distance(m.remainingM, s.distanceUnit), e.positionConfidence, big = true, modifier = Modifier.weight(1f), accent = Accent.distance)
                LabeledValue(stringResource(R.string.ete), Format.duration(m.eteS), m.eteConfidence, big = true, modifier = Modifier.weight(1f), accent = Accent.time)
                LabeledValue(stringResource(R.string.eta_local, m.destination.iata), Format.time(m.etaAtDestination, s.use24h), m.eteConfidence, big = true, modifier = Modifier.weight(1f), accent = Accent.time)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue(stringResource(R.string.ground_speed), Format.speed(e.groundSpeedMps, s.speedUnit), e.speedConfidence, modifier = Modifier.weight(1f), accent = Accent.motion)
                    LabeledValue(stringResource(R.string.altitude), Format.altitude(e.altM, s.altitudeUnit), e.altitudeConfidence, modifier = Modifier.weight(1f), accent = Accent.motion)
                    LabeledValue(stringResource(R.string.track), Format.heading(e.trackDeg), e.trackConfidence, modifier = Modifier.weight(1f), accent = Accent.motion)
                }
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue(stringResource(R.string.flown), Format.distance(m.flownM, s.distanceUnit), e.positionConfidence, modifier = Modifier.weight(1f), accent = Accent.distance)
                    LabeledValue(stringResource(R.string.progress), Format.percent(m.percentComplete), e.positionConfidence, modifier = Modifier.weight(1f), accent = Accent.distance)
                    LabeledValue(stringResource(R.string.elapsed), Format.duration(m.elapsedS), modifier = Modifier.weight(1f), accent = Accent.time)
                }
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue(stringResource(R.string.time_at, m.origin.iata), Format.time(m.nowAtOrigin, s.use24h), modifier = Modifier.weight(1f), accent = Accent.time)
                    LabeledValue(stringResource(R.string.utc_time), Format.time(m.nowUtc.atZone(java.time.ZoneOffset.UTC), s.use24h), modifier = Modifier.weight(1f), accent = Accent.time)
                    LabeledValue(stringResource(R.string.time_at, m.destination.iata), Format.time(m.nowAtDestination, s.use24h), modifier = Modifier.weight(1f), accent = Accent.time)
                }
            } else {
                Text(stringResource(R.string.panel_expand_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

/** MapView bound to the Compose host's lifecycle. onCreate is invoked once at construction. */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).also { it.onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

@Composable
fun phaseName(p: org.skytrack.sensors.FlightPhase): String = stringResource(when (p) {
    org.skytrack.sensors.FlightPhase.GROUND -> R.string.phase_ground
    org.skytrack.sensors.FlightPhase.TAKEOFF -> R.string.phase_takeoff
    org.skytrack.sensors.FlightPhase.CLIMB -> R.string.phase_climb
    org.skytrack.sensors.FlightPhase.CRUISE -> R.string.phase_cruise
    org.skytrack.sensors.FlightPhase.DESCENT -> R.string.phase_descent
    org.skytrack.sensors.FlightPhase.LANDED -> R.string.phase_landed
})

/**
 * Manual visual fix: the user picks a landmark seen out of the window, the side,
 * and a rough distance. Candidates come from the bundled populated places
 * within VISUAL_FIX_SEARCH_RADIUS_M of the current estimate.
 */
@Composable
private fun VisualFixDialog(
    candidates: List<Pair<GeoData.Place, Double>>,
    settings: Settings,
    hebrew: Boolean,
    onConfirm: (GeoData.Place, Boolean?, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<GeoData.Place?>(candidates.firstOrNull()?.first) }
    var side by remember { mutableStateOf(1) }       // 0 = left, 1 = below, 2 = right
    var dist by remember { mutableStateOf(2) }       // 1 = near, 2 = mid, 3 = far (0 = below, implied by side)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.visual_fix)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.visual_fix_hint), style = MaterialTheme.typography.bodySmall)
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.visual_fix_none), color = MaterialTheme.colorScheme.error)
                } else {
                    Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                        for ((place, d) in candidates) {
                            Row(Modifier.fillMaxWidth().clickable { selected = place }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = selected === place, onClick = { selected = place })
                                Text(place.label(hebrew), Modifier.weight(1f), maxLines = 1)
                                Text(Format.distance(d, settings.distanceUnit), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.visual_side), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = side == 0, onClick = { side = 0 }, label = { Text(stringResource(R.string.side_left)) })
                    androidx.compose.material3.FilterChip(selected = side == 1, onClick = { side = 1 }, label = { Text(stringResource(R.string.side_below)) })
                    androidx.compose.material3.FilterChip(selected = side == 2, onClick = { side = 2 }, label = { Text(stringResource(R.string.side_right)) })
                }
                if (side != 1) {
                    Text(stringResource(R.string.visual_distance), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.FilterChip(selected = dist == 1, onClick = { dist = 1 }, label = { Text(stringResource(R.string.dist_near)) })
                        androidx.compose.material3.FilterChip(selected = dist == 2, onClick = { dist = 2 }, label = { Text(stringResource(R.string.dist_mid)) })
                        androidx.compose.material3.FilterChip(selected = dist == 3, onClick = { dist = 3 }, label = { Text(stringResource(R.string.dist_far)) })
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = selected != null,
                onClick = { selected?.let { onConfirm(it, if (side == 1) null else side == 2, if (side == 1) 0 else dist) } }
            ) { Text(stringResource(R.string.apply_fix)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

/**
 * Escalating "no GNSS" warning: yellow after 30 s, orange after 3 min, red after 10 min, with
 * the advice to hold the phone against the window; green relief once a fix is back.
 */
@Composable
private fun GnssBanner(m: FlightMetrics, modifier: Modifier) {
    val (color, text) = when {
        m.gnssRelief -> androidx.compose.ui.graphics.Color(0xFF2E7D32) to stringResource(R.string.gnss_relief)
        m.gnssWarnLevel >= 3 -> androidx.compose.ui.graphics.Color(0xFFC62828) to stringResource(R.string.gnss_warn3, Format.duration(m.gnssNoFixS))
        m.gnssWarnLevel == 2 -> androidx.compose.ui.graphics.Color(0xFFEF6C00) to stringResource(R.string.gnss_warn2, Format.duration(m.gnssNoFixS))
        else -> androidx.compose.ui.graphics.Color(0xFFF9A825) to stringResource(R.string.gnss_warn1)
    }
    Surface(modifier = modifier.clip(RoundedCornerShape(10.dp)), color = color) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}
