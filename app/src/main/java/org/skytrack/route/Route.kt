// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Route
// Version 1.1
// Purpose : Planned route as a sampled great-circle polyline with
//           along-track (s) / cross-track (d) projection, point-at-s
//           and bearing-at-s queries. This is the 1-D manifold that the
//           estimator collapses onto when GNSS is unavailable.
// Units   : metres for s and d, degrees for lat/lon/bearing.
// =============================================================
package org.skytrack.route

import org.skytrack.Parameters
import kotlin.math.abs

class Route(val origin: GeoPoint, val destination: GeoPoint, spacingM: Double = Parameters.ROUTE_SAMPLE_SPACING_M) {

    /** Sampled vertices from origin to destination inclusive. */
    val points: List<GeoPoint>

    /** Cumulative along-track distance at each vertex; cum[0] = 0. */
    private val cum: DoubleArray

    /** Total great-circle length in metres. */
    val lengthM: Double

    init {
        val total = Geodesy.distance(origin, destination)
        val n = maxOf(2, (total / spacingM).toInt() + 1)
        points = (0..n).map { i -> Geodesy.intermediate(origin, destination, i.toDouble() / n) }
        cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geodesy.distance(points[i - 1], points[i])
        }
        lengthM = cum.last()
    }

    /** Result of projecting a position onto the route. */
    data class Projection(val alongM: Double, val crossM: Double, val segment: Int)

    /**
     * Project p onto the route. Finds the nearest segment by cross-track
     * distance with a valid along-track parameter, then returns the global
     * along-track distance. Handles antimeridian crossings because every
     * computation is done on the sphere, never in a planar projection.
     */
    fun project(p: GeoPoint): Projection {
        var best: Projection? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val segLen = cum[i + 1] - cum[i]
            if (segLen <= 0.0) continue
            val (dxt, dat) = Geodesy.crossAlongTrack(points[i], points[i + 1], p)
            // Clamp the along-track parameter to the segment and penalise the overshoot
            // so the true nearest segment wins even when p is off the end of a segment.
            val clamped = dat.coerceIn(0.0, segLen)
            val overshoot = abs(dat - clamped)
            val score = abs(dxt) + overshoot
            if (score < bestScore) {
                bestScore = score
                best = Projection(cum[i] + clamped, dxt, i)
            }
        }
        return best ?: Projection(0.0, 0.0, 0)
    }

    /** Position on the route at along-track distance s (clamped to [0, length]). */
    fun pointAt(sM: Double): GeoPoint {
        val s = sM.coerceIn(0.0, lengthM)
        val i = segmentIndexFor(s)
        val segLen = cum[i + 1] - cum[i]
        val f = if (segLen <= 0.0) 0.0 else (s - cum[i]) / segLen
        return Geodesy.intermediate(points[i], points[i + 1], f)
    }

    /** Course of the route at along-track distance s, degrees true. */
    fun bearingAt(sM: Double): Double {
        val s = sM.coerceIn(0.0, lengthM)
        val i = segmentIndexFor(s)
        val here = pointAt(s)
        val ahead = pointAt(minOf(lengthM, s + 1_000.0))
        return if (Geodesy.distance(here, ahead) < 1.0) Geodesy.bearing(points[i], points[i + 1]) else Geodesy.bearing(here, ahead)
    }

    /**
     * Position offset laterally from the route: point at s, moved crossM
     * metres perpendicular to the local course (positive = right of track).
     */
    fun pointAtWithOffset(sM: Double, crossM: Double): GeoPoint {
        val base = pointAt(sM)
        if (abs(crossM) < 1.0) return base
        return Geodesy.destination(base, Geodesy.wrapBearing(bearingAt(sM) + 90.0), crossM)
    }

    /** Vertices from origin to s, for drawing the flown part of the route. */
    fun polylineUpTo(sM: Double): List<GeoPoint> {
        val s = sM.coerceIn(0.0, lengthM)
        val i = segmentIndexFor(s)
        return points.subList(0, i + 1) + pointAt(s)
    }

    private fun segmentIndexFor(s: Double): Int {
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, cum.size - 2)
    }
}
