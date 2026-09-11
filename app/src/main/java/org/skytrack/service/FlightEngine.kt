// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - FlightEngine
// Version 4.0
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
import org.skytrack.fusion.GroundReference
import org.skytrack.fusion.Metrics
import org.skytrack.net.LiveFlightSource
import org.skytrack.route.GeoPoint
import org.skytrack.route.Route
import org.skytrack.sensors.BaroSample
import org.skytrack.sensors.FlightPhase
import org.skytrack.sensors.FlightPhaseDetector
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample
import org.skytrack.sensors.GyroSample
import org.skytrack.sensors.MotionSample

class FlightEngine(private val airports: AirportRepository, private val stores: Stores, val logger: FlightLogger,
                   val geo: GeoData, private val hebrew: Boolean, private val isOnline: () -> Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var lastExternalFixMs = 0L
    @Volatile private var lastAnyFixMs = 0L

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
    @Volatile private var groundRef: GroundReference? = null
    private var startMs = 0L
    private var warnActiveSinceMs = 0L
    private var reliefUntilMs = 0L
    private var lastWarnLevel = 0

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
        startMs = System.currentTimeMillis(); lastWarnLevel = 0; reliefUntilMs = 0L
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
        if (p.flightNumber.isNotBlank()) pollJob = scope.launch { pollLive(p) }
        return true
    }

    /**
     * Online enrichment: when the phone has a network, ask the ADS-B aggregator for the
     * real position of this flight. Used always in estimate-only mode, and in live mode
     * only when the phone's own GNSS has been silent for a while.
     */
    private suspend fun pollLive(p: FlightPlan) {
        while (true) {
            try {
                val s = stores.settings.value
                val gnssStale = System.currentTimeMillis() - lastAnyFixMs > Parameters.LIVE_ONLY_WHEN_GNSS_OLDER_MS
                if (s.useNetwork && isOnline() && (p.estimateOnly || gnssStale)) {
                    val fix = LiveFlightSource.fetch(p.flightNumber)
                    if (fix != null) {
                        lastExternalFixMs = System.currentTimeMillis()
                        ingestFix(fix)
                    }
                }
            } catch (e: Exception) {
                // Network hiccup: try again next interval.
            }
            delay(Parameters.LIVE_POLL_INTERVAL_MS)
        }
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
        pollJob?.cancel(); pollJob = null
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
        if (g.quality != GnssQuality.NONE) lastAnyFixMs = g.timeMs
        ingestFix(g)
    }

    /** Shared path for phone GNSS and network ADS-B fixes. */
    private fun ingestFix(g: GnssSample) {
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

    fun onMotion(m: MotionSample) { if (sensorsLive) { phaseDetector.onMotion(m); captureTakeoff() } }

    /**
     * The user confirms the aircraft is on the ground at the origin now. Resets the phase to
     * GROUND, forgets a wrongly captured takeoff, records field elevation vs GNSS altitude
     * (altitude bias) and the cabin pressure (cabin-altitude reference).
     */
    @Synchronized
    fun confirmOnGround() {
        val est = estimator ?: return
        val o = origin ?: return
        val p = plan ?: return
        phaseDetector.confirmGround()
        if (p.takeoffMs != null) { plan = p.copy(takeoffMs = null); stores.savePlan(plan) }
        val g = lastGnss?.takeIf { it.quality != GnssQuality.NONE && it.hasAlt && System.currentTimeMillis() - it.timeMs < 30_000 }
        est.setGroundReference(g?.altM, o.elevM.toDouble())
        groundRef = GroundReference(System.currentTimeMillis(), o.elevM, g?.altM, lastBaro?.pressureHpa)
        publish(System.currentTimeMillis())
    }

    fun onGyro(g: GyroSample) { lastGyro = g; if (sensorsLive) estimator?.onGyro(g) }

    /** Nearest airport to the last ground fix, for pre-filling the origin field. */
    fun suggestOrigin(): Airport? {
        val gf = stores.loadGroundFix() ?: return null
        return airports.nearest(GeoPoint(gf.lat, gf.lon), Parameters.ORIGIN_MATCH_RADIUS_M)
    }

    fun currentPlan(): FlightPlan? = plan

    /** A takeoff measured by the sensors is the only one that really happened: it replaces a manual or scheduled value. */
    private fun captureTakeoff() {
        val p = plan ?: return
        val to = phaseDetector.takeoffTimeMs
        if (to != null && (p.takeoffMs == null || (!p.takeoffMeasured && kotlin.math.abs(to - p.takeoffMs) > 60_000))) {
            plan = p.copy(takeoffMs = to, takeoffMeasured = true)
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
        val externalFresh = lastExternalFixMs != 0L && now - lastExternalFixMs < Parameters.LIVE_MAX_AGE_S * 1000
        val live = !p.estimateOnly || externalFresh
        val takeoffRef = takeoffReference(p)
        val phase = if (!p.estimateOnly || externalFresh) phaseDetector.phase else est.predictedPhase(now, takeoffRef)
        val e = est.tick(now, phase, takeoffRef, live)
        val takeoffForMetrics = if (live) p.takeoffMs ?: takeoffRef?.takeIf { now > it } else takeoffRef?.takeIf { now > it }
        // Country under the aircraft: cheap point-in-polygon, refreshed every 5 s once the data is parsed.
        if (geoReady && now - lastCountryCheckMs > 5_000) {
            lastCountryCheckMs = now
            lastCountry = geo.countryAt(GeoPoint(e.lat, e.lon))?.label(hebrew)
        }
        val source = if (externalFresh && (p.estimateOnly || now - lastAnyFixMs > Parameters.LIVE_ONLY_WHEN_GNSS_OLDER_MS)) "ADS-B" else null
        // Cabin pressure altitude: barometric formula from the on-ground reference (cabin = ambient at the gate).
        val gr = groundRef?.takeIf { now - it.timeMs < Parameters.GROUND_REF_MAX_AGE_H * 3600_000 }
        val cabinAlt = if (gr?.pressureHpa != null && lastBaro != null)
            gr.fieldElevM + Parameters.STD_ATMOS_SCALE_M * (1.0 - Math.pow(lastBaro!!.pressureHpa / gr.pressureHpa, Parameters.STD_ATMOS_EXP)) else null
        // GNSS warning escalation (live mode only): seconds since the last usable fix or since start.
        val noFixS = if (!live) 0L else (now - (if (lastAnyFixMs != 0L) lastAnyFixMs else startMs)) / 1000
        val warn = if (!live || externalFresh) 0 else when {
            noFixS < Parameters.GNSS_WARN_AFTER_S -> 0
            noFixS < Parameters.GNSS_WARN_LEVEL2_S -> 1
            noFixS < Parameters.GNSS_WARN_LEVEL3_S -> 2
            else -> 3
        }
        if (warn == 0 && lastWarnLevel > 0) reliefUntilMs = now + (Parameters.GNSS_RELIEF_SHOW_S * 1000).toLong()
        lastWarnLevel = warn
        val relief = now < reliefUntilMs
        val fm = Metrics.compute(e, est.route, est.plannedRoute, est.actualTrack.toList(), p.estimateOnly && !externalFresh,
            o, dst, takeoffForMetrics, lastCountry, takeoffRef, source, cabinAlt, gr, warn, relief, noFixS)
        phaseDetector.onRemaining(fm.remainingM, now)
        _metrics.value = fm
        if (live) logger.log(fm, lastGnss, lastBaro, lastGyro)
        if (live && now - lastPersistMs > Parameters.PERSIST_INTERVAL_MS) {
            lastPersistMs = now
            stores.saveEstimate(SavedEstimate(now, e.alongTrackM, e.groundSpeedMps, e.trackDeg, e.altM, e.phase.name))
        }
    }
}
