// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - SetupScreen
// Version 1.5
// Purpose : Flight plan entry: origin (auto-suggested from the last ground
//           fix), destination search, optional flight number and scheduled
//           departure. Starts or clears the active flight.
// =============================================================
package org.skytrack.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skytrack.R
import org.skytrack.data.Airport
import org.skytrack.data.AirportRepository
import org.skytrack.data.FlightPlan
import org.skytrack.scan.BoardingPass
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SetupScreen(
    airports: AirportRepository,
    existing: FlightPlan?,
    suggestedOrigin: Airport?,
    onStart: (FlightPlan) -> Unit,
    onClear: () -> Unit,
    onBack: (() -> Unit)?,
    onScan: () -> Unit,
    prefill: BoardingPass? = null
) {
    var origin by remember { mutableStateOf(existing?.let { airports.byCode(it.originIata) } ?: suggestedOrigin) }
    var destination by remember { mutableStateOf(existing?.let { airports.byCode(it.destinationIata) }) }
    var flightNumber by rememberSaveable { mutableStateOf(existing?.flightNumber ?: "") }
    var departure by rememberSaveable { mutableStateOf(existing?.scheduledDepartureMs?.let { ms ->
        val zone = ZoneId.of(origin?.tz ?: "UTC")
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
    } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var scanInfo by remember { mutableStateOf<String?>(null) }

    // Apply a scanned boarding pass once: origin, destination, flight number.
    val unknownAirport = stringResource(R.string.scan_unknown_airport)
    LaunchedEffect(prefill) {
        val bp = prefill ?: return@LaunchedEffect
        val o = airports.byCode(bp.fromIata)
        val d = airports.byCode(bp.toIata)
        if (o != null) origin = o
        if (d != null) destination = d
        flightNumber = bp.flightNumber
        scanInfo = if (o == null || d == null) unknownAirport.format(bp.fromIata, bp.toIata)
        else "${bp.flightNumber}  ${bp.fromIata} \u2192 ${bp.toIata}" + (bp.date?.let { "  $it" } ?: "") +
                (if (bp.seat.isNotEmpty()) "  ${bp.seat}" else "")
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.flight_setup), style = MaterialTheme.typography.headlineSmall)

        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_boarding_pass)) }
        scanInfo?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

        AirportField(stringResource(R.string.origin), origin, airports) { origin = it }
        AirportField(stringResource(R.string.destination), destination, airports) { destination = it }

        OutlinedTextField(
            value = flightNumber, onValueChange = { flightNumber = it.uppercase().take(8) },
            label = { Text(stringResource(R.string.flight_number)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = departure, onValueChange = { departure = it.take(5) },
            label = { Text(stringResource(R.string.scheduled_departure)) },
            supportingText = { Text(stringResource(R.string.scheduled_departure_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val errTime = stringResource(R.string.error_time_format)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val o = origin ?: return@Button
                val d = destination ?: return@Button
                val depMs = parseDeparture(departure, o.tz)
                if (departure.isNotBlank() && depMs == null) { error = errTime; return@Button }
                error = null
                val keepTakeoff = existing?.let { ex -> ex.takeoffMs?.takeIf { ex.originIata == o.iata && ex.destinationIata == d.iata } }
                onStart(FlightPlan(o.iata, d.iata, flightNumber.trim(), depMs, keepTakeoff))
            },
            enabled = origin != null && destination != null && origin?.iata != destination?.iata,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(if (existing == null) R.string.start_flight else R.string.update_flight)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (existing != null) OutlinedButton(onClick = onClear) { Text(stringResource(R.string.end_flight)) }
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AirportField(label: String, selected: Airport?, airports: AirportRepository, onSelect: (Airport?) -> Unit) {
    // Query text is owned by this field; it is NOT keyed on the selection, otherwise every
    // keystroke that clears the selection would also reset the text being typed.
    var query by rememberSaveable { mutableStateOf(selected?.let { "${it.iata} ${it.city}" } ?: "") }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (selected != null) { query = "${selected.iata} ${selected.city}"; editing = false }
    }
    val results = remember(query, editing) { if (editing && query.trim().length >= 2) airports.search(query) else emptyList() }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { text ->
                query = text
                editing = true
                if (selected != null) onSelect(null)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (selected != null) Text(selected.name, maxLines = 1)
                else Text(stringResource(R.string.airport_search_hint))
            }
        )
        if (results.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    for (a in results) {
                        Text(
                            a.label,
                            Modifier.fillMaxWidth().clickable { onSelect(a) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        } else if (editing && query.trim().length >= 2 && selected == null) {
            Text(stringResource(R.string.airport_no_results), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }
}

/** Parse "HH:mm" in the origin zone as today's departure; null if blank or invalid. */
private fun parseDeparture(text: String, tz: String): Long? {
    if (text.isBlank()) return null
    return try {
        val t = LocalTime.parse(text.trim(), DateTimeFormatter.ofPattern("H:mm", Locale.US))
        val zone = try { ZoneId.of(tz) } catch (e: Exception) { ZoneId.of("UTC") }
        LocalDate.now(zone).atTime(t).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) { null }
}
