// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - FlightLogReader
// Version 1.2
// Purpose : Parse the CSV flight logs written by FlightLogger, tolerant of
//           column sets from older versions (columns are looked up by name)
//           and of the duplicated header rows older versions produced.
//           Produces a summary for the log list and a row sequence for replay.
// =============================================================
package org.skytrack.service

import org.skytrack.sensors.FlightPhase
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample
import org.skytrack.sensors.GyroSample
import java.io.File

data class LogSummary(
    val file: File,
    val originIata: String?,
    val destinationIata: String?,
    val flightNumber: String?,
    val startMs: Long,
    val endMs: Long,
    val rows: Int,
    val fixes: Int,
    val appVersion: String?,
    val snapshot: File?,
    /** Estimate columns are measured-first (app 4.x+) or produced by clean_log.py: usable for replay. */
    val estTrusted: Boolean = false
) {
    val durationS: Long get() = (endMs - startMs) / 1000
}

/** One second of the recorded flight. */
data class ReplayRow(val timeMs: Long, val phase: FlightPhase, val gnss: GnssSample?, val gyro: GyroSample?, val liveTracking: Boolean,
                     /** The log's own estimate for rows without a fix (used only when the log is trusted: app >= 4 or cleaned). */
                     val est: GnssSample? = null)

object FlightLogReader {

    /** Header-only pass: cheap enough for a list of 20 files. */
    fun summarize(f: File): LogSummary? = try {
        var origin: String? = null; var dest: String? = null; var fn: String? = null; var ver: String? = null; var cleaned = false
        var header: List<String>? = null
        var first = -1L; var last = -1L; var rows = 0; var fixes = 0; var lastGnss = ""
        f.bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.startsWith("#")) {
                    Regex("origin=(\\w+)").find(ln)?.let { origin = it.groupValues[1] }
                    Regex("destination=(\\w+)").find(ln)?.let { dest = it.groupValues[1] }
                    Regex("flight=(\\S+)").find(ln)?.let { fn = it.groupValues[1] }
                    Regex("app_version=(\\S+)").find(ln)?.let { ver = it.groupValues[1] }
                    if (ln.contains("cleaned", ignoreCase = true) || ln.contains("clean_log")) cleaned = true
                    continue
                }
                if (ln.startsWith("time_utc")) { header = ln.split(','); continue }
                val h = header ?: continue
                val c = ln.split(',')
                val t = c.getOrNull(h.indexOf("epoch_ms"))?.toLongOrNull() ?: continue
                if (first < 0) first = t
                last = t; rows++
                val g = c.getOrNull(h.indexOf("gnss_time_ms")) ?: ""
                if (g.isNotEmpty() && g != lastGnss) { fixes++; lastGnss = g }
            }
        }
        if (origin == null || dest == null) {
            // Older logs: derive from the file name  yyyymmdd_hhmm_ORG_DST_FLIGHT.csv
            val parts = f.nameWithoutExtension.split('_')
            if (parts.size >= 4) { origin = origin ?: parts[2]; dest = dest ?: parts[3]; fn = fn ?: parts.getOrNull(4) }
        }
        val snap = File(f.parentFile, f.nameWithoutExtension + "_map.png").takeIf { it.exists() }
        val major = ver?.substringBefore('.')?.toIntOrNull() ?: 0
        if (rows == 0) null else LogSummary(f, origin, dest, fn, first, last, rows, fixes, ver, snap, cleaned || major >= 4)
    } catch (e: Exception) { null }

    /** Grouping key for logs of the same flight on the same day. */
    fun groupKey(s: LogSummary): String =
        "${s.originIata}_${s.destinationIata}_${s.flightNumber}_" + java.time.Instant.ofEpochMilli(s.startMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    /**
     * Merge several logs of the same flight into the earliest file: header comments and column
     * row from the first file, data rows from all files sorted by epoch and de-duplicated.
     * Column sets may differ between app versions; rows are re-mapped onto the first file's header.
     * Returns the merged file; the other files (and their snapshots) are deleted.
     */
    fun merge(files: List<File>): File? {
        if (files.size < 2) return files.firstOrNull()
        val sorted = files.sortedBy { it.name }
        val target = sorted.first()
        var header: List<String>? = null
        val comments = ArrayList<String>()
        val rows = java.util.TreeMap<Long, String>()
        for (f in sorted) {
            var h: List<String>? = null
            f.bufferedReader().useLines { lines ->
                for (ln in lines) {
                    if (ln.startsWith("#")) { if (header == null) comments.add(ln); continue }
                    if (ln.startsWith("time_utc")) { h = ln.split(','); if (header == null) header = h; continue }
                    val hh = h ?: continue
                    val target = header ?: continue
                    val c = ln.split(',')
                    val t = c.getOrNull(hh.indexOf("epoch_ms"))?.toLongOrNull() ?: continue
                    val line = if (hh == target) ln else target.joinToString(",") { col -> c.getOrNull(hh.indexOf(col)) ?: "" }
                    rows.putIfAbsent(t, line)
                }
            }
        }
        val hdr = header ?: return null
        val tmp = File(target.parentFile, target.name + ".merge")
        tmp.bufferedWriter().use { w ->
            for (c in comments) { w.write(c); w.newLine() }
            w.write("# merged from ${sorted.size} files: ${sorted.joinToString(" ") { it.name }}"); w.newLine()
            w.write(hdr.joinToString(",")); w.newLine()
            for (line in rows.values) { w.write(line); w.newLine() }
        }
        for (f in sorted.drop(1)) { f.delete(); File(f.parentFile, f.nameWithoutExtension + "_map.png").delete() }
        target.delete()
        tmp.renameTo(target)
        return target
    }

    /** Full pass for replay. Consecutive rows repeating the same fix carry gnss = null. */
    fun rows(f: File): List<ReplayRow> {
        val out = ArrayList<ReplayRow>(12_000)
        var header: List<String>? = null
        var lastGnss = ""
        f.bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.startsWith("#")) continue
                if (ln.startsWith("time_utc")) { header = ln.split(','); continue }
                val h = header ?: continue
                val c = ln.split(',')
                fun s(name: String): String = c.getOrNull(h.indexOf(name)) ?: ""
                fun d(name: String): Double? = s(name).toDoubleOrNull()
                val t = s("epoch_ms").toLongOrNull() ?: continue
                val phase = try { FlightPhase.valueOf(s("phase")) } catch (e: Exception) { FlightPhase.CRUISE }
                val gt = s("gnss_time_ms")
                var gnss: GnssSample? = null
                if (gt.isNotEmpty() && gt != lastGnss) {
                    lastGnss = gt
                    val lat = d("gnss_lat"); val lon = d("gnss_lon")
                    if (lat != null && lon != null) {
                        val hacc = d("gnss_hacc_m") ?: 9999.0
                        val sats = s("gnss_sats_used").toIntOrNull() ?: 0
                        val q = try { GnssQuality.valueOf(s("gnss_quality")) } catch (e: Exception) {
                            if (hacc <= 100 && sats >= 5) GnssQuality.GOOD else if (hacc <= 2000) GnssQuality.DEGRADED else GnssQuality.NONE
                        }
                        gnss = GnssSample(gt.toLong(), lat, lon, d("gnss_alt_m") ?: 0.0, s("gnss_alt_m").isNotEmpty(),
                            d("gnss_speed_mps") ?: 0.0, s("gnss_speed_mps").isNotEmpty(),
                            d("gnss_bearing_deg") ?: 0.0, s("gnss_bearing_deg").isNotEmpty(),
                            hacc, 30.0, sats, s("gnss_sats_visible").toIntOrNull() ?: 0, q)
                    }
                }
                val gyro = d("gyro_yaw_dps")?.let { GyroSample(t, it) }
                var est: GnssSample? = null
                if (gnss == null) {
                    val el = d("est_lat"); val eo = d("est_lon")
                    if (el != null && eo != null && s("mode") != "PREDICTED_ONLY")
                        est = GnssSample(t, el, eo, d("alt_m") ?: 0.0, s("alt_m").isNotEmpty(), d("speed_mps") ?: 0.0, s("speed_mps").isNotEmpty(),
                            d("track_deg") ?: 0.0, s("track_deg").isNotEmpty(), (d("sigma_s_m") ?: 500.0).coerceAtLeast(50.0), 100.0, 0, 0, GnssQuality.DEGRADED, virtual = true)
                }
                out.add(ReplayRow(t, phase, gnss, gyro, s("tracking") != "ESTIMATE", est))
            }
        }
        return out
    }
}
