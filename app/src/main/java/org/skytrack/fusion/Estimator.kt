// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Estimator
// Version 2.3
// Purpose : Route-anchored position estimator.
//
//           Principle: the governing route is the strongest hypothesis. The
//           aircraft is ALWAYS displayed on it. Measurements move the aircraft
//           along the route (s) and are used to accumulate evidence of a
//           lateral deviation; only a PROVEN deviation (consistent GOOD fixes
//           over time, consistent heading disagreement, plausible progress)
//           re-plans the governing route from the proven position to the
//           destination and exposes the actual flown track for drawing.
//
//           Modes:
//             GNSS_TRACKING     - fix within GNSS_LOSS_TO_CONSTRAINED_MS
//             ROUTE_CONSTRAINED - fix lost: propagate along the route
//             PREDICTED_ONLY    - no fix ever (or estimate-only mode):
//                                 time-since-takeoff profile
//           Pure Kotlin, JVM-testable.
// Units   : metres, seconds, m/s, degrees true.
// =============================================================
package org.skytrack.fusion

import org.skytrack.Parameters
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy
import org.skytrack.route.Route
import org.skytrack.sensors.FlightPhase
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample
import org.skytrack.sensors.GyroSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

enum class FusionMode { GNSS_TRACKING, ROUTE_CONSTRAINED, PREDICTED_ONLY }
enum class Confidence { MEASURED, FUSED, PREDICTED, STALE }

data class PositionEstimate(
    val timeMs: Long,
    val lat: Double,                    // always on the governing route
    val lon: Double,
    val altM: Double,
    val groundSpeedMps: Double,
    val trackDeg: Double,
    val verticalRateMps: Double,
    val alongTrackM: Double,            // along the governing route
    val totalFlownM: Double,            // along all routes since origin
    val sigmaAlongM: Double,
    val measuredCrossM: Double?,        // last measured lateral offset from the governing route, null if none
    val measuredCrossAgeMs: Long,
    val deviationEvidence: Int,         // consistent fixes accumulated toward a proven deviation
    val deviationRequired: Int,
    val replanCount: Int,
    val visualFixCount: Int,
    val originMismatchM: Double?,       // on ground: distance between fix and origin if beyond threshold
    val mode: FusionMode,
    val phase: FlightPhase,
    val lastFixAgeMs: Long,
    val satsUsed: Int,
    val satsVisible: Int,
    val gnssQuality: GnssQuality,
    val positionConfidence: Confidence,
    val altitudeConfidence: Confidence,
    val speedConfidence: Confidence,
    val trackConfidence: Confidence
)

class Estimator(val plannedRoute: Route) {

    /** Governing route: the planned one until a proven deviation re-plans it. */
    var route: Route = plannedRoute
        private set

    /** Actual positions from GOOD fixes, decimated, for drawing the flown track. */
    val actualTrack: MutableList<GeoPoint> = ArrayList()

    var replanCount = 0
        private set
    var visualFixCount = 0
        private set

    // -- Kinematic state on the governing route --
    private var s = 0.0
    private var v = 0.0
    private var psi = plannedRoute.bearingAt(0.0)
    private var h = 0.0
    private var hdot = 0.0
    private var sigmaS = Parameters.SIGMA_S_INITIAL_M
    private var flownBase = 0.0          // distance flown on previous governing routes

    // -- GNSS bookkeeping --
    private var everFixed = false
    private var lastFixMs = 0L
    private var lastAltMs = 0L
    private var lastAltM = 0.0
    private var satsUsed = 0
    private var satsVisible = 0
    private var lastQuality = GnssQuality.NONE
    private var vRef = 0.0
    private val speedWin = ArrayDeque<Pair<Long, Double>>()
    private var lastCross: Double? = null
    private var lastCrossMs = 0L
    private var originMismatch: Double? = null

    // -- Deviation evidence --
    private data class Evidence(val timeMs: Long, val crossM: Double, val alongM: Double, val speedMps: Double)
    private val evidence = ArrayList<Evidence>()
    private var lastTurnMs = 0L

    // -- Gyro --
    private var gyroDeltaDeg = 0.0
    private var lastGyroMs = 0L
    private val turnWin = ArrayDeque<Pair<Long, Double>>()   // (time, heading delta) for the 60 s turn window

    private var lastTickMs = 0L

    /** Restore persisted state after relaunch. */
    fun restore(alongM: Double, speedMps: Double, trackDeg: Double, altM: Double, timeMs: Long) {
        s = alongM.coerceIn(0.0, route.lengthM); v = speedMps; psi = trackDeg; h = altM
        vRef = speedMps; everFixed = true; lastFixMs = timeMs; lastAltMs = 0L
        sigmaS = max(Parameters.SIGMA_MIN_M, Parameters.ALONG_TRACK_DRIFT_RATE * speedMps * 60.0)
    }

    fun onGyro(g: GyroSample) {
        if (lastGyroMs != 0L) {
            val dt = (g.timeMs - lastGyroMs) / 1000.0
            if (dt > 0 && dt < 1.0) {
                val d = g.yawRateDps * dt
                gyroDeltaDeg += d
                turnWin.addLast(Pair(g.timeMs, d))
                while (turnWin.isNotEmpty() && g.timeMs - turnWin.first().first > 60_000) turnWin.removeFirst()
                // A sustained turn on a straight route is evidence that the aircraft left it.
                val turned = turnWin.sumOf { it.second }
                val routeBend = abs(Geodesy.bearingDiff(route.bearingAt(min(route.lengthM, s + v * 60)), route.bearingAt(s)))
                if (abs(turned) >= Parameters.GYRO_TURN_EVIDENCE_DEG && routeBend < 3.0) {
                    if (g.timeMs - lastTurnMs > 60_000) sigmaS *= 1.5
                    lastTurnMs = g.timeMs
                }
            }
        }
        lastGyroMs = g.timeMs
    }

    /**
     * Manual visual fix. The user identified a landmark out of the window.
     * @param landmark      position of the identified object
     * @param sideRight     true = seen out of the right-hand window, false = left, null = straight below
     * @param distanceM     estimated slant-ground distance to the landmark
     * @param sigmaM        1-sigma of the implied aircraft position
     * The implied aircraft position is the landmark displaced perpendicular to
     * the route course, toward the aircraft's side. Only the along-track
     * component is applied (route anchoring); the lateral component is exposed
     * as a measured offset like any other fix.
     */
    fun onVisualFix(landmark: GeoPoint, sideRight: Boolean?, distanceM: Double, sigmaM: Double, now: Long, phase: FlightPhase) {
        val brg = route.bearingAt(s)
        val aircraft = when (sideRight) {
            null -> landmark
            // Landmark on the right => aircraft lies to the LEFT of the landmark => bearing course - 90.
            true -> Geodesy.destination(landmark, Geodesy.wrapBearing(brg - 90.0), distanceM)
            false -> Geodesy.destination(landmark, Geodesy.wrapBearing(brg + 90.0), distanceM)
        }
        val proj = route.project(aircraft)
        val r = max(sigmaM, Parameters.SIGMA_MIN_M)
        val k = (sigmaS * sigmaS) / (sigmaS * sigmaS + r * r)
        s = (s + k * (proj.alongM - s)).coerceIn(0.0, route.lengthM)
        sigmaS = max(Parameters.SIGMA_MIN_M, sqrt((1 - k) * sigmaS * sigmaS))
        lastCross = proj.crossM; lastCrossMs = now
        if (vRef < Parameters.PHASE_GROUND_SPEED_MPS) vRef = phaseSpeed(phase)
        if (v < Parameters.PHASE_GROUND_SPEED_MPS) v = vRef
        // A visual fix is a position observation: leave PREDICTED_ONLY, but as an aged fix so
        // ROUTE_CONSTRAINED propagation takes over immediately.
        everFixed = true
        lastFixMs = now - Parameters.GNSS_LOSS_TO_CONSTRAINED_MS - 1
        visualFixCount++
    }

    fun onGnss(g: GnssSample, phase: FlightPhase) {
        satsUsed = g.satsUsed; satsVisible = g.satsVisible; lastQuality = g.quality
        if (g.quality == GnssQuality.NONE) return
        val p = GeoPoint(g.lat, g.lon)

        // On the ground the aircraft sits at the origin; a fix only validates the plan.
        if (phase == FlightPhase.GROUND) {
            val dOrigin = Geodesy.distance(p, plannedRoute.origin)
            originMismatch = if (dOrigin > Parameters.ORIGIN_MISMATCH_M) dOrigin else null
            everFixed = true; lastFixMs = g.timeMs
            if (g.hasSpeed) v = g.speedMps
            updateAltitude(g)
            return
        }
        originMismatch = null

        val proj = route.project(p)
        lastCross = proj.crossM; lastCrossMs = g.timeMs

        // Along-track Kalman update. Cross-track is NOT applied to the displayed position.
        val r = max(g.hAccM, Parameters.SIGMA_MIN_M)
        val ks = (sigmaS * sigmaS) / (sigmaS * sigmaS + r * r)
        s = (s + ks * (proj.alongM - s)).coerceIn(0.0, route.lengthM)
        sigmaS = max(Parameters.SIGMA_MIN_M, sqrt((1 - ks) * sigmaS * sigmaS))

        if (g.hasSpeed) {
            v = if (everFixed) v + 0.5 * (g.speedMps - v) else g.speedMps
            speedWin.addLast(Pair(g.timeMs, g.speedMps))
            while (speedWin.isNotEmpty() && g.timeMs - speedWin.first().first > Parameters.SPEED_AVG_WINDOW_S * 1000) speedWin.removeFirst()
            vRef = speedWin.map { it.second }.average()
        }
        val gnssTrackUsable = g.hasBearing && g.speedMps > Parameters.GNSS_MIN_SPEED_FOR_TRACK
        if (gnssTrackUsable) {
            psi = Geodesy.wrapBearing(psi + 0.6 * Geodesy.bearingDiff(g.bearingDeg, psi))
            gyroDeltaDeg = 0.0
        }
        updateAltitude(g)
        everFixed = true
        lastFixMs = g.timeMs

        if (g.quality == GnssQuality.GOOD) {
            recordTrack(p)
            val remaining = route.lengthM - s
            if (remaining < Parameters.TERMINAL_AREA_M) {
                // Terminal area: routes never follow the great circle here; re-anchor freely.
                if (abs(proj.crossM) > Parameters.TERMINAL_REANCHOR_MIN_M) replan(p)
                evidence.clear()
            } else {
                accumulateEvidence(g, p, proj, gnssTrackUsable)
            }
        }
    }

    private fun accumulateEvidence(g: GnssSample, p: GeoPoint, proj: Route.Projection, trackUsable: Boolean) {
        val headingDiff = if (trackUsable) abs(Geodesy.bearingDiff(g.bearingDeg, route.bearingAt(proj.alongM))) else 0.0
        val consistentSign = evidence.isEmpty() || sign(evidence.last().crossM) == sign(proj.crossM)
        val far = abs(proj.crossM) >= Parameters.DEVIATION_MIN_CROSS_M
        val headingOk = !trackUsable || headingDiff >= Parameters.DEVIATION_MIN_HEADING_DEG
        var progressOk = true
        if (evidence.isNotEmpty()) {
            val e0 = evidence.last()
            val dt = (g.timeMs - e0.timeMs) / 1000.0
            if (dt > 0.5) {
                val expected = 0.5 * (e0.speedMps + g.speedMps) * dt
                val actual = abs(proj.alongM - e0.alongM)
                progressOk = expected < 1.0 || abs(actual - expected) <= Parameters.DEVIATION_SPEED_TOLERANCE * expected + 200.0
            }
        }
        if (far && consistentSign && headingOk && progressOk) {
            evidence.add(Evidence(g.timeMs, proj.crossM, proj.alongM, g.speedMps))
        } else {
            evidence.clear()
            return
        }
        val turnRecent = lastTurnMs != 0L && g.timeMs - lastTurnMs < Parameters.GYRO_TURN_MEMORY_S * 1000
        val required = if (turnRecent) Parameters.DEVIATION_MIN_FIXES_TURN else Parameters.DEVIATION_MIN_FIXES
        val span = (g.timeMs - evidence.first().timeMs) / 1000.0
        if (evidence.size >= required && span >= Parameters.DEVIATION_MIN_DURATION_S) {
            replan(p)
        }
    }

    /** Proven deviation: new governing route from the proven position to the destination. */
    private fun replan(from: GeoPoint) {
        flownBase += s
        route = Route(from, plannedRoute.destination)
        s = 0.0
        sigmaS = Parameters.SIGMA_MIN_M * 10
        evidence.clear()
        lastCross = 0.0
        replanCount++
        if (actualTrack.isEmpty() || Geodesy.distance(actualTrack.last(), from) > 100.0) actualTrack.add(from)
    }

    private fun recordTrack(p: GeoPoint) {
        if (actualTrack.isEmpty() || Geodesy.distance(actualTrack.last(), p) >= Parameters.TRACK_DECIMATION_M) actualTrack.add(p)
    }

    private fun updateAltitude(g: GnssSample) {
        if (!g.hasAlt) return
        if (lastAltMs != 0L) {
            val dt = (g.timeMs - lastAltMs) / 1000.0
            if (dt > 0.5) hdot += 0.3 * ((g.altM - lastAltM) / dt - hdot)
        }
        h = g.altM; lastAltM = g.altM; lastAltMs = g.timeMs
    }

    /**
     * Propagate to `now` and emit an estimate.
     * @param phase         current flight phase
     * @param takeoffRefMs  takeoff time (measured or assumed) for PREDICTED_ONLY; null if unknown
     * @param sensorsLive   false in estimate-only mode: fixes are ignored, mode is PREDICTED_ONLY
     */
    fun tick(now: Long, phase: FlightPhase, takeoffRefMs: Long?, sensorsLive: Boolean = true): PositionEstimate {
        val dt = if (lastTickMs == 0L) 0.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 30.0)
        lastTickMs = now
        val age = if (everFixed && sensorsLive) now - lastFixMs else Long.MAX_VALUE

        val onGround = phase == FlightPhase.GROUND
        val mode = when {
            !sensorsLive || !everFixed -> FusionMode.PREDICTED_ONLY
            age > Parameters.GNSS_LOSS_TO_CONSTRAINED_MS -> FusionMode.ROUTE_CONSTRAINED
            else -> FusionMode.GNSS_TRACKING
        }

        if (onGround && sensorsLive) {
            // Anchored at the origin until takeoff is detected.
            s = 0.0; psi = route.bearingAt(0.0)
            sigmaS = Parameters.SIGMA_MIN_M
        } else when (mode) {
            FusionMode.PREDICTED_ONLY -> {
                val t = if (takeoffRefMs != null && now > takeoffRefMs) (now - takeoffRefMs) / 1000.0 else 0.0
                val (ps, pv) = predictedProfile(t)
                s = ps; v = pv; psi = route.bearingAt(s)
                sigmaS = if (t <= 0.0) Parameters.SIGMA_MIN_M else Parameters.SIGMA_S_INITIAL_M + Parameters.ALONG_TRACK_DRIFT_RATE * pv * t
            }
            FusionMode.GNSS_TRACKING -> {
                s = (s + v * dt).coerceIn(0.0, route.lengthM)
                sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * v * dt
                gyroDeltaDeg = 0.0
            }
            FusionMode.ROUTE_CONSTRAINED -> {
                val ageS = age / 1000.0
                val vPhase = phaseSpeed(phase)
                val w = min(1.0, ageS / Parameters.SPEED_BLEND_TAU_S)
                val vEff = if (vPhase == 0.0 && vRef < Parameters.PHASE_GROUND_SPEED_MPS) 0.0 else vRef * (1 - w) + vPhase * w
                v = vEff
                s = (s + vEff * dt).coerceIn(0.0, route.lengthM)
                sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * vEff * dt
                psi = if (ageS < Parameters.GYRO_TRUST_WINDOW_S && lastGyroMs != 0L && now - lastGyroMs < 5_000)
                    Geodesy.wrapBearing(psi + gyroDeltaDeg) else route.bearingAt(s)
                gyroDeltaDeg = 0.0
            }
        }

        val pos = route.pointAt(s)
        val fresh = sensorsLive && everFixed && age < Parameters.GNSS_STALE_MS * 2
        val altFresh = sensorsLive && lastAltMs != 0L && now - lastAltMs < Parameters.GNSS_STALE_MS * 2

        val posConf = when (mode) {
            FusionMode.GNSS_TRACKING -> if (!fresh) Confidence.PREDICTED else if (lastQuality == GnssQuality.GOOD) Confidence.MEASURED else Confidence.FUSED
            FusionMode.ROUTE_CONSTRAINED -> if (age < 120_000) Confidence.FUSED else Confidence.PREDICTED
            FusionMode.PREDICTED_ONLY -> Confidence.PREDICTED
        }
        val speedConf = if (fresh) Confidence.MEASURED else if (mode == FusionMode.PREDICTED_ONLY) Confidence.PREDICTED else Confidence.FUSED
        val trackConf = when {
            fresh && v > Parameters.GNSS_MIN_SPEED_FOR_TRACK -> Confidence.MEASURED
            mode == FusionMode.ROUTE_CONSTRAINED && age / 1000.0 < Parameters.GYRO_TRUST_WINDOW_S -> Confidence.FUSED
            else -> Confidence.PREDICTED
        }
        val altConf = if (altFresh) Confidence.MEASURED else if (lastAltMs != 0L && sensorsLive) Confidence.STALE else Confidence.PREDICTED
        val turnRecent = lastTurnMs != 0L && now - lastTurnMs < Parameters.GYRO_TURN_MEMORY_S * 1000

        return PositionEstimate(
            timeMs = now, lat = pos.lat, lon = pos.lon, altM = h,
            groundSpeedMps = v, trackDeg = psi, verticalRateMps = if (altFresh) hdot else 0.0,
            alongTrackM = s, totalFlownM = flownBase + s, sigmaAlongM = sigmaS,
            measuredCrossM = if (sensorsLive && !onGround) lastCross else null,
            measuredCrossAgeMs = if (lastCrossMs == 0L) -1L else now - lastCrossMs,
            deviationEvidence = evidence.size,
            deviationRequired = if (turnRecent) Parameters.DEVIATION_MIN_FIXES_TURN else Parameters.DEVIATION_MIN_FIXES,
            replanCount = replanCount,
            visualFixCount = visualFixCount,
            originMismatchM = if (sensorsLive && onGround) originMismatch else null,
            mode = mode, phase = phase, lastFixAgeMs = if (everFixed && sensorsLive) now - lastFixMs else -1L,
            satsUsed = satsUsed, satsVisible = satsVisible, gnssQuality = if (sensorsLive) lastQuality else GnssQuality.NONE,
            positionConfidence = posConf, altitudeConfidence = altConf,
            speedConfidence = speedConf, trackConfidence = trackConf
        )
    }

    private fun phaseSpeed(p: FlightPhase): Double = when (p) {
        FlightPhase.GROUND, FlightPhase.LANDED -> 0.0
        FlightPhase.TAKEOFF, FlightPhase.CLIMB -> Parameters.SPEED_CLIMB_MPS
        FlightPhase.CRUISE -> Parameters.SPEED_CRUISE_MPS
        FlightPhase.DESCENT -> Parameters.SPEED_DESCENT_MPS
    }

    /**
     * Piecewise profile for PREDICTED_ONLY on the governing route: climb, cruise,
     * descent over the last DESCENT_DISTANCE. Returns (along-track m, speed m/s).
     */
    private fun predictedProfile(tS: Double): Pair<Double, Double> {
        val l = route.lengthM
        if (tS <= 0.0) return Pair(0.0, 0.0)
        val climbDist = min(Parameters.SPEED_CLIMB_MPS * Parameters.CLIMB_DURATION_S, l * 0.4)
        val descentDist = min(Parameters.DESCENT_DISTANCE_M, l * 0.4)
        val cruiseDist = max(0.0, l - climbDist - descentDist)
        val tClimb = climbDist / Parameters.SPEED_CLIMB_MPS
        val tCruise = cruiseDist / Parameters.SPEED_CRUISE_MPS
        val tDescent = descentDist / Parameters.SPEED_DESCENT_MPS
        return when {
            tS < tClimb -> Pair(Parameters.SPEED_CLIMB_MPS * tS, Parameters.SPEED_CLIMB_MPS)
            tS < tClimb + tCruise -> Pair(climbDist + Parameters.SPEED_CRUISE_MPS * (tS - tClimb), Parameters.SPEED_CRUISE_MPS)
            tS < tClimb + tCruise + tDescent -> Pair(climbDist + cruiseDist + Parameters.SPEED_DESCENT_MPS * (tS - tClimb - tCruise), Parameters.SPEED_DESCENT_MPS)
            else -> Pair(l, 0.0)
        }
    }

    /** Phase implied by elapsed time on the predicted profile (estimate-only mode). */
    fun predictedPhase(now: Long, takeoffRefMs: Long?): FlightPhase {
        if (takeoffRefMs == null || now <= takeoffRefMs) return FlightPhase.GROUND
        val t = (now - takeoffRefMs) / 1000.0
        val (ps, pv) = predictedProfile(t)
        return when {
            pv == 0.0 && ps >= route.lengthM -> FlightPhase.LANDED
            t < Parameters.CLIMB_DURATION_S -> FlightPhase.CLIMB
            route.lengthM - ps <= min(Parameters.DESCENT_DISTANCE_M, route.lengthM * 0.4) + 1.0 -> FlightPhase.DESCENT
            else -> FlightPhase.CRUISE
        }
    }
}
