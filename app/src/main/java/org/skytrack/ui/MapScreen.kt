// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapScreen
// Version 1.1
// Purpose : Full-screen MapLibre view hosted in Compose, with floating
//           zoom / fit / recenter / orientation controls, a status strip
//           (GNSS, mode, fix age) and a collapsible metrics panel.
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.List
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
import org.skytrack.data.Settings
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
    onOpenSetup: () -> Unit
) {
    val mapView = rememberMapViewWithLifecycle()
    var controller by remember { mutableStateOf<MapController?>(null) }
    var trackUp by rememberSaveable { mutableStateOf(settings.trackUp) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                if (controller == null) {
                    mv.getMapAsync { map ->
                        val c = MapController(mv.context, map)
                        c.followEnabled = settings.autoFollow
                        c.trackUp = trackUp
                        c.setRotateGestures(settings.rotateGestures)
                        controller = c
                    }
                }
            }
        )

        LaunchedEffect(controller, night) { controller?.setPalette(if (night) MapStyle.NIGHT else MapStyle.DAY) }
        LaunchedEffect(controller, settings) {
            controller?.followEnabled = settings.autoFollow
            controller?.setRotateGestures(settings.rotateGestures)
        }
        LaunchedEffect(controller, trackUp) { controller?.trackUp = trackUp }
        LaunchedEffect(controller, metrics) { if (metrics != null) controller?.update(metrics) }

        // -- Status strip --
        StatusStrip(metrics, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp))

        // -- Floating controls (right edge) --
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallFloatingActionButton(onClick = { controller?.zoomIn() }) { Icon(Icons.Filled.Add, stringResource(R.string.zoom_in)) }
            SmallFloatingActionButton(onClick = { controller?.zoomOut() }) { Icon(Icons.Filled.Remove, stringResource(R.string.zoom_out)) }
            SmallFloatingActionButton(onClick = { controller?.fitRoute() }) { Icon(Icons.Filled.Fullscreen, stringResource(R.string.fit_route)) }
            SmallFloatingActionButton(onClick = { controller?.recenter() }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.recenter)) }
            SmallFloatingActionButton(onClick = { trackUp = !trackUp; controller?.recenter() }) {
                Icon(if (trackUp) Icons.Filled.Flight else Icons.Filled.Explore, stringResource(R.string.orientation))
            }
        }

        // -- Left controls: settings, metrics, setup --
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallFloatingActionButton(onClick = onOpenMetrics) { Icon(Icons.Filled.List, stringResource(R.string.metrics)) }
            SmallFloatingActionButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            SmallFloatingActionButton(onClick = onOpenSetup) { Icon(Icons.Filled.Flight, stringResource(R.string.flight_setup)) }
        }

        // -- Metrics panel --
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp)) {
            MetricsPanel(metrics, settings, panelExpanded) { panelExpanded = !panelExpanded }
        }
    }
}

@Composable
private fun StatusStrip(m: FlightMetrics?, modifier: Modifier) {
    Surface(modifier = modifier.clip(RoundedCornerShape(12.dp)), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (m == null) {
                Text(stringResource(R.string.no_flight), style = MaterialTheme.typography.labelMedium)
            } else {
                val e = m.estimate
                val gnss = when (e.gnssQuality) {
                    GnssQuality.GOOD -> stringResource(R.string.gnss_good, e.satsUsed)
                    GnssQuality.DEGRADED -> stringResource(R.string.gnss_degraded, e.satsUsed)
                    GnssQuality.NONE -> stringResource(R.string.gnss_none)
                }
                val mode = when (e.mode) {
                    FusionMode.GNSS_TRACKING -> stringResource(R.string.mode_tracking)
                    FusionMode.ROUTE_CONSTRAINED -> stringResource(R.string.mode_constrained)
                    FusionMode.PREDICTED_ONLY -> stringResource(R.string.mode_predicted)
                    FusionMode.OFF_ROUTE -> stringResource(R.string.mode_off_route)
                }
                Text(gnss, style = MaterialTheme.typography.labelMedium)
                Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.fix_age, Format.ageSeconds(e.lastFixAgeMs)), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MetricsPanel(m: FlightMetrics?, s: Settings, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onToggle() },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    ) {
        if (m == null) {
            Text(stringResource(R.string.no_flight_hint), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            return@Surface
        }
        val e = m.estimate
        Column(Modifier.padding(12.dp)) {
            // Row 1: the three headline values
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue(stringResource(R.string.remaining), Format.distance(m.remainingM, s.distanceUnit), e.positionConfidence, big = true)
                LabeledValue(stringResource(R.string.ete), Format.duration(m.eteS), m.eteConfidence, big = true)
                LabeledValue(stringResource(R.string.eta_local, m.destination.iata), Format.time(m.etaAtDestination, s.use24h), m.eteConfidence, big = true)
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue(stringResource(R.string.ground_speed), Format.speed(e.groundSpeedMps, s.speedUnit), e.speedConfidence)
                    LabeledValue(stringResource(R.string.altitude), Format.altitude(e.altM, s.altitudeUnit), e.altitudeConfidence)
                    LabeledValue(stringResource(R.string.track), Format.heading(e.trackDeg), e.trackConfidence)
                    LabeledValue(stringResource(R.string.vertical_rate), Format.verticalRate(e.verticalRateMps, s.altitudeUnit), e.altitudeConfidence)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue(stringResource(R.string.flown), "${Format.distance(m.flownM, s.distanceUnit)} (${Format.percent(m.percentComplete)})", e.positionConfidence)
                    LabeledValue(stringResource(R.string.elapsed), Format.duration(m.elapsedS))
                    LabeledValue(stringResource(R.string.time_at, m.origin.iata), Format.time(m.nowAtOrigin, s.use24h))
                    LabeledValue(stringResource(R.string.time_at, m.destination.iata), Format.time(m.nowAtDestination, s.use24h))
                }
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
