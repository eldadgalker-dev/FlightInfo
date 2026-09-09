// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - FlightEngine
// Version 2.3
// Purpose : Application-scoped coordinator. Owns the Route, Estimator and
//           FlightPhaseDetector for the active flight, consumes sensor
//           flows (started by TrackingService), ticks the estimator at
//           ENGINE_TICK_MS and publishes FlightMetrics as a StateFlow.
//           Two tracking modes:
//             LIVE          - sensors drive the estimate
//             ESTIMATE_ONLY - sensors ignored; position follows the
//                             time-based profile from the assumed takeoff
//                             (scheduled departure + taxi allowance)
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
import org.skytrack.data.GeoData
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

class FlightEngine(private val airports: AirportRepository, private val stores: Stores, val logger: FlightLogger,
                   val geo: GeoData, private val hebrew: Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val _metrics = MutableStateFlow<FlightMetrics?>(null)
    val metrics: StateFlow<FlightMetrics?> = _metrics

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** null = unknown yet; set by TrackingService from the sensor manager. */
    val baroAvailable = MutableStateFlow<Boolean?>(null)

    private var geoReady = false
    private var lastCountryCheckMs = 0L
    private var lastCountry: String? = null

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
    @Volatile private var lastGnss: GnssSample? = null
    @Volatile private var lastBaro: BaroSample? = null
    @Volatile private var lastGyro: GyroSample? = null

    /** True when sensors drive the estimate; false in estimate-only mode. */
    val sensorsLive: Boolean get() = plan?.estimateOnly != true

    /** Start (or restart) tracking for a plan. Returns false if airports are unknown. */
    @Synchronized
    fun start(p: FlightPlan): Boolean {
        val o = airports.byCode(p.originIata) ?: return false
        val dst = airports.byCode(p.destinationIata) ?: return false
        stop()
        plan = p; origin = o; destination = dst
        val est = Estimator(Route(o.point, dst.point))
        estimator = est
        phaseDetector = FlightPhaseDetector()

        val saved = stores.loadEstimate()
        if (!p.estimateOnly && saved != null && System.currentTimeMillis() - saved.timeMs < 12 * 3600_000L && p.takeoffMs != null) {
            est.restore(saved.alongM, saved.speedMps, saved.trackDeg, saved.altM, saved.timeMs)
            val ph = try { FlightPhase.valueOf(saved.phase) } catch (e: Exception) { FlightPhase.CRUISE }
            phaseDetector.restore(ph, p.takeoffMs)
        } else if (!p.estimateOnly && p.takeoffMs != null) {
            phaseDetector.restore(FlightPhase.CRUISE, p.takeoffMs)
        } else {
            stores.clearEstimate()
        }
        stores.savePlan(p)
        if (!geoReady) scope.launch { geo.warmUp(); geoReady = true }
        logger.enabled = stores.settings.value.logFlights
        if (!p.estimateOnly) logger.start(p.originIata, p.destinationIata, p.flightNumber) else logger.stop()
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

    /** Switch between live sensing and estimate-only without losing the plan. */
    @Synchronized
    fun setEstimateOnly(enabled: Boolean) {
        val p = plan ?: return
        if (p.estimateOnly == enabled) return
        start(p.copy(estimateOnly = enabled))
    }

    @Synchronized
    fun stop() {
        tickJob?.cancel(); tickJob = null
        _active.value = false
        logger.stop()
    }

    /** Clear the active flight entirely (return to setup). */
    @Synchronized
    fun clearFlight() {
        stop()
        stores.savePlan(null); stores.clearEstimate()
        _metrics.value = null
        estimator = null; origin = null; destination = null; plan = null
    }

    fun onGnss(g: GnssSample) {
        lastGnss = g
        if (!sensorsLive) return
        val est = estimator ?: return
        if (g.quality != GnssQuality.NONE && g.hasAlt) {
            if (lastGnssAltMs != 0L) {
                val dt = (g.timeMs - lastGnssAltMs) / 1000.0
                if (dt > 0.5) lastGnssVRate = (g.altM - lastGnssAlt) / dt
            }
            lastGnssAlt = g.altM; lastGnssAltMs = g.timeMs
        }
        phaseDetector.onGnss(g.speedMps, lastGnssVRate, g.quality == GnssQuality.GOOD, g.timeMs)
        est.onGnss(g, phaseDetector.phase)
        if (phaseDetector.phase == FlightPhase.GROUND && g.quality != GnssQuality.NONE && g.speedMps < 5.0) {
            stores.saveGroundFix(GroundFix(g.lat, g.lon, g.timeMs))
        }
        captureTakeoff()
    }

    /**
     * Manual visual fix from the UI. distanceCategory: 0 = below, 1 = near, 2 = mid, 3 = far.
     */
    fun applyVisualFix(landmark: GeoPoint, sideRight: Boolean?, distanceCategory: Int) {
        val est = estimator ?: return
        if (!sensorsLive) return
        val (d, sigma) = when (distanceCategory) {
            0 -> Pair(0.0, Parameters.VISUAL_FIX_SIGMA_BELOW_M)
            1 -> Pair(Parameters.VISUAL_DIST_NEAR_M, Parameters.VISUAL_FIX_SIGMA_M)
            2 -> Pair(Parameters.VISUAL_DIST_MID_M, Parameters.VISUAL_FIX_SIGMA_M)
            else -> Pair(Parameters.VISUAL_DIST_FAR_M, Parameters.VISUAL_FIX_SIGMA_M * 1.5)
        }
        val side = if (distanceCategory == 0) null else sideRight
        est.onVisualFix(landmark, side, d, sigma, System.currentTimeMillis(), phaseDetector.phase)
        publish(System.currentTimeMillis())
    }

    /** Candidate landmarks around the current estimate for the visual-fix dialog. */
    fun visualFixCandidates(): List<Pair<GeoData.Place, Double>> {
        val m = _metrics.value ?: return emptyList()
        if (!geoReady) return emptyList()
        return geo.nearbyPlaces(GeoPoint(m.estimate.lat, m.estimate.lon), Parameters.VISUAL_FIX_SEARCH_RADIUS_M, Parameters.VISUAL_FIX_MAX_CANDIDATES)
    }

    fun onBaro(b: BaroSample) { lastBaro = b; if (sensorsLive) { phaseDetector.onBaro(b); captureTakeoff() } }

    fun onGyro(g: GyroSample) { lastGyro = g; if (sensorsLive) estimator?.onGyro(g) }

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

    /** Takeoff reference: measured takeoff, else scheduled departure + taxi allowance. */
    private fun takeoffReference(p: FlightPlan): Long? =
        p.takeoffMs ?: p.scheduledDepartureMs?.let { it + (Parameters.TAXI_ALLOWANCE_S * 1000).toLong() }

    private fun publish(now: Long) {
        val est = estimator ?: return
        val o = origin ?: return
        val dst = destination ?: return
        val p = plan ?: return
        val live = !p.estimateOnly
        val takeoffRef = takeoffReference(p)
        val phase = if (live) phaseDetector.phase else est.predictedPhase(now, takeoffRef)
        val e = est.tick(now, phase, takeoffRef, live)
        val takeoffForMetrics = if (live) p.takeoffMs ?: takeoffRef?.takeIf { now > it } else takeoffRef?.takeIf { now > it }
        // Country under the aircraft: cheap point-in-polygon, refreshed every 5 s once the data is parsed.
        if (geoReady && now - lastCountryCheckMs > 5_000) {
            lastCountryCheckMs = now
            lastCountry = geo.countryAt(GeoPoint(e.lat, e.lon))?.label(hebrew)
        }
        val fm = Metrics.compute(e, est.route, est.plannedRoute, est.actualTrack.toList(), !live, o, dst, takeoffForMetrics, lastCountry)
        _metrics.value = fm
        if (live) logger.log(fm, lastGnss, lastBaro, lastGyro)
        if (live && now - lastPersistMs > Parameters.PERSIST_INTERVAL_MS) {
            lastPersistMs = now
            stores.saveEstimate(SavedEstimate(now, e.alongTrackM, e.groundSpeedMps, e.trackDeg, e.altM, e.phase.name))
        }
    }
}
