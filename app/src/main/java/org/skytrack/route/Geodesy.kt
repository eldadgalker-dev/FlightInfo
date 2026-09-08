// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - Geodesy
// Version 1.1
// Purpose : Spherical great-circle mathematics. A sphere is used
//           deliberately: the ~0.3 % error versus WGS84 is far below
//           the estimator's own uncertainty and keeps the code
//           dependency-free and testable on the JVM.
// Units   : degrees in/out for lat/lon/bearing, metres for distance.
// =============================================================
package org.skytrack.route

import org.skytrack.Parameters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geodesy {

    private const val R = Parameters.EARTH_RADIUS_M

    fun toRad(deg: Double): Double = deg * PI / 180.0
    fun toDeg(rad: Double): Double = rad * 180.0 / PI

    /** Normalise a longitude to [-180, 180). */
    fun wrapLon(lon: Double): Double {
        var l = lon
        while (l >= 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    /** Normalise a bearing to [0, 360). */
    fun wrapBearing(deg: Double): Double {
        var b = deg % 360.0
        if (b < 0) b += 360.0
        return b
    }

    /** Signed smallest difference a - b in degrees, range (-180, 180]. */
    fun bearingDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Haversine great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLon = toRad(b.lon - a.lon)
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from a to b in degrees [0, 360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val dLon = toRad(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return wrapBearing(toDeg(atan2(y, x)))
    }

    /** Destination point from start along bearing (deg) for distance (m). */
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val ang = distanceM / R
        val la1 = toRad(start.lat)
        val lo1 = toRad(start.lon)
        val brg = toRad(bearingDeg)
        val la2 = asin(sin(la1) * cos(ang) + cos(la1) * sin(ang) * cos(brg))
        val lo2 = lo1 + atan2(sin(brg) * sin(ang) * cos(la1), cos(ang) - sin(la1) * sin(la2))
        return GeoPoint(toDeg(la2), wrapLon(toDeg(lo2)))
    }

    /**
     * Point at fraction f (0..1) along the great circle from a to b.
     * Uses spherical linear interpolation so antimeridian crossings are correct.
     */
    fun intermediate(a: GeoPoint, b: GeoPoint, f: Double): GeoPoint {
        val d = distance(a, b) / R
        if (d < 1e-12) return a
        val la1 = toRad(a.lat); val lo1 = toRad(a.lon)
        val la2 = toRad(b.lat); val lo2 = toRad(b.lon)
        val aa = sin((1 - f) * d) / sin(d)
        val bb = sin(f * d) / sin(d)
        val x = aa * cos(la1) * cos(lo1) + bb * cos(la2) * cos(lo2)
        val y = aa * cos(la1) * sin(lo1) + bb * cos(la2) * sin(lo2)
        val z = aa * sin(la1) + bb * sin(la2)
        return GeoPoint(toDeg(atan2(z, sqrt(x * x + y * y))), wrapLon(toDeg(atan2(y, x))))
    }

    /**
     * Cross-track and along-track distance of point p relative to the great
     * circle segment from a to b. Cross-track is positive to the right of the
     * direction of travel. Along-track is measured from a.
     */
    fun crossAlongTrack(a: GeoPoint, b: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val d13 = distance(a, p) / R
        val th13 = toRad(bearing(a, p))
        val th12 = toRad(bearing(a, b))
        val dxt = asin((sin(d13) * sin(th13 - th12)).coerceIn(-1.0, 1.0))
        val cosDxt = cos(dxt)
        // Along-track: acos(cos(d13)/cos(dxt)); sign taken from the bearing difference
        // so points behind the segment start yield a negative value.
        val ratio = if (abs(cosDxt) < 1e-12) 1.0 else (cos(d13) / cosDxt).coerceIn(-1.0, 1.0)
        val along = acos(ratio) * (if (cos(th13 - th12) >= 0) 1.0 else -1.0)
        return Pair(dxt * R, along * R)
    }
}
