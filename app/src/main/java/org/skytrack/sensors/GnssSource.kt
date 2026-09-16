// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - GnssSource
// Version 1.4
// Purpose : Wrap android.location.LocationManager (GPS_PROVIDER) and
//           GnssStatus into a cold Flow of classified GNSS samples.
//           LocationManager is used directly, not Fused Location, because
//           satellite counts and raw status are required and there is no
//           network in flight anyway.
// =============================================================
package org.skytrack.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.skytrack.Parameters

enum class GnssQuality { GOOD, DEGRADED, NONE }

data class GnssSample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val hasAlt: Boolean,
    val speedMps: Double,
    val hasSpeed: Boolean,
    val bearingDeg: Double,
    val hasBearing: Boolean,
    val hAccM: Double,
    val vAccM: Double,
    val satsUsed: Int,
    val satsVisible: Int,
    val quality: GnssQuality,
    /** Replay only: a trusted position from the log's estimate columns, not a receiver fix. */
    val virtual: Boolean = false
)

class GnssSource(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var satsUsed = 0
    @Volatile private var satsVisible = 0

    /** Emits one sample per fix. Requires ACCESS_FINE_LOCATION already granted. */
    @SuppressLint("MissingPermission")
    fun samples(intervalMs: Long = Parameters.GNSS_INTERVAL_SCREEN_ON_MS): Flow<GnssSample> = callbackFlow {
        val thread = HandlerThread("skytrack-gnss").apply { start() }
        val handler = Handler(thread.looper)

        val statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                satsUsed = used
                satsVisible = status.satelliteCount
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { trySend(classify(loc)) }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        lm.registerGnssStatusCallback(statusCb, handler)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, thread.looper)

        awaitClose {
            lm.removeUpdates(listener)
            lm.unregisterGnssStatusCallback(statusCb)
            thread.quitSafely()
        }
    }

    /**
     * "Where am I" indoors as well: the fused provider (Wi-Fi / cell / Bluetooth, the one Google Maps
     * uses; Android 12+) first, then network, then GPS. The first answer from any provider wins, is
     * stored for the next launch, and the other requests are cancelled. Nothing without permission.
     */
    @SuppressLint("MissingPermission")
    fun requestSingleFix(onFix: (Location) -> Unit) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var delivered = false
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (delivered) return
                    delivered = true; lm.removeUpdates(this); remember(loc); onFix(loc)
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            for (prov in providers()) {
                try { lm.requestLocationUpdates(prov, 1000L, 0f, listener, android.os.Looper.getMainLooper()) } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    private fun providers(): List<String> {
        val out = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) out.add(LocationManager.FUSED_PROVIDER)
        out.add(LocationManager.NETWORK_PROVIDER); out.add(LocationManager.GPS_PROVIDER)
        return out.filter { try { lm.allProviders.contains(it) } catch (e: Exception) { false } }
    }

    /**
     * Last known position for the start-up camera: the freshest of all providers, or the position
     * this app stored at its previous run (survives a reboot, when the system caches are empty).
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        var best: Location? = null
        try {
            for (prov in providers()) {
                val l = try { lm.getLastKnownLocation(prov) } catch (e: Exception) { null } ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (e: Exception) { }
        if (best != null) { remember(best!!); return best }
        val prefs = context.getSharedPreferences("skytrack", Context.MODE_PRIVATE)
        if (!prefs.contains("last_loc_lat")) return null
        return Location("stored").apply {
            latitude = prefs.getFloat("last_loc_lat", 0f).toDouble(); longitude = prefs.getFloat("last_loc_lon", 0f).toDouble()
            time = prefs.getLong("last_loc_time", 0L)
        }
    }

    /** Store the latest device position for the next launch. */
    fun remember(loc: Location) {
        try {
            context.getSharedPreferences("skytrack", Context.MODE_PRIVATE).edit()
                .putFloat("last_loc_lat", loc.latitude.toFloat()).putFloat("last_loc_lon", loc.longitude.toFloat()).putLong("last_loc_time", loc.time).apply()
        } catch (e: Exception) { }
    }

    private var lastRememberMs = 0L
    private fun rememberThrottled(loc: Location) {
        val now = System.currentTimeMillis()
        if (now - lastRememberMs > 30_000) { lastRememberMs = now; remember(loc) }
    }

    private fun classify(loc: Location): GnssSample {
        rememberThrottled(loc)
        val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0
        val vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else 9999.0
        val q = when {
            hAcc <= Parameters.GNSS_GOOD_HACC_M && satsUsed >= Parameters.GNSS_GOOD_MIN_SATS -> GnssQuality.GOOD
            hAcc <= Parameters.GNSS_DEGRADED_HACC_M -> GnssQuality.DEGRADED
            else -> GnssQuality.NONE
        }
        return GnssSample(
            timeMs = System.currentTimeMillis(),
            lat = loc.latitude, lon = loc.longitude,
            altM = loc.altitude, hasAlt = loc.hasAltitude(),
            speedMps = loc.speed.toDouble(), hasSpeed = loc.hasSpeed(),
            bearingDeg = loc.bearing.toDouble(), hasBearing = loc.hasBearing(),
            hAccM = hAcc, vAccM = vAcc,
            satsUsed = satsUsed, satsVisible = satsVisible,
            quality = q
        )
    }
}
