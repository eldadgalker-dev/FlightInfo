// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Metrics
// Version 2.3
// Purpose : Derive every displayed value (origin / now / destination
//           columns) from a PositionEstimate, the Route and the airports.
//           Time zones come from the airport table (IANA); the current
//           position is shown in UTC because no tz-polygon database is
//           bundled (zero-budget constraint, documented in README).
// Units   : SI internally; formatting happens in ui/Format.kt.
// =============================================================
package org.skytrack.fusion

import org.skytrack.Parameters
import org.skytrack.data.Airport
import org.skytrack.route.GeoPoint
import org.skytrack.route.Route
import org.skytrack.sensors.FlightPhase
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max

data class FlightMetrics(
    val estimate: PositionEstimate,
    val origin: Airport,
    val destination: Airport,
    val route: Route,               // governing route (re-planned after a proven deviation)
    val plannedRoute: Route,        // original great circle origin -> destination
    val actualTrack: List<GeoPoint>,// decimated GOOD fixes (drawn once a deviation is proven)
    val estimateOnly: Boolean,      // sensors deliberately ignored
    val overflownCountry: String?,  // localised name of the country under the estimated position
    val routeLengthM: Double,
    val flownM: Double,
    val remainingM: Double,
    val percentComplete: Double,
    val elapsedS: Long?,            // since takeoff, null if not airborne yet
    val eteS: Long?,                // estimated time en route remaining
    val etaUtc: Instant?,
    val takeoffUtc: Instant?,
    val nowUtc: Instant,
    val originZone: ZoneId,
    val destinationZone: ZoneId,
    val eteConfidence: Confidence
) {
    val nowAtOrigin: ZonedDateTime get() = nowUtc.atZone(originZone)
    val nowAtDestination: ZonedDateTime get() = nowUtc.atZone(destinationZone)
    val etaAtDestination: ZonedDateTime? get() = etaUtc?.atZone(destinationZone)
    val takeoffAtOrigin: ZonedDateTime? get() = takeoffUtc?.atZone(originZone)
    val totalFlightS: Long? get() = if (elapsedS != null && eteS != null) elapsedS + eteS else null
}

object Metrics {

    fun compute(e: PositionEstimate, route: Route, plannedRoute: Route, actualTrack: List<GeoPoint>,
                estimateOnly: Boolean, origin: Airport, destination: Airport, takeoffMs: Long?,
                overflownCountry: String? = null): FlightMetrics {
        val remaining = (route.lengthM - e.alongTrackM).coerceAtLeast(0.0)
        val flown = e.totalFlownM.coerceAtLeast(0.0)
        val total = flown + remaining

        // Effective speed for ETE: measured speed in cruise, otherwise the phase-typical
        // value, so that a slow taxi or a stale zero does not produce an infinite ETE.
        val vMeasured = e.groundSpeedMps
        val vCruise = if (vMeasured > 100.0) vMeasured else Parameters.SPEED_CRUISE_MPS
        val descentDist = minOf(Parameters.DESCENT_DISTANCE_M, route.lengthM * 0.4)

        val ete: Double = when {
            e.phase == FlightPhase.LANDED || remaining < 1_000.0 -> 0.0
            e.phase == FlightPhase.DESCENT -> remaining / max(60.0, if (vMeasured > 60.0) vMeasured else Parameters.SPEED_DESCENT_MPS)
            remaining > descentDist -> (remaining - descentDist) / vCruise + Parameters.DESCENT_ALLOWANCE_S
            else -> remaining / Parameters.SPEED_DESCENT_MPS
        }
        val now = Instant.ofEpochMilli(e.timeMs)
        val airborne = e.phase != FlightPhase.GROUND && takeoffMs != null
        val elapsed = if (airborne) (e.timeMs - takeoffMs!!) / 1000 else null
        val eteS = ete.toLong()
        val eta = now.plusSeconds(eteS)

        val eteConf = when (e.positionConfidence) {
            Confidence.MEASURED -> if (e.speedConfidence == Confidence.MEASURED) Confidence.MEASURED else Confidence.FUSED
            Confidence.FUSED -> Confidence.FUSED
            else -> Confidence.PREDICTED
        }

        return FlightMetrics(
            estimate = e, origin = origin, destination = destination, route = route,
            plannedRoute = plannedRoute, actualTrack = actualTrack, estimateOnly = estimateOnly, overflownCountry = overflownCountry,
            routeLengthM = total, flownM = flown, remainingM = remaining,
            percentComplete = if (total > 0) 100.0 * flown / total else 0.0,
            elapsedS = elapsed, eteS = eteS, etaUtc = eta,
            takeoffUtc = takeoffMs?.let { Instant.ofEpochMilli(it) },
            nowUtc = now,
            originZone = zoneOf(origin.tz), destinationZone = zoneOf(destination.tz),
            eteConfidence = eteConf
        )
    }

    private fun zoneOf(id: String): ZoneId = try { ZoneId.of(id) } catch (e: Exception) { ZoneId.of("UTC") }
}
