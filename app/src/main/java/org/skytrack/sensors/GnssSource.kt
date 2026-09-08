// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - GnssSource
// Version 1.1
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
    val quality: GnssQuality
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

    /** Last known fix from any provider (used for origin detection at startup). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = try {
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { null }

    private fun classify(loc: Location): GnssSample {
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
