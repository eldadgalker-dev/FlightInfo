// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - ReplayEngine
// Version 1.0
// Purpose : Play a recorded flight log back through a fresh Estimator at
//           30x .. 600x real time, publishing FlightMetrics exactly like the
//           live engine so the normal map screen can display it. Because the
//           CURRENT estimator is used, a replay also shows how the latest
//           algorithm would have behaved on an old flight.
// =============================================================
package org.skytrack.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skytrack.data.Airport
import org.skytrack.data.AirportRepository
import org.skytrack.fusion.Estimator
import org.skytrack.fusion.FlightMetrics
import org.skytrack.fusion.Metrics
import org.skytrack.route.Route
import java.io.File

class ReplayEngine(private val airports: AirportRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _metrics = MutableStateFlow<FlightMetrics?>(null)
    val metrics: StateFlow<FlightMetrics?> = _metrics
    private val _progress = MutableStateFlow(0f)          // 0..1
    val progress: StateFlow<Float> = _progress
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _speed = MutableStateFlow(120)             // x real time
    val speed: StateFlow<Int> = _speed
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    var summary: LogSummary? = null
        private set

    private var rows: List<ReplayRow> = emptyList()
    private var index = 0
    private var est: Estimator? = null
    private var origin: Airport? = null
    private var destination: Airport? = null
    private var takeoffMs: Long? = null

    /** Load a log; returns false with `error` set if it cannot be replayed. */
    suspend fun load(file: File): Boolean = withContext(Dispatchers.IO) {
        stop()
        val sum = FlightLogReader.summarize(file)
        val o = sum?.originIata?.let { airports.byCode(it) }
        val d = sum?.destinationIata?.let { airports.byCode(it) }
        if (sum == null || o == null || d == null) { _error.value = "unknown airports in log"; return@withContext false }
        summary = sum; origin = o; destination = d
        rows = FlightLogReader.rows(file)
        if (rows.isEmpty()) { _error.value = "empty log"; return@withContext false }
        _error.value = null
        reset()
        true
    }

    private fun reset() {
        val o = origin ?: return; val d = destination ?: return
        est = Estimator(Route(o.point, d.point))
        index = 0; takeoffMs = null
        _progress.value = 0f
        step(0)                   // publish the first row immediately
    }

    fun play() {
        if (_playing.value || rows.isEmpty()) return
        _playing.value = true
        job = scope.launch {
            while (isActive && index < rows.size) {
                val t0 = rows[index].timeMs
                step(index)
                index++
                if (index >= rows.size) { _playing.value = false; break }
                val dtMs = (rows[index].timeMs - t0).coerceIn(0L, 60_000L)
                delay(maxOf(5L, dtMs / _speed.value))
            }
        }
    }

    fun pause() { job?.cancel(); job = null; _playing.value = false }

    fun setSpeed(x: Int) { _speed.value = x.coerceIn(1, 3600) }

    /** Jump to a fraction of the flight: replays silently from the start to that point. */
    fun seek(fraction: Float) {
        val wasPlaying = _playing.value
        pause()
        val target = (fraction.coerceIn(0f, 1f) * (rows.size - 1)).toInt()
        reset()
        for (i in 0 until target) step(i)
        index = target
        if (wasPlaying) play()
    }

    fun stop() { pause(); rows = emptyList(); _metrics.value = null; summary = null; est = null }

    private fun step(i: Int) {
        val e = est ?: return
        val o = origin ?: return; val d = destination ?: return
        val r = rows[i]
        r.gnss?.let { e.onGnss(it, r.phase) }
        r.gyro?.let { e.onGyro(it) }
        if (takeoffMs == null && r.phase != org.skytrack.sensors.FlightPhase.GROUND) takeoffMs = r.timeMs
        val pe = e.tick(r.timeMs, r.phase, takeoffMs, r.liveTracking)
        _metrics.value = Metrics.compute(pe, e.route, e.plannedRoute, e.actualTrack.toList(), !r.liveTracking, o, d, takeoffMs)
        _progress.value = if (rows.size > 1) i.toFloat() / (rows.size - 1) else 1f
    }
}
