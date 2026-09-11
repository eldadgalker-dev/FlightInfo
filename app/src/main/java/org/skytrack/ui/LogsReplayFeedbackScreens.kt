// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - LogsReplayFeedbackScreens
// Version 1.0
// Purpose : Flight-log manager (list, replay, share, delete, report), the
//           replay overlay (play / pause / speed / seek over the normal map
//           screen), and the in-app feedback form (bug / improvement /
//           flight log -> e-mail or GitHub issue).
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
    onBack: () -> Unit
) {
    var summaries by remember { mutableStateOf<List<LogSummary>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { summaries = files().mapNotNull { FlightLogReader.summarize(it) } }
    val fmt = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.US) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        if (summaries.isEmpty()) {
            Text(stringResource(R.string.logs_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(summaries, key = { it.file.name }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${s.originIata ?: "?"} \u2192 ${s.destinationIata ?: "?"}  ${s.flightNumber ?: ""}",
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(fmt.format(Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault())) +
                                "  \u00B7  " + Format.duration(s.durationS) +
                                "  \u00B7  ${s.fixes} " + stringResource(R.string.logs_fixes) +
                                (s.appVersion?.let { "  \u00B7  v$it" } ?: "") +
                                (if (s.snapshot != null) "  \u00B7  " + stringResource(R.string.logs_has_map) else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onReplay(s.file) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_replay)) }
                            OutlinedButton(onClick = { onShare(listOfNotNull(s.file, s.snapshot)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_share)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onReport(s.file) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_report)) }
                            OutlinedButton(onClick = { onDelete(s.file); s.snapshot?.delete(); reload++ }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.logs_delete)) }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Replay

/** Transport controls drawn over the normal map screen while a log is replayed. */
@Composable
fun ReplayOverlay(engine: ReplayEngine, onClose: () -> Unit) {
    val playing by engine.playing.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val speed by engine.speed.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }

    Box(Modifier.fillMaxSize()) {
        Surface(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 150.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.replay_title, engine.summary?.let { "${it.originIata} \u2192 ${it.destinationIata}" } ?: ""),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { engine.stop(); onClose() }) { Text(stringResource(R.string.replay_close)) }
                }
                error?.let { Text(stringResource(R.string.replay_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Slider(
                    value = seeking ?: progress,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { engine.seek(it) }; seeking = null },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (playing) engine.pause() else engine.play() }) {
                        Text(stringResource(if (playing) R.string.replay_pause else R.string.replay_play))
                    }
                    for (x in listOf(30, 120, 600)) {
                        FilterChip(selected = speed == x, onClick = { engine.setSpeed(x) }, label = { Text("${x}\u00D7") })
                    }
                }
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
