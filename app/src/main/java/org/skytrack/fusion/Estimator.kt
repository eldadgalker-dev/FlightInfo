// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Estimator
// Version 4.0
// Purpose : Measured-first position estimator.
//
//           With a usable fix (any accuracy up to WEAK_FIX_MAX_HACC_M) the
//           aircraft is where it was measured; the measured track is kept at
//           TRACK_DECIMATION_M resolution. The governing route is the great
//           circle from the latest measured position to the destination and
//           is re-anchored whenever the aircraft leaves it laterally. Without
//           a fix the aircraft propagates along the governing route from the
//           last measured position, using the gyro to scale progress and the
//           barometer-derived phase to pick a speed. Without any fix ever, a
//           time-based profile along the planned route is used.
//
//           Modes: GNSS_TRACKING / ROUTE_CONSTRAINED / PREDICTED_ONLY
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
import kotlin.math.sqrt

enum class FusionMode { GNSS_TRACKING, ROUTE_CONSTRAINED, PREDICTED_ONLY }
enum class Confidence { MEASURED, FUSED, PREDICTED, STALE }

data class PositionEstimate(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val groundSpeedMps: Double,
    val trackDeg: Double,
    val verticalRateMps: Double,
    val alongTrackM: Double,            // along the governing route (from its anchor)
    val totalFlownM: Double,            // measured track length + dead-reckoned distance
    val sigmaAlongM: Double,            // 1-sigma position uncertainty along the direction of flight
    val replanCount: Int,               // re-anchors of the governing route
    val visualFixCount: Int,
    val maneuvering: Boolean,
    val groundReferenced: Boolean,
    val originMismatchM: Double?,
    /** 0 = nothing (time only), 1 = inertial/baro only, 2 = weak fix or network fix, 3 = good fix now */
    val sensorLevel: Int,
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

    /** Governing route: planned until the first fix, then anchor -> destination. */
    var route: Route = plannedRoute
        private set

    /** Measured positions, decimated, oldest first. */
    val actualTrack: MutableList<GeoPoint> = ArrayList()

    var replanCount = 0
        private set
    var visualFixCount = 0
        private set

    // -- State --
    private var s = 0.0                  // along the governing route
    private var v = 0.0
    private var psi = plannedRoute.bearingAt(0.0)
    private var h = 0.0
    private var hdot = 0.0
    private var sigmaS = Parameters.SIGMA_S_INITIAL_M
    private var flownMeasured = 0.0      // length of the measured track
    private var flownSinceFix = 0.0      // dead-reckoned distance since the last fix
    private var pos: GeoPoint = plannedRoute.origin
    private var anchor: GeoPoint? = null
    private var lastReanchorMs = 0L

    // -- Fix bookkeeping --
    private var everFixed = false
    private var lastFixMs = 0L
    private var lastFixPos: GeoPoint? = null
    private var lastAltMs = 0L
    private var lastAltM = 0.0
    private var satsUsed = 0
    private var satsVisible = 0
    private var lastQuality = GnssQuality.NONE
    private var lastHAcc = 0.0
    private var vRef = 0.0
    private val speedWin = ArrayDeque<Pair<Long, Double>>()
    private var originMismatch: Double? = null

    // -- Gyro --
    private var gyroDeltaDeg = 0.0
    private var lastGyroMs = 0L
    private var psiDR = plannedRoute.bearingAt(0.0)
    private var maneuverSinceMs = 0L
    private var maneuvering = false
    private var lastTurnMs = 0L
    private val turnWin = ArrayDeque<Pair<Long, Double>>()

    // -- Ground reference --
    private var altOffsetM = 0.0
    private var groundReferenced = false

    private var lastTickMs = 0L

    /** Restore persisted state after relaunch (position on the planned route). */
    fun restore(alongM: Double, speedMps: Double, trackDeg: Double, altM: Double, timeMs: Long) {
        s = alongM.coerceIn(0.0, route.lengthM); pos = route.pointAt(s)
        v = speedMps; psi = trackDeg; h = altM
        vRef = speedMps; everFixed = true; lastFixMs = timeMs; lastFixPos = pos; lastAltMs = 0L
        sigmaS = max(Parameters.SIGMA_MIN_M, Parameters.ALONG_TRACK_DRIFT_RATE * speedMps * 60.0)
    }

    fun setGroundReference(gnssAltM: Double?, fieldElevM: Double) {
        if (gnssAltM != null) { altOffsetM = gnssAltM - fieldElevM; groundReferenced = true }
        sigmaS = Parameters.SIGMA_MIN_M
    }

    fun onGyro(g: GyroSample) {
        if (lastGyroMs != 0L) {
            val dt = (g.timeMs - lastGyroMs) / 1000.0
            if (dt > 0 && dt < 1.0) {
                val d = g.yawRateDps * dt
                gyroDeltaDeg += d
                psiDR = Geodesy.wrapBearing(psiDR + d)
                turnWin.addLast(Pair(g.timeMs, d))
                while (turnWin.isNotEmpty() && g.timeMs - turnWin.first().first > 60_000) turnWin.removeFirst()
                val turned = turnWin.sumOf { it.second }
                if (abs(turned) >= Parameters.GYRO_TURN_EVIDENCE_DEG) {
                    if (g.timeMs - lastTurnMs > 60_000) sigmaS *= 1.5
                    lastTurnMs = g.timeMs
                }
            }
        }
        lastGyroMs = g.timeMs
    }

    /** Manual visual fix: along-route Kalman update on the governing route, sigma as given. */
    fun onVisualFix(landmark: GeoPoint, sideRight: Boolean?, distanceM: Double, sigmaM: Double, now: Long, phase: FlightPhase) {
        val brg = route.bearingAt(s)
        val aircraft = when (sideRight) {
            null -> landmark
            true -> Geodesy.destination(landmark, Geodesy.wrapBearing(brg - 90.0), distanceM)
            false -> Geodesy.destination(landmark, Geodesy.wrapBearing(brg + 90.0), distanceM)
        }
        val proj = route.project(aircraft)
        val r = max(sigmaM, Parameters.SIGMA_MIN_M)
        val k = (sigmaS * sigmaS) / (sigmaS * sigmaS + r * r)
        s = (s + k * (proj.alongM - s)).coerceIn(0.0, route.lengthM)
        sigmaS = max(Parameters.SIGMA_MIN_M, sqrt((1 - k) * sigmaS * sigmaS))
        pos = route.pointAt(s)
        if (vRef < Parameters.PHASE_GROUND_SPEED_MPS) vRef = phaseSpeed(phase)
        if (v < Parameters.PHASE_GROUND_SPEED_MPS) v = vRef
        everFixed = true
        lastFixMs = now - Parameters.GNSS_LOSS_TO_CONSTRAINED_MS - 1
        visualFixCount++
    }

    fun onGnss(g: GnssSample, phase: FlightPhase) {
        satsUsed = g.satsUsed; satsVisible = g.satsVisible; lastQuality = g.quality
        if (g.quality == GnssQuality.NONE || g.hAccM > Parameters.WEAK_FIX_MAX_HACC_M) return
        val p = GeoPoint(g.lat, g.lon)
        lastHAcc = g.hAccM

        // Origin plausibility while on the ground (plan check), but the position is still shown as measured.
        originMismatch = if (phase == FlightPhase.GROUND) {
            val d = Geodesy.distance(p, plannedRoute.origin)
            if (d > Parameters.ORIGIN_MISMATCH_M) d else null
        } else null

        // Measured track and flown distance.
        val prev = lastFixPos
        if (prev != null) {
            val step = Geodesy.distance(prev, p)
            if (step < 50_000.0) flownMeasured += step        // ignore impossible jumps
        }
        if (actualTrack.isEmpty() || Geodesy.distance(actualTrack.last(), p) >= Parameters.TRACK_DECIMATION_M) {
            actualTrack.add(p)
            if (actualTrack.size > Parameters.TRACK_MAX_POINTS) actualTrack.removeAt(0)
        }
        flownSinceFix = 0.0

        // Governing route: anchor at the measured position when we left the current one.
        val proj = route.project(p)
        val timeOk = g.timeMs - lastReanchorMs > Parameters.REANCHOR_MIN_INTERVAL_S * 1000
        if (anchor == null || (abs(proj.crossM) > Parameters.REANCHOR_CROSS_M && timeOk)) {
            reanchor(p, g.timeMs)
        } else {
            s = proj.alongM.coerceIn(0.0, route.lengthM)
        }
        pos = p
        sigmaS = max(Parameters.SIGMA_MIN_M, g.hAccM)

        if (g.hasSpeed) {
            val spd = if (g.speedMps < Parameters.GNSS_SPEED_NOISE_MPS) 0.0 else g.speedMps
            v = if (everFixed) v + 0.5 * (spd - v) else spd
            speedWin.addLast(Pair(g.timeMs, spd))
            while (speedWin.isNotEmpty() && g.timeMs - speedWin.first().first > Parameters.SPEED_AVG_WINDOW_S * 1000) speedWin.removeFirst()
            vRef = speedWin.map { it.second }.average()
        }
        if (g.hasBearing && g.speedMps > Parameters.GNSS_MIN_SPEED_FOR_TRACK) {
            psi = Geodesy.wrapBearing(psi + 0.6 * Geodesy.bearingDiff(g.bearingDeg, psi))
            gyroDeltaDeg = 0.0; psiDR = psi
            maneuverSinceMs = 0L; maneuvering = false
        }
        updateAltitude(g)
        everFixed = true
        lastFixMs = g.timeMs
        lastFixPos = p
    }

    private fun reanchor(p: GeoPoint, now: Long) {
        val dest = plannedRoute.destination
        if (Geodesy.distance(p, dest) < 1_000.0) return
        route = Route(p, dest)
        anchor = p
        s = 0.0
        lastReanchorMs = now
        replanCount++
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
     * @param sensorsLive false in estimate-only mode without a network fix: PREDICTED_ONLY on the planned route
     */
    fun tick(now: Long, phase: FlightPhase, takeoffRefMs: Long?, sensorsLive: Boolean = true): PositionEstimate {
        val dt = if (lastTickMs == 0L) 0.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 30.0)
        lastTickMs = now
        val age = if (everFixed && sensorsLive) now - lastFixMs else Long.MAX_VALUE
        val ageS = age / 1000.0
        val gyroFresh = lastGyroMs != 0L && now - lastGyroMs < 5_000

        val mode = when {
            !sensorsLive || !everFixed -> FusionMode.PREDICTED_ONLY
            age > Parameters.GNSS_LOSS_TO_CONSTRAINED_MS -> FusionMode.ROUTE_CONSTRAINED
            else -> FusionMode.GNSS_TRACKING
        }

        when (mode) {
            FusionMode.PREDICTED_ONLY -> {
                val t = if (takeoffRefMs != null && now > takeoffRefMs) (now - takeoffRefMs) / 1000.0 else 0.0
                val (ps, pv) = predictedProfile(plannedRoute.lengthM, t)
                route = plannedRoute; anchor = null
                s = ps; v = pv; psi = route.bearingAt(s); pos = route.pointAt(s)
                flownMeasured = 0.0; flownSinceFix = ps
                sigmaS = if (t <= 0.0) Parameters.SIGMA_MIN_M else Parameters.SIGMA_S_INITIAL_M + Parameters.ALONG_TRACK_DRIFT_RATE * pv * t
                maneuvering = false
            }
            FusionMode.GNSS_TRACKING -> {
                // Between fixes: short dead reckoning from the measured position.
                if (dt > 0 && v > 0) {
                    val step = v * dt
                    pos = Geodesy.destination(pos, psi, step)
                    s = (s + step).coerceIn(0.0, route.lengthM)
                    flownSinceFix += step
                    sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * step
                }
                gyroDeltaDeg = 0.0
            }
            FusionMode.ROUTE_CONSTRAINED -> {
                val onGround = phase == FlightPhase.GROUND
                val vPhase = phaseSpeed(phase)
                val w = min(1.0, ageS / Parameters.SPEED_BLEND_TAU_S)
                val vEff = if (onGround || (vPhase == 0.0 && vRef < Parameters.PHASE_GROUND_SPEED_MPS)) 0.0 else vRef * (1 - w) + vPhase * w
                v = vEff
                var factor = 1.0
                if (gyroFresh && ageS < Parameters.GYRO_PROGRESS_WINDOW_S) {
                    val dev = Geodesy.bearingDiff(psiDR, route.bearingAt(s))
                    factor = kotlin.math.cos(Math.toRadians(dev))
                    if (abs(dev) > Parameters.MANEUVER_HEADING_DEG) {
                        if (maneuverSinceMs == 0L) maneuverSinceMs = now
                        if (now - maneuverSinceMs > Parameters.MANEUVER_CONFIRM_S * 1000) maneuvering = true
                    } else { maneuverSinceMs = 0L; maneuvering = false }
                } else { maneuverSinceMs = 0L; maneuvering = false }
                val step = vEff * dt * factor
                s = (s + step).coerceIn(0.0, route.lengthM)
                flownSinceFix += abs(step)
                pos = route.pointAt(s)
                sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * vEff * dt * (if (maneuvering) Parameters.MANEUVER_SIGMA_FACTOR else 1.0)
                psi = if (ageS < Parameters.GYRO_TRUST_WINDOW_S && gyroFresh) Geodesy.wrapBearing(psi + gyroDeltaDeg) else route.bearingAt(s)
                gyroDeltaDeg = 0.0
            }
        }

        val fresh = sensorsLive && everFixed && age < Parameters.GNSS_STALE_MS * 2
        val altFresh = sensorsLive && lastAltMs != 0L && now - lastAltMs < Parameters.GNSS_STALE_MS * 2
        val goodNow = fresh && lastQuality == GnssQuality.GOOD

        val posConf = when (mode) {
            FusionMode.GNSS_TRACKING -> if (!fresh) Confidence.FUSED else if (goodNow) Confidence.MEASURED else Confidence.FUSED
            FusionMode.ROUTE_CONSTRAINED -> if (age < 120_000) Confidence.FUSED else Confidence.PREDICTED
            FusionMode.PREDICTED_ONLY -> Confidence.PREDICTED
        }
        val speedConf = if (fresh) Confidence.MEASURED else if (mode == FusionMode.PREDICTED_ONLY) Confidence.PREDICTED else Confidence.FUSED
        val trackConf = when {
            fresh && v > Parameters.GNSS_MIN_SPEED_FOR_TRACK -> Confidence.MEASURED
            mode == FusionMode.ROUTE_CONSTRAINED && ageS < Parameters.GYRO_TRUST_WINDOW_S -> Confidence.FUSED
            else -> Confidence.PREDICTED
        }
        val altConf = if (altFresh) Confidence.MEASURED else if (lastAltMs != 0L && sensorsLive) Confidence.STALE else Confidence.PREDICTED
        val sensorLevel = when {
            goodNow -> 3
            fresh -> 2
            mode == FusionMode.ROUTE_CONSTRAINED -> 1   // last fix + inertial / baro / visual propagation
            else -> 0
        }

        return PositionEstimate(
            timeMs = now, lat = pos.lat, lon = pos.lon,
            altM = if (groundReferenced && lastAltMs != 0L) h - altOffsetM else h,
            groundSpeedMps = v, trackDeg = psi, verticalRateMps = if (altFresh) hdot else 0.0,
            alongTrackM = s, totalFlownM = flownMeasured + flownSinceFix, sigmaAlongM = sigmaS,
            replanCount = replanCount, visualFixCount = visualFixCount,
            maneuvering = maneuvering && mode == FusionMode.ROUTE_CONSTRAINED,
            groundReferenced = groundReferenced,
            originMismatchM = if (sensorsLive && phase == FlightPhase.GROUND) originMismatch else null,
            sensorLevel = sensorLevel,
            mode = mode, phase = phase, lastFixAgeMs = if (everFixed && sensorsLive) age else -1L,
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

    /** Climb / cruise / descent profile along a route of length l. Returns (along m, speed m/s). */
    private fun predictedProfile(l: Double, tS: Double): Pair<Double, Double> {
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

    fun profileDurationS(lengthM: Double): Double = Metrics.profileDurationS(lengthM)

    /** Phase implied by elapsed time on the predicted profile (estimate-only mode). */
    fun predictedPhase(now: Long, takeoffRefMs: Long?): FlightPhase {
        if (takeoffRefMs == null || now <= takeoffRefMs) return FlightPhase.GROUND
        val t = (now - takeoffRefMs) / 1000.0
        val l = plannedRoute.lengthM
        val (ps, pv) = predictedProfile(l, t)
        return when {
            pv == 0.0 && ps >= l -> FlightPhase.LANDED
            t < Parameters.CLIMB_DURATION_S -> FlightPhase.CLIMB
            l - ps <= min(Parameters.DESCENT_DISTANCE_M, l * 0.4) + 1.0 -> FlightPhase.DESCENT
            else -> FlightPhase.CRUISE
        }
    }
}
