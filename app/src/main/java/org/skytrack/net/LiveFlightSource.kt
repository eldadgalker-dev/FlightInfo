// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - LiveFlightSource
// Version 1.0
// Purpose : Fetch the real position of the tracked flight from a community
//           ADS-B aggregator (adsb.lol, free, no key) when the phone happens
//           to have internet: at the gate, on onboard Wi-Fi, or when following
//           someone else's flight from home. The report is converted into a
//           GnssSample so the estimator treats it like any other fix.
//           The app never requires this; without network nothing changes.
// =============================================================
package org.skytrack.net

import org.json.JSONObject
import org.skytrack.Parameters
import org.skytrack.data.Airlines
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample

object LiveFlightSource {

    private const val FT_TO_M = 0.3048
    private const val KT_TO_MPS = 0.514444

    /**
     * Query each candidate callsign for the flight number; return the freshest
     * positioned aircraft as a GnssSample, or null if none is airborne/known.
     * Throws only on network errors (caller decides whether to retry).
     */
    suspend fun fetch(flightNumber: String): GnssSample? {
        for (cs in Airlines.callsignsFor(flightNumber)) {
            val url = String.format(Parameters.LIVE_ADSB_URL_TEMPLATE, cs)
            val body = try { Downloader.getText(url) } catch (e: Exception) { continue }
            parse(body)?.let { return it }
        }
        return null
    }

    /** Parse an adsb.lol / readsb "v2" response. Public for JVM tests. */
    fun parse(body: String, nowMs: Long = System.currentTimeMillis()): GnssSample? {
        val root = JSONObject(body)
        val ac = root.optJSONArray("ac") ?: return null
        var best: GnssSample? = null
        var bestAge = Double.MAX_VALUE
        for (i in 0 until ac.length()) {
            val a = ac.getJSONObject(i)
            if (!a.has("lat") || !a.has("lon")) continue
            val agePos = a.optDouble("seen_pos", a.optDouble("seen", 0.0))
            if (agePos > Parameters.LIVE_MAX_AGE_S || agePos >= bestAge) continue
            val altRaw = a.opt("alt_baro")
            val onGround = altRaw is String && altRaw == "ground"
            val altM = if (altRaw is Number) altRaw.toDouble() * FT_TO_M else 0.0
            val gs = a.optDouble("gs", Double.NaN)
            val track = a.optDouble("track", Double.NaN)
            val speed = if (gs.isNaN()) 0.0 else gs * KT_TO_MPS
            best = GnssSample(
                timeMs = nowMs - (agePos * 1000).toLong(),
                lat = a.getDouble("lat"), lon = a.getDouble("lon"),
                altM = altM, hasAlt = altRaw is Number,
                speedMps = if (onGround) 0.0 else speed, hasSpeed = !gs.isNaN() || onGround,
                bearingDeg = if (track.isNaN()) 0.0 else track, hasBearing = !track.isNaN(),
                hAccM = Parameters.LIVE_FIX_HACC_M, vAccM = 30.0,
                satsUsed = 0, satsVisible = 0,
                quality = GnssQuality.GOOD
            )
            bestAge = agePos
        }
        return best
    }
}
