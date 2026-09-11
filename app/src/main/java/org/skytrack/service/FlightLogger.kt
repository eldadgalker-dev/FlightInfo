// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - FlightLogger
// Version 2.1
// Purpose : Write one CSV row per engine tick with the estimate AND the raw
//           sensor inputs (GNSS, barometer, gyro), so real flights can be
//           replayed offline to calibrate Parameters (taxi time, speed
//           profile, deviation thresholds). Files live in the app's external
//           files directory (Android/data/<applicationId>/files/logs) and
//           can be shared from Settings. Nothing is ever uploaded.
// =============================================================
package org.skytrack.service

import android.content.Context
import org.skytrack.fusion.FlightMetrics
import org.skytrack.sensors.BaroSample
import org.skytrack.sensors.GnssSample
import org.skytrack.sensors.GyroSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class FlightLogger(private val context: Context) {

    private val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var lastFlushMs = 0L
    private var rows = 0

    @Volatile var enabled: Boolean = true

    @Synchronized
    fun start(originIata: String, destinationIata: String, flightNumber: String) {
        stop()
        if (!enabled) return
        try {
            dir.mkdirs()
            rotate()
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.US).format(Instant.now().atZone(ZoneOffset.UTC))
            val fn = flightNumber.ifBlank { "flight" }
            val f = File(dir, "${stamp}_${originIata}_${destinationIata}_$fn.csv")
            val fresh = !f.exists() || f.length() == 0L
            writer = BufferedWriter(FileWriter(f, true))
            if (!fresh) { currentFile = f; rows = 0; return }   // same file re-opened within the minute: no second header
            // Header comments: everything needed to interpret the file later.
            val ver = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }
            writer?.write("# FlightInfo flight log"); writer?.newLine()
            writer?.write("# app_version=$ver device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}"); writer?.newLine()
            writer?.write("# flight=$fn origin=$originIata destination=$destinationIata started_utc=${ISO.format(Instant.now().atZone(ZoneOffset.UTC))}"); writer?.newLine()
            writer?.write("# columns: see header row; distances m, speeds m/s, angles deg, pressure hPa; empty = not available"); writer?.newLine()
            writer?.write(HEADER); writer?.newLine()
            currentFile = f
            rows = 0
        } catch (e: Exception) {
            writer = null
        }
    }

    @Synchronized
    fun stop() {
        try { writer?.flush(); writer?.close() } catch (e: Exception) { }
        writer = null
    }

    /** One row per tick. Raw samples are the most recent ones seen by the engine (may be null). */
    @Synchronized
    fun log(m: FlightMetrics, gnss: GnssSample?, baro: BaroSample?, gyro: GyroSample?) {
        val w = writer ?: return
        val e = m.estimate
        try {
            val sb = StringBuilder(320)
            sb.append(ISO.format(Instant.ofEpochMilli(e.timeMs).atZone(ZoneOffset.UTC))).append(',')
            sb.append(e.timeMs).append(',')
            sb.append(if (m.estimateOnly) "ESTIMATE" else "LIVE").append(',')
            sb.append(e.mode.name).append(',').append(e.phase.name).append(',')
            sb.append(f(e.lat, 6)).append(',').append(f(e.lon, 6)).append(',')
            sb.append(f(e.alongTrackM, 0)).append(',').append(f(e.totalFlownM, 0)).append(',').append(f(m.remainingM, 0)).append(',')
            sb.append(f(e.sigmaAlongM, 0)).append(',')
            sb.append(f(e.groundSpeedMps, 1)).append(',').append(f(e.trackDeg, 1)).append(',').append(f(e.altM, 0)).append(',')
            sb.append(m.eteS ?: "").append(',')
            sb.append(if (e.maneuvering) 1 else 0).append(',').append(m.cabinAltM?.let { f(it, 0) } ?: "").append(',')
            sb.append(e.sensorLevel).append(',').append(e.replanCount).append(',').append(m.positionSource ?: "").append(',')
            if (gnss != null) {
                sb.append(gnss.timeMs).append(',').append(f(gnss.lat, 6)).append(',').append(f(gnss.lon, 6)).append(',')
                sb.append(if (gnss.hasAlt) f(gnss.altM, 0) else "").append(',')
                sb.append(if (gnss.hasSpeed) f(gnss.speedMps, 1) else "").append(',')
                sb.append(if (gnss.hasBearing) f(gnss.bearingDeg, 1) else "").append(',')
                sb.append(f(gnss.hAccM, 0)).append(',').append(gnss.satsUsed).append(',').append(gnss.satsVisible).append(',')
                sb.append(gnss.quality.name).append(',')
            } else sb.append(",,,,,,,,,,")
            if (baro != null) sb.append(f(baro.pressureHpa, 2)).append(',').append(f(baro.rateHpaPerMin, 3)).append(',') else sb.append(",,")
            sb.append(gyro?.let { f(it.yawRateDps, 2) } ?: "")
            w.write(sb.toString()); w.newLine()
            rows++
            if (e.timeMs - lastFlushMs > 10_000) { w.flush(); lastFlushMs = e.timeMs }
        } catch (e: Exception) {
            // Logging must never affect tracking.
        }
    }

    /** Save a PNG of the map next to the current log (called once at landing). */
    @Synchronized
    fun saveSnapshot(bmp: android.graphics.Bitmap) {
        val base = currentFile ?: latestFile() ?: return
        try {
            val f = File(base.parentFile, base.name.removeSuffix(".csv") + "_map.png")
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { }
    }

    fun latestFile(): File? = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }

    fun allFiles(): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteAll() { allFiles().forEach { it.delete() } }

    private fun rotate() {
        val files = allFiles()
        if (files.size >= MAX_FILES) files.drop(MAX_FILES - 1).forEach { it.delete() }
    }

    private fun f(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

    companion object {
        private const val MAX_FILES = 20
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        const val HEADER = "time_utc,epoch_ms,tracking,mode,phase,est_lat,est_lon,along_m,total_flown_m,remaining_m," +
                "sigma_s_m,speed_mps,track_deg,alt_m,ete_s,maneuvering,cabin_alt_m,sensor_level,reanchors,position_source," +
                "gnss_time_ms,gnss_lat,gnss_lon,gnss_alt_m,gnss_speed_mps,gnss_bearing_deg,gnss_hacc_m,gnss_sats_used,gnss_sats_visible,gnss_quality," +
                "baro_hpa,baro_rate_hpa_min,gyro_yaw_dps"
    }
}
