// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - FlightEngine
// Version 1.1
// Purpose : Application-scoped coordinator. Owns the Route, Estimator and
//           FlightPhaseDetector for the active flight, consumes sensor
//           flows (started by TrackingService), ticks the estimator at
//           ENGINE_TICK_MS and publishes FlightMetrics as a StateFlow.
//           Emits a first estimate synchronously on start() so the UI is
//           never blank.
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
import org.skytrack.Parameters
import org.skytrack.data.Airport
import org.skytrack.data.AirportRepository
import org.skytrack.data.FlightPlan
import org.skytrack.data.GroundFix
import org.skytrack.data.SavedEstimate
import org.skytrack.data.Stores
import org.skytrack.fusion.Estimator
import org.skytrack.fusion.FlightMetrics
import org.skytrack.fusion.Metrics
import org.skytrack.route.GeoPoint
import org.skytrack.route.Route
import org.skytrack.sensors.BaroSample
import org.skytrack.sensors.FlightPhase
import org.skytrack.sensors.FlightPhaseDetector
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample
import org.skytrack.sensors.GyroSample

class FlightEngine(private val airports: AirportRepository, private val stores: Stores) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val _metrics = MutableStateFlow<FlightMetrics?>(null)
    val metrics: StateFlow<FlightMetrics?> = _metrics

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    var route: Route? = null
        private set
    var origin: Airport? = null
        private set
    var destination: Airport? = null
        private set

    private var estimator: Estimator? = null
    private var phaseDetector = FlightPhaseDetector()
    private var plan: FlightPlan? = null
    private var lastPersistMs = 0L
    private var lastGnssVRate = 0.0
    private var lastGnssAltMs = 0L
    private var lastGnssAlt = 0.0

    /** Start (or restart) tracking for a plan. Returns false if airports are unknown. */
    @Synchronized
    fun start(p: FlightPlan): Boolean {
        val o = airports.byCode(p.originIata) ?: return false
        val dst = airports.byCode(p.destinationIata) ?: return false
        stop()
        plan = p; origin = o; destination = dst
        val r = Route(o.point, dst.point)
        route = r
        val est = Estimator(r)
        estimator = est
        phaseDetector = FlightPhaseDetector()

        // Restore the last estimate if it belongs to this flight and is recent (< 12 h).
        val saved = stores.loadEstimate()
        if (saved != null && System.currentTimeMillis() - saved.timeMs < 12 * 3600_000L && p.takeoffMs != null) {
            est.restore(saved.alongM, saved.crossM, saved.speedMps, saved.trackDeg, saved.altM, saved.timeMs)
            val ph = try { FlightPhase.valueOf(saved.phase) } catch (e: Exception) { FlightPhase.CRUISE }
            phaseDetector.restore(ph, p.takeoffMs)
        } else if (p.takeoffMs != null) {
            phaseDetector.restore(FlightPhase.CRUISE, p.takeoffMs)
        } else {
            stores.clearEstimate()
        }
        stores.savePlan(p)
        _active.value = true
        publish(System.currentTimeMillis())
        tickJob = scope.launch {
            while (isActive) {
                delay(Parameters.ENGINE_TICK_MS)
                publish(System.currentTimeMillis())
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        tickJob?.cancel(); tickJob = null
        _active.value = false
    }

    /** Clear the active flight entirely (return to setup). */
    @Synchronized
    fun clearFlight() {
        stop()
        stores.savePlan(null); stores.clearEstimate()
        _metrics.value = null
        route = null; estimator = null; origin = null; destination = null; plan = null
    }

    fun onGnss(g: GnssSample) {
        val est = estimator ?: return
        if (g.quality != GnssQuality.NONE && g.hasAlt) {
            if (lastGnssAltMs != 0L) {
                val dt = (g.timeMs - lastGnssAltMs) / 1000.0
                if (dt > 0.5) lastGnssVRate = (g.altM - lastGnssAlt) / dt
            }
            lastGnssAlt = g.altM; lastGnssAltMs = g.timeMs
        }
        phaseDetector.onGnss(g.speedMps, lastGnssVRate, g.quality == GnssQuality.GOOD, g.timeMs)
        est.onGnss(g)
        // Remember ground fixes for origin auto-detection of the next flight.
        if (phaseDetector.phase == FlightPhase.GROUND && g.quality != GnssQuality.NONE && g.speedMps < 5.0) {
            stores.saveGroundFix(GroundFix(g.lat, g.lon, g.timeMs))
        }
        captureTakeoff()
    }

    fun onBaro(b: BaroSample) { phaseDetector.onBaro(b); captureTakeoff() }

    fun onGyro(g: GyroSample) { estimator?.onGyro(g) }

    /** Nearest airport to the last ground fix, for pre-filling the origin field. */
    fun suggestOrigin(): Airport? {
        val gf = stores.loadGroundFix() ?: return null
        return airports.nearest(GeoPoint(gf.lat, gf.lon), Parameters.ORIGIN_MATCH_RADIUS_M)
    }

    fun currentPlan(): FlightPlan? = plan

    private fun captureTakeoff() {
        val p = plan ?: return
        val to = phaseDetector.takeoffTimeMs
        if (to != null && p.takeoffMs == null) {
            plan = p.copy(takeoffMs = to)
            stores.savePlan(plan)
        }
    }

    private fun publish(now: Long) {
        val est = estimator ?: return
        val r = route ?: return
        val o = origin ?: return
        val dst = destination ?: return
        val p = plan ?: return
        // Takeoff reference for PREDICTED_ONLY: measured takeoff, else scheduled departure + 15 min taxi.
        val takeoffRef = p.takeoffMs ?: p.scheduledDepartureMs?.let { it + 15 * 60_000L }
        val e = est.tick(now, phaseDetector.phase, takeoffRef)
        _metrics.value = Metrics.compute(e, r, o, dst, p.takeoffMs ?: takeoffRef?.takeIf { now > it })
        if (now - lastPersistMs > Parameters.PERSIST_INTERVAL_MS) {
            lastPersistMs = now
            stores.saveEstimate(SavedEstimate(now, e.alongTrackM, e.crossTrackM, e.groundSpeedMps, e.trackDeg, e.altM, e.phase.name))
        }
    }
}
