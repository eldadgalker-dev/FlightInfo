// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - LogsReplayFeedbackScreens
// Version 3.8
// Purpose : Flight-log manager (list, replay, share, delete, report), the
//           replay overlay (play / pause / speed / seek over the normal map
//           screen), and the in-app feedback form (bug / improvement /
//           flight log -> e-mail or GitHub issue).
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.skytrack.R
import org.skytrack.data.Settings
import org.skytrack.net.Feedback
import org.skytrack.service.FlightLogReader
import org.skytrack.service.LogSummary
import org.skytrack.service.ReplayEngine
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ Logs

@Composable
fun LogsScreen(
    files: () -> List<File>,
    onReplay: (File) -> Unit,
    onShare: (List<File>) -> Unit,
    onDelete: (File) -> Unit,
    onReport: (File) -> Unit,
    onImport: () -> Unit,
    importTick: Int,
    onRepair: suspend (File) -> File?,
    onBack: () -> Unit
) {
    var checkResult by remember { mutableStateOf<Pair<LogSummary, org.skytrack.service.LogCheck>?>(null) }
    var repairMsg by remember { mutableStateOf<String?>(null) }
    var summaries by remember { mutableStateOf<List<LogSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    val selected = remember { mutableStateOf(setOf<String>()) }      // file names
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(reload, importTick) {
        loading = true
        summaries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            files().mapNotNull { FlightLogReader.summarize(it) }.sortedByDescending { it.startMs }
        }
        selected.value = selected.value.filter { n -> summaries.any { it.file.name == n } }.toSet()
        loading = false
    }
    val sel = summaries.filter { it.file.name in selected.value }
    val groups = remember(summaries) { summaries.groupBy { FlightLogReader.groupKey(it) }.filter { it.value.size > 1 } }
    var mergeAsk by remember { mutableStateOf<List<LogSummary>?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = onImport) { Text(stringResource(R.string.logs_import)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
        }
        Text(stringResource(R.string.logs_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Recommendation only - nothing is merged without confirmation.
        for ((_, g) in groups) {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.logs_merge_suggest, g.size, "${g.first().originIata} \u2192 ${g.first().destinationIata}"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { mergeAsk = g }) { Text(stringResource(R.string.logs_merge_review)) }
                }
            }
        }
        checkResult?.let { (sum, c) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { checkResult = null },
                title = { Text(stringResource(R.string.logs_check_title)) },
                text = {
                    Text(stringResource(R.string.logs_check_report, c.rows, c.fixes, Format.durationHms(c.durationS), c.gaps, Format.durationHms(c.longestGapS),
                        c.staleRows, c.jumps, c.duplicateSeconds, c.phases.joinToString(" \u2192 ")) +
                        "\n\n" + stringResource(if (c.alreadyClean) R.string.logs_check_clean else if (c.needsRepair) R.string.logs_check_needs_repair else R.string.logs_check_ok),
                        style = MaterialTheme.typography.bodySmall)
                },
                confirmButton = {
                    if (c.needsRepair) TextButton(onClick = {
                        checkResult = null
                        scope.launch { val out = onRepair(sum.file); repairMsg = if (out != null) out.name else "failed"; reload++ }
                    }) { Text(stringResource(R.string.logs_repair)) }
                },
                dismissButton = { TextButton(onClick = { checkResult = null }) { Text(stringResource(R.string.replay_close)) } }
            )
        }
        repairMsg?.let { name ->
            androidx.compose.material3.AlertDialog(onDismissRequest = { repairMsg = null }, title = { Text(stringResource(R.string.logs_repair)) },
                text = { Text(stringResource(R.string.logs_repaired, name)) },
                confirmButton = { TextButton(onClick = { repairMsg = null }) { Text(stringResource(R.string.replay_close)) } })
        }
        mergeAsk?.let { g ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { mergeAsk = null },
                title = { Text(stringResource(R.string.logs_merge_confirm_title)) },
                text = { Text(stringResource(R.string.logs_merge_confirm_text, g.size) + "\n" + g.joinToString("\n") { "\u2022 " + Format.dateTime(Instant.ofEpochMilli(it.startMs).atZone(ZoneId.systemDefault())) + "  " + Format.durationHms(it.durationS) }) },
                confirmButton = { TextButton(onClick = {
                    mergeAsk = null
                    scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlightLogReader.merge(g.map { it.file }) }; selected.value = emptySet(); reload++ }
                }) { Text(stringResource(R.string.logs_merge_selected, g.size)) } },
                dismissButton = { TextButton(onClick = { mergeAsk = null }) { Text(stringResource(R.string.replay_close)) } }
            )
        }
        if (loading) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
            Text(stringResource(R.string.logs_loading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } else if (summaries.isEmpty()) {
            Text(stringResource(R.string.logs_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        // Compact list: one line per log, tap to select (multi-select allowed).
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(summaries, key = { it.file.name }) { s ->
                val isSel = s.file.name in selected.value
                Card(
                    Modifier.fillMaxWidth().clickable {
                        selected.value = if (isSel) selected.value - s.file.name else selected.value + s.file.name
                    },
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${s.originIata ?: "?"} \u2192 ${s.destinationIata ?: "?"}  ${s.flightNumber ?: ""}" + (if (isSel) "  \u2713" else ""),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(Format.dateTime(Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault())) +
                                "  \u00B7  " + stringResource(R.string.logs_duration) + " " + Format.durationHms(s.durationS) +
                                "  \u00B7  ${s.fixes} " + stringResource(R.string.logs_fixes) +
                                (s.appVersion?.let { "  \u00B7  v$it" } ?: "") +
                                (if (s.snapshot != null) "  \u00B7  " + stringResource(R.string.logs_has_map) else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Action bar for the selection.
        if (sel.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.logs_selected, sel.size), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { onReplay(sel.first().file) }, enabled = sel.size == 1, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_replay)) }
                    OutlinedButton(onClick = { onShare(sel.flatMap { listOfNotNull(it.file, it.snapshot) }) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_share)) }
                    OutlinedButton(onClick = { onReport(sel.first().file) }, enabled = sel.size == 1, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_report)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val one = sel.first()
                        scope.launch { val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.skytrack.service.FlightLogRepair.check(one.file) }; if (c != null) checkResult = Pair(one, c) }
                    }, enabled = sel.size == 1, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_check)) }
                    OutlinedButton(onClick = { mergeAsk = sel }, enabled = sel.size >= 2, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_merge_selected, sel.size)) }
                    OutlinedButton(onClick = { sel.forEach { onDelete(it.file); it.snapshot?.delete() }; selected.value = emptySet(); reload++ },
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_delete)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Replay

/**
 * Replay panel: replaces the metrics panel at the bottom of the map while a log is replayed.
 * Transport controls plus the key values of the replayed moment.
 */
@Composable
fun ReplayPanel(engine: ReplayEngine, settings: Settings, onClose: () -> Unit) {
    val playing by engine.playing.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val speed by engine.speed.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    val m by engine.metrics.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }
    // Always English, LTR, fixed columns: unambiguous and steady at 600x.
    val ctx = LocalContext.current
    val en = remember(ctx) { englishContext(ctx) }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(en.getString(R.string.replay_title, engine.summary?.let { "${it.originIata} \u2192 ${it.destinationIata} ${it.flightNumber ?: ""}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(if (playing) en.getString(R.string.replay_playing) else en.getString(R.string.replay_paused),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            m?.let { fm ->
                val e = fm.estimate
                // Position quality for the badge (shared by the rows below).
                val fresh = e.mode == org.skytrack.fusion.FusionMode.GNSS_TRACKING
                val good = fresh && e.sensorLevel == 3
                val badgeColor = if (good) androidx.compose.ui.graphics.Color(0xFF2E7D32) else if (fresh) androidx.compose.ui.graphics.Color(0xFFF57C00) else androidx.compose.ui.graphics.Color(0xFFC62828)
                // Three columns per row: nothing is cut.
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue(en.getString(R.string.utc_time), Format.time(fm.nowUtc.atZone(java.time.ZoneOffset.UTC), true), modifier = Modifier.weight(1f), accent = Accent.time)
                    LabeledValue(en.getString(R.string.remaining_short), if (fm.freeRecording) "--" else Format.distance(fm.remainingM, settings.distanceUnit), e.positionConfidence, modifier = Modifier.weight(1f), accent = Accent.distance)
                    LabeledValue(en.getString(R.string.ground_speed), Format.speed(e.groundSpeedMps, settings.speedUnit), e.speedConfidence, modifier = Modifier.weight(1f), accent = Accent.motion)
                }
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue(en.getString(R.string.altitude), Format.altitude(e.altM, settings.altitudeUnit), e.altitudeConfidence, modifier = Modifier.weight(1f), accent = Accent.motion)
                    LabeledValue(en.getString(R.string.phase), en.getString(when (e.phase) { org.skytrack.sensors.FlightPhase.GROUND -> R.string.phase_ground; org.skytrack.sensors.FlightPhase.TAKEOFF -> R.string.phase_takeoff; org.skytrack.sensors.FlightPhase.CLIMB -> R.string.phase_climb; org.skytrack.sensors.FlightPhase.CRUISE -> R.string.phase_cruise; org.skytrack.sensors.FlightPhase.DESCENT -> R.string.phase_descent; org.skytrack.sensors.FlightPhase.LANDED -> R.string.phase_landed }), modifier = Modifier.weight(1f))
                    LabeledValue(en.getString(R.string.satellites), if (fresh) "${e.satsUsed}/${e.satsVisible}" else "--", modifier = Modifier.weight(1f), accent = Accent.motion)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    LabeledValue(en.getString(R.string.gnss_accuracy_short), if (e.sigmaAlongM < 1000) "\u00B1${e.sigmaAlongM.toInt()} m" else "\u00B1${String.format(java.util.Locale.US, "%.1f", e.sigmaAlongM / 1000)} km", modifier = Modifier.weight(1f), accent = Accent.motion)
                    Column(Modifier.weight(1f)) {
                        Text(en.getString(R.string.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (good) "GPS good" else if (fresh) "GPS weak" else "estimated", style = MaterialTheme.typography.labelMedium, color = badgeColor,
                            modifier = Modifier.background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.weight(1f))
                }
                // Elapsed log time.
                engine.summary?.let { sum ->
                    Text("${en.getString(R.string.log_time)}: ${Format.durationHms((fm.nowUtc.toEpochMilli() - sum.startMs) / 1000)} ${en.getString(R.string.of)} ${Format.durationHms(sum.durationS)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Slider(
                value = seeking ?: progress,
                onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { engine.seek(it) }; seeking = null },
                modifier = Modifier.fillMaxWidth().height(28.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (playing) engine.pause() else engine.play() }, modifier = Modifier.weight(1.3f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text(en.getString(if (playing) R.string.replay_pause else R.string.replay_play))
                }
                for (x in listOf(30, 120, 600)) {
                    FilterChip(selected = speed == x, onClick = { engine.setSpeed(x) }, label = { Text("${x}\u00D7") }, modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = { engine.stop(); onClose() }, modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(en.getString(R.string.replay_close)) }
            }
            error?.let { Text(en.getString(R.string.replay_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
    }
}

// ------------------------------------------------------------------ Feedback

@Composable
fun FeedbackScreen(settings: Settings, latestLog: File?, latestSnapshot: File?, preselectedLog: File?, onBack: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(if (preselectedLog != null) Feedback.Kind.FLIGHT_LOG else Feedback.Kind.BUG) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var attachLog by remember { mutableStateOf(preselectedLog != null || latestLog != null) }
    var status by remember { mutableStateOf<String?>(null) }
    val logFile = preselectedLog ?: latestLog
    val noApp = stringResource(R.string.feedback_no_app)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == Feedback.Kind.BUG, onClick = { kind = Feedback.Kind.BUG }, label = { Text(stringResource(R.string.feedback_bug)) })
            FilterChip(selected = kind == Feedback.Kind.IMPROVEMENT, onClick = { kind = Feedback.Kind.IMPROVEMENT }, label = { Text(stringResource(R.string.feedback_improvement)) })
            FilterChip(selected = kind == Feedback.Kind.FLIGHT_LOG, onClick = { kind = Feedback.Kind.FLIGHT_LOG }, label = { Text(stringResource(R.string.feedback_log)) })
        }
        OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text(stringResource(R.string.feedback_subject)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(stringResource(R.string.feedback_body)) },
            minLines = 5, modifier = Modifier.fillMaxWidth())
        if (logFile != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feedback_attach_log), style = MaterialTheme.typography.bodyLarge)
                    Text(logFile.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = attachLog, onCheckedChange = { attachLog = it })
            }
        }
        Text(Feedback.deviceInfo(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(4.dp))
        val attachments = if (attachLog && logFile != null) listOfNotNull(logFile, latestSnapshot?.takeIf { preselectedLog == null || it.name.startsWith(logFile.nameWithoutExtension) }) else emptyList()
        Button(onClick = { if (!Feedback.sendEmail(context, kind, title, body, attachments)) status = noApp }, enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feedback_send_email)) }
        OutlinedButton(onClick = { if (!Feedback.openGithubIssue(context, kind, title, body)) status = noApp }, enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feedback_open_issue)) }
        Text(stringResource(R.string.feedback_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
