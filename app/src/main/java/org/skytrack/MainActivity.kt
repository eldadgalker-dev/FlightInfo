// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - MainActivity
// Version 1.5
// Purpose : Single-activity host. Simple state-based navigation between
//           Setup / Map / Metrics / Settings, runtime permission requests,
//           and foreground-service start/stop tied to the active flight.
// =============================================================
package org.skytrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skytrack.data.FlightPlan
import org.skytrack.data.ThemeMode
import org.skytrack.service.TrackingService
import org.skytrack.scan.BoardingPass
import org.skytrack.ui.MapScreen
import org.skytrack.ui.ScanScreen
import org.skytrack.ui.MetricsScreen
import org.skytrack.ui.SettingsScreen
import org.skytrack.ui.SetupScreen
import org.skytrack.ui.SkyTrackTheme

private enum class Screen { SETUP, MAP, METRICS, SETTINGS, SCAN }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SkyTrackApp
        setContent { Root(app) }
    }
}

@Composable
private fun Root(app: SkyTrackApp) {
    val settings by app.stores.settings.collectAsStateWithLifecycle()
    val metrics by app.engine.metrics.collectAsStateWithLifecycle()
    val plan by app.stores.plan.collectAsStateWithLifecycle()
    val active by app.engine.active.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(if (plan == null) Screen.SETUP else Screen.MAP) }
    var pendingPlan by remember { mutableStateOf<FlightPlan?>(null) }
    var scanned by remember { mutableStateOf<BoardingPass?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Start regardless of the result: without location the engine runs in PREDICTED_ONLY mode.
        pendingPlan?.let { p ->
            if (app.engine.start(p)) { TrackingService.start(context); screen = Screen.MAP }
            pendingPlan = null
        }
    }

    fun startFlight(p: FlightPlan) {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (app.engine.start(p)) { TrackingService.start(context); screen = Screen.MAP }
        } else {
            pendingPlan = p
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Keep the service alive whenever a flight is active and permissions allow it.
    LaunchedEffect(active) {
        if (active && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            TrackingService.start(context)
        }
    }

    val night = when (settings.theme) {
        ThemeMode.NIGHT -> true
        ThemeMode.DAY -> false
        ThemeMode.AUTO -> {
            // Day between 07:00 and 19:00 at the destination (or device zone before a plan exists).
            val h = metrics?.nowAtDestination?.hour ?: java.time.LocalTime.now().hour
            h < 7 || h >= 19
        }
    }

    SkyTrackTheme(night = night) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.SETUP -> SetupScreen(
                    airports = app.airports,
                    existing = plan,
                    suggestedOrigin = remember { app.engine.suggestOrigin() },
                    onStart = { startFlight(it) },
                    onClear = { TrackingService.stop(context); app.engine.clearFlight(); screen = Screen.SETUP },
                    onBack = if (plan != null) ({ screen = Screen.MAP }) else null,
                    onScan = { screen = Screen.SCAN },
                    prefill = scanned
                )
                Screen.SCAN -> {
                    BackHandler { screen = Screen.SETUP }
                    ScanScreen(onResult = { scanned = it; screen = Screen.SETUP }, onCancel = { screen = Screen.SETUP })
                }
                Screen.MAP -> {
                    BackHandler(enabled = false) {}
                    MapScreen(
                        metrics = metrics, settings = settings, night = night,
                        onOpenMetrics = { screen = Screen.METRICS },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenSetup = { screen = Screen.SETUP }
                    )
                }
                Screen.METRICS -> {
                    BackHandler { screen = Screen.MAP }
                    MetricsScreen(metrics, settings) { screen = Screen.MAP }
                }
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.MAP }
                    SettingsScreen(settings, onChange = { app.stores.saveSettings(it) }) { screen = Screen.MAP }
                }
            }
            if (screen == Screen.SETUP && plan != null) {
                BackHandler { screen = Screen.MAP }
            }
        }
    }
}
