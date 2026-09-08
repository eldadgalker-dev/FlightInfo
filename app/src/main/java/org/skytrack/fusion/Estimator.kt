// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Estimator
// Version 1.1
// Purpose : Position estimator with four modes:
//             GNSS_TRACKING    - scalar Kalman updates on along/cross track
//             ROUTE_CONSTRAINED- GNSS lost: propagate along the route only,
//                                cross-track decays to zero, heading follows
//                                gyro briefly then the route course
//             PREDICTED_ONLY   - never had a fix: time-since-takeoff profile
//             OFF_ROUTE        - aircraft is far from the planned route:
//                                free 2-D dead reckoning from raw GNSS
//           Pure Kotlin, no Android dependencies, deterministic given inputs.
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class FusionMode { GNSS_TRACKING, ROUTE_CONSTRAINED, PREDICTED_ONLY, OFF_ROUTE }
enum class Confidence { MEASURED, FUSED, PREDICTED, STALE }

data class PositionEstimate(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val groundSpeedMps: Double,
    val trackDeg: Double,
    val verticalRateMps: Double,
    val alongTrackM: Double,
    val crossTrackM: Double,
    val sigmaAlongM: Double,
    val sigmaCrossM: Double,
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

class Estimator(val route: Route) {

    // -- State --
    private var s = 0.0                 // m, along-track distance
    private var d = 0.0                 // m, cross-track offset (right positive)
    private var v = 0.0                 // m/s, ground speed
    private var psi = route.bearingAt(0.0) // deg, track
    private var h = 0.0                 // m, altitude
    private var hdot = 0.0              // m/s, vertical rate
    private var sigmaS = Parameters.SIGMA_S_INITIAL_M
    private var sigmaD = Parameters.SIGMA_D_INITIAL_M

    // -- GNSS bookkeeping --
    private var everFixed = false
    private var lastFixMs = 0L          // any usable fix (GOOD or DEGRADED)
    private var lastAltMs = 0L
    private var lastAltM = 0.0
    private var satsUsed = 0
    private var satsVisible = 0
    private var lastQuality = GnssQuality.NONE
    private var vRef = 0.0              // m/s, moving-average speed at time of last fix
    private val speedWin = ArrayDeque<Pair<Long, Double>>()

    // -- Off-route --
    private var offRoute = false
    private var offRouteCount = 0
    private var insideSinceMs = 0L
    private var freePos = GeoPoint(route.origin.lat, route.origin.lon)

    // -- Gyro --
    private var gyroDeltaDeg = 0.0      // accumulated heading change since last tick
    private var lastGyroMs = 0L

    private var lastTickMs = 0L

    /** Restore a persisted state after relaunch. */
    fun restore(alongM: Double, crossM: Double, speedMps: Double, trackDeg: Double, altM: Double, timeMs: Long) {
        s = alongM.coerceIn(0.0, route.lengthM); d = crossM; v = speedMps; psi = trackDeg; h = altM
        vRef = speedMps; everFixed = true; lastFixMs = timeMs; lastAltMs = 0L
        sigmaS = max(Parameters.SIGMA_MIN_M, Parameters.ALONG_TRACK_DRIFT_RATE * speedMps * 60.0)
        sigmaD = 5_000.0
    }

    fun onGyro(g: GyroSample) {
        if (lastGyroMs != 0L) {
            val dt = (g.timeMs - lastGyroMs) / 1000.0
            if (dt > 0 && dt < 1.0) gyroDeltaDeg += g.yawRateDps * dt
        }
        lastGyroMs = g.timeMs
    }

    fun onGnss(g: GnssSample) {
        satsUsed = g.satsUsed; satsVisible = g.satsVisible; lastQuality = g.quality
        if (g.quality == GnssQuality.NONE) return

        val p = GeoPoint(g.lat, g.lon)
        val proj = route.project(p)

        // Off-route detection uses only GOOD fixes to avoid false triggers.
        if (g.quality == GnssQuality.GOOD) {
            if (abs(proj.crossM) > Parameters.OFF_ROUTE_M) {
                offRouteCount++
                if (offRouteCount >= Parameters.OFF_ROUTE_CONSECUTIVE) { offRoute = true; insideSinceMs = 0L }
            } else {
                offRouteCount = 0
                if (offRoute) {
                    if (insideSinceMs == 0L) insideSinceMs = g.timeMs
                    if (g.timeMs - insideSinceMs > Parameters.OFF_ROUTE_CLEAR_S * 1000) offRoute = false
                }
            }
        }
        freePos = p

        // Scalar Kalman updates: K = P / (P + R). The first fix after a long gap
        // has a large P and therefore snaps almost fully to the measurement.
        val r = max(g.hAccM, Parameters.SIGMA_MIN_M)
        val ks = (sigmaS * sigmaS) / (sigmaS * sigmaS + r * r)
        val kd = (sigmaD * sigmaD) / (sigmaD * sigmaD + r * r)
        s += ks * (proj.alongM - s)
        d += kd * (proj.crossM - d)
        sigmaS = max(Parameters.SIGMA_MIN_M, kotlin.math.sqrt((1 - ks) * sigmaS * sigmaS))
        sigmaD = max(Parameters.SIGMA_MIN_M, kotlin.math.sqrt((1 - kd) * sigmaD * sigmaD))
        s = s.coerceIn(0.0, route.lengthM)

        if (g.hasSpeed) {
            v = if (everFixed) v + 0.5 * (g.speedMps - v) else g.speedMps
            speedWin.addLast(Pair(g.timeMs, g.speedMps))
            while (speedWin.isNotEmpty() && g.timeMs - speedWin.first().first > Parameters.SPEED_AVG_WINDOW_S * 1000) speedWin.removeFirst()
            vRef = speedWin.map { it.second }.average()
        }
        if (g.hasBearing && g.speedMps > Parameters.GNSS_MIN_SPEED_FOR_TRACK) {
            psi = Geodesy.wrapBearing(psi + 0.6 * Geodesy.bearingDiff(g.bearingDeg, psi))
            gyroDeltaDeg = 0.0
        }
        if (g.hasAlt) {
            if (lastAltMs != 0L) {
                val dt = (g.timeMs - lastAltMs) / 1000.0
                if (dt > 0.5) hdot = hdot + 0.3 * ((g.altM - lastAltM) / dt - hdot)
            }
            h = g.altM; lastAltM = g.altM; lastAltMs = g.timeMs
        }
        everFixed = true
        lastFixMs = g.timeMs
    }

    /**
     * Propagate to `now` and emit an estimate.
     * @param phase            current flight phase from the detector
     * @param takeoffRefMs     takeoff time (measured or scheduled) for PREDICTED_ONLY, null if unknown
     */
    fun tick(now: Long, phase: FlightPhase, takeoffRefMs: Long?): PositionEstimate {
        val dt = if (lastTickMs == 0L) 0.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 30.0)
        lastTickMs = now
        val age = if (everFixed) now - lastFixMs else Long.MAX_VALUE

        val mode = when {
            !everFixed -> FusionMode.PREDICTED_ONLY
            offRoute -> FusionMode.OFF_ROUTE
            age > Parameters.GNSS_LOSS_TO_CONSTRAINED_MS -> FusionMode.ROUTE_CONSTRAINED
            else -> FusionMode.GNSS_TRACKING
        }

        when (mode) {
            FusionMode.PREDICTED_ONLY -> {
                val t = if (takeoffRefMs != null && now > takeoffRefMs) (now - takeoffRefMs) / 1000.0 else 0.0
                val (ps, pv) = predictedProfile(t)
                s = ps; v = pv; psi = route.bearingAt(s); d = 0.0
                sigmaS = Parameters.SIGMA_S_INITIAL_M + Parameters.ALONG_TRACK_DRIFT_RATE * pv * t
                sigmaD = Parameters.SIGMA_D_INITIAL_M
            }
            FusionMode.GNSS_TRACKING -> {
                // Smooth motion between fixes; uncertainty grows slowly.
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
                d *= exp(-dt / Parameters.CROSS_TRACK_DECAY_TAU_S)
                sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * vEff * dt
                sigmaD = max(sigmaD, 2_000.0)
                if (ageS < Parameters.GYRO_TRUST_WINDOW_S && lastGyroMs != 0L && now - lastGyroMs < 5_000) {
                    psi = Geodesy.wrapBearing(psi + gyroDeltaDeg)
                } else {
                    psi = route.bearingAt(s)
                }
                gyroDeltaDeg = 0.0
            }
            FusionMode.OFF_ROUTE -> {
                if (age > Parameters.GNSS_LOSS_TO_CONSTRAINED_MS) {
                    freePos = Geodesy.destination(freePos, psi, v * dt)
                    sigmaS += Parameters.ALONG_TRACK_DRIFT_RATE * v * dt
                    sigmaD += Parameters.ALONG_TRACK_DRIFT_RATE * v * dt
                }
                gyroDeltaDeg = 0.0
            }
        }

        val pos = if (mode == FusionMode.OFF_ROUTE) freePos else route.pointAtWithOffset(s, d)
        val fresh = everFixed && age < Parameters.GNSS_STALE_MS * 2
        val altFresh = lastAltMs != 0L && now - lastAltMs < Parameters.GNSS_STALE_MS * 2

        val posConf = when (mode) {
            FusionMode.GNSS_TRACKING, FusionMode.OFF_ROUTE ->
                if (!fresh) Confidence.PREDICTED else if (lastQuality == GnssQuality.GOOD) Confidence.MEASURED else Confidence.FUSED
            FusionMode.ROUTE_CONSTRAINED -> if (age < 120_000) Confidence.FUSED else Confidence.PREDICTED
            FusionMode.PREDICTED_ONLY -> Confidence.PREDICTED
        }
        val speedConf = if (fresh) Confidence.MEASURED else if (mode == FusionMode.PREDICTED_ONLY) Confidence.PREDICTED else Confidence.FUSED
        val trackConf = when {
            fresh && v > Parameters.GNSS_MIN_SPEED_FOR_TRACK -> Confidence.MEASURED
            mode == FusionMode.ROUTE_CONSTRAINED && age / 1000.0 < Parameters.GYRO_TRUST_WINDOW_S -> Confidence.FUSED
            else -> Confidence.PREDICTED
        }
        val altConf = if (altFresh) Confidence.MEASURED else if (lastAltMs != 0L) Confidence.STALE else Confidence.PREDICTED

        return PositionEstimate(
            timeMs = now, lat = pos.lat, lon = pos.lon, altM = h,
            groundSpeedMps = v, trackDeg = psi, verticalRateMps = if (altFresh) hdot else 0.0,
            alongTrackM = if (mode == FusionMode.OFF_ROUTE) route.project(freePos).alongM else s,
            crossTrackM = if (mode == FusionMode.OFF_ROUTE) route.project(freePos).crossM else d,
            sigmaAlongM = sigmaS, sigmaCrossM = sigmaD,
            mode = mode, phase = phase, lastFixAgeMs = if (everFixed) age else -1L,
            satsUsed = satsUsed, satsVisible = satsVisible, gnssQuality = lastQuality,
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
     * Piecewise profile for PREDICTED_ONLY: climb at SPEED_CLIMB for CLIMB_DURATION,
     * cruise at SPEED_CRUISE, descend at SPEED_DESCENT over the last DESCENT_DISTANCE.
     * Returns (along-track m, speed m/s).
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
}
