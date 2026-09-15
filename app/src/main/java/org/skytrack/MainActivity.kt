// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MainActivity
// Version 4.9
// Purpose : Single-activity host. Simple state-based navigation between
//           Setup / Map / Metrics / Settings, runtime permission requests,
//           and foreground-service start/stop tied to the active flight.
// =============================================================
package org.skytrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import org.skytrack.ui.FeedbackScreen
import org.skytrack.ui.HelpScreen
import org.skytrack.ui.LogsScreen
import org.skytrack.ui.ReplayPanel
import kotlinx.coroutines.launch
import org.skytrack.ui.MapScreen
import org.skytrack.ui.ScanScreen
import org.skytrack.ui.MetricsScreen
import org.skytrack.ui.SettingsScreen
import org.skytrack.ui.SetupScreen
import org.skytrack.ui.SkyTrackTheme

private enum class Screen { SETUP, MAP, METRICS, SETTINGS, SCAN, HELP, LOGS, REPLAY, FEEDBACK }

class MainActivity : AppCompatActivity() {

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

    var screen by rememberSaveable { mutableStateOf(Screen.MAP) }   // the map is the home screen, flight or not
    var pendingPlan by remember { mutableStateOf<FlightPlan?>(null) }
    var scanned by remember { mutableStateOf<BoardingPass?>(null) }
    val replay = remember { org.skytrack.service.ReplayEngine(app.airports) }
    var reportLog by remember { mutableStateOf<java.io.File?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val resourceMonitor = remember { org.skytrack.data.ResourceMonitor(context) }
    var importTick by remember { mutableStateOf(0) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) {
            // Copy an external CSV log (from another phone or an e-mail) into the logs folder.
            try {
                var name: String? = null
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
                val fileName = (name ?: "imported_${System.currentTimeMillis()}.csv").substringAfterLast('/')
                val logsDir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
                logsDir.mkdirs()
                val dest = java.io.File(logsDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
                importTick++
            } catch (e: Exception) { }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Start regardless of the result: without location the engine runs in PREDICTED_ONLY mode.
        pendingPlan?.let { p ->
            if (p.free) { if (app.engine.startFreeRecording()) TrackingService.start(context) }
            else if (app.engine.start(p)) { if (!p.estimateOnly) TrackingService.start(context); screen = Screen.MAP }
            pendingPlan = null
        }
    }

    fun startFlight(p: FlightPlan) {
        if (p.estimateOnly) {
            // No sensors, no service, no permissions needed.
            TrackingService.stop(context)
            if (app.engine.start(p)) screen = Screen.MAP
            return
        }
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

    // Silent update check once per launch, only when allowed and online. Result shown as a line in Setup / Settings.
    val updateInfo by app.updateInfo.collectAsStateWithLifecycle()
    LaunchedEffect(settings.useNetwork) {
        if (settings.useNetwork && app.updateInfo.value == null && app.isOnline()) {
            try { app.updateInfo.value = org.skytrack.net.Updater(context).check() } catch (e: Exception) { }
        }
    }

    // Keep the service alive whenever a flight is active and permissions allow it.
    LaunchedEffect(active, plan?.estimateOnly) {
        if (active && plan?.estimateOnly != true &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
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
            // A crash recorded by the previous run: show it once and offer to send it.
            val crashPrefs = context.getSharedPreferences("skytrack", android.content.Context.MODE_PRIVATE)
            var crashText by remember { mutableStateOf(crashPrefs.getString("last_crash", null)) }
            crashText?.let { txt ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(R.string.crash_title)) },
                    text = { Text(txt.take(1200), style = MaterialTheme.typography.bodySmall) },
                    confirmButton = { TextButton(onClick = {
                        org.skytrack.net.Feedback.sendEmail(context, org.skytrack.net.Feedback.Kind.BUG, "crash report", txt, emptyList())
                        crashPrefs.edit().remove("last_crash").apply(); crashText = null
                    }) { Text(stringResource(R.string.crash_send)) } },
                    dismissButton = { TextButton(onClick = { crashPrefs.edit().remove("last_crash").apply(); crashText = null }) { Text(stringResource(R.string.crash_dismiss)) } }
                )
            }
            when (screen) {
                Screen.SETUP -> SetupScreen(
                    airports = app.airports,
                    existing = plan?.takeIf { !it.free },
                    suggestedOrigin = remember { app.engine.suggestOrigin() },
                    onStart = { startFlight(it) },
                    onClear = { TrackingService.stop(context); app.engine.clearFlight(); screen = Screen.SETUP },
                    onBack = { screen = Screen.MAP },
                    onScan = { screen = Screen.SCAN },
                    onHelp = { screen = Screen.HELP },
                    prefill = scanned,
                    updateAvailable = updateInfo?.takeIf { it.isNewer }?.latestVersion,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onConfirmGround = { app.engine.confirmOnGround() },
                    groundStatus = metrics?.groundReference?.let { gr ->
                        androidx.compose.ui.res.stringResource(R.string.ground_ref_done2,
                            gr.gnssBiasM?.let { org.skytrack.ui.Format.altitude(it, settings.altitudeUnit) } ?: "--", gr.satsUsed, gr.hAccM?.toInt() ?: 0)
                    }
                )
                Screen.SCAN -> {
                    BackHandler { screen = Screen.SETUP }
                    ScanScreen(onResult = { scanned = it; screen = Screen.SETUP }, onCancel = { screen = Screen.SETUP })
                }
                Screen.MAP -> {
                    BackHandler(enabled = false) {}
                    val recording by app.engine.recording.collectAsStateWithLifecycle()
                    val initialLoc = remember {
                        try { org.skytrack.sensors.GnssSource(context).lastKnown()?.let { Pair(it.latitude, it.longitude) } } catch (e: Exception) { null }
                            ?: app.stores.loadGroundFix()?.let { Pair(it.lat, it.lon) }
                    }
                    MapScreen(
                        recording = recording,
                        initialLocation = initialLoc,
                        onToggleRecording = {
                            if (recording) app.engine.setRecording(false)
                            else if (plan == null) {
                                // REC without a flight: free recording (sensors + log), needs location permission.
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    if (app.engine.startFreeRecording()) TrackingService.start(context)
                                } else { pendingPlan = FlightPlan.freeRecording(); permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
                            } else app.engine.setRecording(true)
                        },
                        metrics = metrics, settings = settings, night = night,
                        onOpenMetrics = { screen = Screen.METRICS },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenSetup = { screen = Screen.SETUP },
                        onOpenHelp = { screen = Screen.HELP },
                        visualFixCandidates = { app.engine.visualFixCandidates() },
                        onVisualFix = { place, side, dist -> app.engine.applyVisualFix(place.point, side, dist) },
                        onConfirmGround = { app.engine.confirmOnGround() },
                        onSnapshot = { bmp -> app.engine.logger.saveSnapshot(bmp) },
                        initialZoom = remember { app.stores.loadLastZoom() },
                        onZoomChanged = { z -> app.stores.saveLastZoom(z) },
                        panelExpandedInitial = remember { app.stores.loadPanelExpanded() },
                        onPanelExpandedChanged = { e -> app.stores.savePanelExpanded(e) },
                        hebrew = app.hebrew,
                        onToggleEstimateOnly = {
                            val nowEstimate = !(metrics?.estimateOnly ?: false)
                            app.engine.setEstimateOnly(nowEstimate)
                            if (nowEstimate) TrackingService.stop(context)
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) TrackingService.start(context)
                        }
                    )
                }
                Screen.METRICS -> {
                    BackHandler { screen = Screen.MAP }
                    val baro by app.engine.baroAvailable.collectAsStateWithLifecycle()
                    var res by remember { mutableStateOf<org.skytrack.data.ResourceSnapshot?>(null) }
                    LaunchedEffect(Unit) { while (true) { res = resourceMonitor.snapshot(); kotlinx.coroutines.delay(5_000) } }
                    MetricsScreen(metrics, settings, baro, res) { screen = Screen.MAP }
                }
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.MAP }
                    var logCount by remember { mutableStateOf(app.engine.logger.allFiles().size) }
                    SettingsScreen(
                        settings,
                        onChange = {
                            app.stores.saveSettings(it); app.engine.logger.enabled = it.logFlights
                            if (it.language != settings.language) applyLanguage(it.language)
                        },
                        onExit = { keep ->
                            if (!keep) { TrackingService.stop(context); app.engine.stop() }
                            (context as? android.app.Activity)?.finishAffinity()
                        },
                        onBack = { screen = Screen.MAP },
                        onHelp = { screen = Screen.HELP },
                        onShareLog = { shareLatestLog(context, app) },
                        onDeleteLogs = { app.engine.logger.deleteAll(); logCount = 0 },
                        logCount = logCount,
                        aerial = remember { org.skytrack.map.AerialPack(context) },
                        updater = remember { org.skytrack.net.Updater(context) },
                        onLogs = { screen = Screen.LOGS },
                        onFeedback = { reportLog = null; screen = Screen.FEEDBACK }
                    )
                }
                Screen.LOGS -> {
                    BackHandler { screen = Screen.SETTINGS }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    LogsScreen(
                        files = { app.engine.logger.allFiles() },
                        onReplay = { f -> scope.launch { if (replay.load(f)) { replay.play(); screen = Screen.REPLAY } } },
                        onShare = { files -> shareFiles(context, files) },
                        onDelete = { f -> f.delete() },
                        onReport = { f -> reportLog = f; screen = Screen.FEEDBACK },
                        onImport = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                        importTick = importTick,
                        onBack = { screen = Screen.SETTINGS }
                    )
                }
                Screen.REPLAY -> {
                    BackHandler { replay.stop(); screen = Screen.LOGS }
                    val rm by replay.metrics.collectAsStateWithLifecycle()
                    MapScreen(
                        metrics = rm, settings = settings, night = night,
                        onOpenMetrics = {}, onOpenSettings = {}, onOpenSetup = {}, onOpenHelp = {},
                        visualFixCandidates = { emptyList() }, onVisualFix = { _, _, _ -> }, onConfirmGround = {},
                        onSnapshot = {}, initialZoom = app.stores.loadLastZoom(), onZoomChanged = {},
                        panelExpandedInitial = true, onPanelExpandedChanged = {},
                        hebrew = app.hebrew, onToggleEstimateOnly = {},
                        replayPanel = { ReplayPanel(replay, settings) { screen = Screen.LOGS } }
                    )
                }
                Screen.FEEDBACK -> {
                    BackHandler { screen = Screen.SETTINGS }
                    val latest = app.engine.logger.latestFile()
                    val snap = latest?.let { java.io.File(it.parentFile, it.nameWithoutExtension + "_map.png").takeIf { p -> p.exists() } }
                    FeedbackScreen(settings, latest, snap, reportLog) { screen = Screen.SETTINGS }
                }
                Screen.HELP -> {
                    BackHandler { screen = Screen.MAP }
                    HelpScreen { screen = Screen.MAP }
                }
            }
            if (screen == Screen.SETUP) {
                BackHandler { screen = Screen.MAP }
            }
        }
    }
}

/** Share the most recent CSV log through the system share sheet (FileProvider URI). */
private fun shareLatestLog(context: android.content.Context, app: SkyTrackApp) {
    val f = app.engine.logger.latestFile() ?: return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, f.name))
    } catch (e: Exception) {
        // No app able to receive the file; nothing else to do offline.
    }
}

/** Per-app language: "system" clears the override; otherwise a BCP-47 tag. Applies immediately (activity recreates). */
private fun applyLanguage(lang: String) {
    val locales = if (lang == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
    AppCompatDelegate.setApplicationLocales(locales)
}

/** Share one or more files (CSV log, PNG snapshot) through the system share sheet. */
private fun shareFiles(context: android.content.Context, files: List<java.io.File>) {
    val uris = files.filter { it.exists() }.map { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", it) }
    if (uris.isEmpty()) return
    try {
        val intent = android.content.Intent(if (uris.size > 1) android.content.Intent.ACTION_SEND_MULTIPLE else android.content.Intent.ACTION_SEND).apply {
            type = "*/*"
            if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, files[0].name))
    } catch (e: Exception) { }
}
