// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - FlightLogRepair
// Version 1.1
// Purpose : "Check / repair log" in the log manager. check() reports what is
//           wrong with a log (time gaps, stale repeated fixes, implausible
//           jumps, duplicate seconds, phase sequence). repair() writes a new
//           continuous 1 Hz file next to the original (never overwriting it):
//           distinct fresh fixes only, every hole interpolated on the great
//           circle between the real fixes before and after it with a speed
//           profile matching both ends, est columns = measured position,
//           remaining / flown / sigma / ete recomputed, phases relabelled.
//           Same rules as tools/clean_log.py.
// =============================================================
package org.skytrack.service

import org.skytrack.Parameters
import org.skytrack.data.Airport
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LogCheck(
    val rows: Int, val fixes: Int, val durationS: Long,
    val gaps: Int, val longestGapS: Long, val staleRows: Int, val jumps: Int, val duplicateSeconds: Int,
    val phases: List<String>, val alreadyClean: Boolean
) {
    val needsRepair: Boolean get() = !alreadyClean && (gaps > 0 || staleRows > 0 || jumps > 0 || duplicateSeconds > 0)
}

object FlightLogRepair {

    // ---------------- Parameters (same as clean_log.py) ----------------
    private const val STALE_FIX_MS = 30_000L
    private const val GAP_S = 10.0
    private const val MAX_V = 350.0
    private const val GROUND_V = 40.0
    private const val FIELD_MARGIN_M = 300.0
    private const val DRIFT = 0.06

    private class Fix(val t: Long, val lat: Double, val lon: Double, val alt: Double?, val v: Double?, val hacc: Double?, val brg: Double?,
                      val sats: String, val vis: String, val q: String)
    private class Row(val t: Long, val lat: Double, val lon: Double, val alt: Double, val v: Double, val brg: Double, val sigma: Double,
                      val interpolated: Boolean, val fix: Fix?, var flown: Double, var remaining: Double, var phase: String = "CRUISE")

    private fun read(f: File): Pair<List<String>, List<Map<String, String>>> {
        var header: List<String>? = null
        val rows = ArrayList<Map<String, String>>()
        f.bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.isEmpty() || ln.startsWith("#")) continue
                if (ln.startsWith("time_utc")) { header = ln.split(','); continue }
                val h = header ?: continue
                val c = ln.split(',')
                if (c.size < h.size) continue
                rows.add(h.indices.associate { h[it] to c[it] })
            }
        }
        return Pair(header ?: emptyList(), rows)
    }

    private fun fixes(rows: List<Map<String, String>>): List<Fix> {
        val out = ArrayList<Fix>(); var last = ""
        for (r in rows) {
            val gt = r["gnss_time_ms"] ?: ""; val lat = r["gnss_lat"]?.toDoubleOrNull(); val lon = r["gnss_lon"]?.toDoubleOrNull()
            val t = r["epoch_ms"]?.toLongOrNull() ?: continue
            if (gt.isEmpty() || lat == null || lon == null) continue
            if (t - gt.toLong() > STALE_FIX_MS || gt == last) continue
            last = gt
            out.add(Fix(gt.toLong(), lat, lon, r["gnss_alt_m"]?.toDoubleOrNull(), r["gnss_speed_mps"]?.toDoubleOrNull(), r["gnss_hacc_m"]?.toDoubleOrNull(),
                r["gnss_bearing_deg"]?.toDoubleOrNull(), r["gnss_sats_used"] ?: "", r["gnss_sats_visible"] ?: "", r["gnss_quality"] ?: ""))
        }
        return out
    }

    /** Analysis only; nothing is written. */
    fun check(f: File): LogCheck? {
        val (_, rows) = read(f)
        if (rows.isEmpty()) return null
        val ts = rows.mapNotNull { it["epoch_ms"]?.toLongOrNull() }
        var gaps = 0; var longest = 0L; var dup = 0
        for (i in 1 until ts.size) { val d = ts[i] - ts[i - 1]; if (d > GAP_S * 1000) { gaps++; longest = max(longest, d) }; if (d < 500) dup++ }
        val stale = rows.count { r -> val gt = r["gnss_time_ms"]?.toLongOrNull(); val t = r["epoch_ms"]?.toLongOrNull(); gt != null && t != null && t - gt > STALE_FIX_MS }
        val fx = fixes(rows)
        var jumps = 0
        for (i in 1 until fx.size) {
            val dt = (fx[i].t - fx[i - 1].t) / 1000.0
            if (dt > 0 && Geodesy.distance(GeoPoint(fx[i - 1].lat, fx[i - 1].lon), GeoPoint(fx[i].lat, fx[i].lon)) / dt > MAX_V) jumps++
        }
        val phases = ArrayList<String>(); for (r in rows) { val p = r["phase"] ?: ""; if (phases.isEmpty() || phases.last() != p) phases.add(p) }
        val cleaned = f.bufferedReader().useLines { l -> l.take(12).any { it.startsWith("#") && (it.contains("cleaned", true) || it.contains("clean_log") || it.contains("FlightLogRepair")) } }
        return LogCheck(rows.size, fx.size, if (ts.isEmpty()) 0 else (ts.last() - ts.first()) / 1000, gaps, longest / 1000, stale, jumps, dup, phases, cleaned)
    }

    /**
     * The flight only: from the last stationary period at the origin before the first airborne fix to the
     * first stationary period at the destination after the last airborne fix (+ up to 5 min of taxi).
     * Removes gate time hours before, and the phone left running at home after landing.
     */
    private fun selectFlight(fx: List<Fix>, origin: Airport, destination: Airport): List<Fix> {
        fun onGround(f: Fix, ap: Airport): Boolean = Geodesy.distance(GeoPoint(f.lat, f.lon), ap.point) < 20_000 && (f.v ?: 0.0) < GROUND_V &&
                (f.alt == null || f.alt < ap.elevM + FIELD_MARGIN_M)
        val air = fx.indices.filter { (fx[it].v ?: 0.0) > GROUND_V * 2 && (fx[it].alt ?: 0.0) > max(origin.elevM, destination.elevM) + 1000 }
        if (air.isEmpty()) return fx
        var start = 0
        for (i in air.first() - 1 downTo 0) {
            if (onGround(fx[i], origin)) {
                start = i
                while (start > 0 && onGround(fx[start - 1], origin) && fx[start].t - fx[start - 1].t < 20 * 60_000) start--
                break
            }
        }
        var end = fx.size - 1
        for (i in air.last() + 1 until fx.size) {
            if (onGround(fx[i], destination)) {
                end = i
                while (end + 1 < fx.size && onGround(fx[end + 1], destination) && fx[end + 1].t - fx[i].t < 5 * 60_000) end++
                break
            }
        }
        return fx.subList(start, end + 1)
    }

    /** Position fraction and speed factor across a hole (see clean_log.py hole_profile). */
    private fun profile(u: Double, span: Double, d: Double, v0: Double, v1: Double): Pair<Double, Double> {
        val vAvg = d / span
        val g0 = v0 < GROUND_V; val g1 = v1 < GROUND_V
        if (g0 && g1) return Pair(3 * u * u - 2 * u * u * u, 6 * u - 6 * u * u)
        if (g0 && v1 > 0) {
            val ta = 2 * (v1 * span - d) / v1
            if (ta > 0 && ta <= span) { val tu = ta / span
                return if (u < tu) Pair((v1 * span / d) * (u * u) / (2 * tu), (v1 / vAvg) * (u / tu)) else Pair((v1 * span / d) * (tu / 2 + (u - tu)), v1 / vAvg) }
        }
        if (g1 && v0 > 0) {
            val td = 2 * (v0 * span - d) / v0
            if (td > 0 && td <= span) { val tu = 1 - td / span
                return if (u < tu) Pair((v0 * span / d) * u, v0 / vAvg) else { val w = (u - tu) / (1 - tu); Pair((v0 * span / d) * (tu + (1 - tu) * (w - w * w / 2)), (v0 / vAvg) * (1 - w)) } }
        }
        return Pair(u, 1.0)
    }

    /**
     * Writes <name>_clean.csv next to the original and returns it. Origin/destination are needed for
     * remaining distance and phases; for a free recording pass the first/last fix as stand-ins.
     */
    fun repair(f: File, origin: Airport, destination: Airport, header: List<String>? = null): File? {
        val (hdr0, raw) = read(f)
        val hdr = header ?: hdr0
        var fx = fixes(raw)
        if (fx.size < 2) return null
        fx = selectFlight(fx, origin, destination)
        if (fx.size < 2) return null
        val dest = destination.point
        val rows = ArrayList<Row>()
        var fi = 0; var flown = 0.0; var prev = GeoPoint(fx[0].lat, fx[0].lon)
        val t0 = fx.first().t; val t1 = fx.last().t
        var t = t0
        while (t <= t1) {
            while (fi + 1 < fx.size && fx[fi + 1].t <= t) fi++
            val f0 = fx[fi]; val f1 = if (fi + 1 < fx.size) fx[fi + 1] else null
            val age = (t - f0.t) / 1000.0
            val row: Row
            if (f1 == null || f1.t == f0.t) {
                row = Row(t, f0.lat, f0.lon, f0.alt ?: 0.0, f0.v ?: 0.0, f0.brg ?: Geodesy.bearing(GeoPoint(f0.lat, f0.lon), dest), f0.hacc ?: 50.0, false, f0, 0.0, 0.0)
            } else {
                val span = (f1.t - f0.t) / 1000.0; val u = min(1.0, age / span)
                val a = GeoPoint(f0.lat, f0.lon); val b = GeoPoint(f1.lat, f1.lon)
                val d = Geodesy.distance(a, b); val vImp = d / span
                val long = span > GAP_S
                val (frac, dfrac) = if (long && span > 60) profile(u, span, d, f0.v ?: 0.0, f1.v ?: 0.0) else Pair(u, 1.0)
                val p = Geodesy.intermediate(a, b, frac)
                val a0 = f0.alt ?: (f1.alt ?: 0.0); val a1 = f1.alt ?: a0
                val alt = a0 + (a1 - a0) * frac
                row = if (long) Row(t, p.lat, p.lon, alt, vImp * dfrac, Geodesy.bearing(p, b), (f0.hacc ?: 50.0) + DRIFT * d * frac, true, if (age * 1000 < STALE_FIX_MS) f0 else null, 0.0, 0.0)
                      else Row(t, p.lat, p.lon, alt, f0.v ?: vImp, f0.brg ?: Geodesy.bearing(p, b), f0.hacc ?: 50.0, false, f0, 0.0, 0.0)
            }
            val pos = GeoPoint(row.lat, row.lon)
            val step = Geodesy.distance(prev, pos); if (step < 50_000) flown += step; prev = pos
            row.flown = flown; row.remaining = Geodesy.distance(pos, dest)
            rows.add(row); t += 1000
        }
        // Phases from altitude trend and speed.
        val top = rows.maxOf { it.alt }
        var phase = "GROUND"
        for ((i, r) in rows.withIndex()) {
            val j = max(0, i - 60); val vr = (rows[i].alt - rows[j].alt) / max(1, i - j)
            when (phase) {
                "GROUND" -> if (r.v > GROUND_V * 1.5 || r.alt > origin.elevM + FIELD_MARGIN_M) phase = "TAKEOFF"
                "TAKEOFF" -> phase = "CLIMB"
                "CLIMB" -> if (vr < 0.5 && r.alt > top * 0.75) phase = "CRUISE"
                "CRUISE" -> if (vr < -2.0 && r.remaining < 400_000) phase = "DESCENT"
                "DESCENT" -> if (r.v < GROUND_V && r.alt < destination.elevM + FIELD_MARGIN_M) phase = "LANDED"
            }
            r.phase = phase
        }
        val out = File(f.parentFile, f.nameWithoutExtension.removeSuffix("_clean") + "_clean.csv")
        val iso = DateTimeFormatter.ISO_INSTANT
        out.bufferedWriter().use { w ->
            w.write("# FlightInfo flight log (cleaned in-app by FlightLogRepair 1.0 on ${Instant.now().atZone(ZoneOffset.UTC).toLocalDate()})\n")
            w.write("# app_version=repaired\n")
            w.write("# flight=${f.nameWithoutExtension.split('_').getOrNull(4) ?: ""} origin=${origin.iata} destination=${destination.iata} started_utc=${iso.format(Instant.ofEpochMilli(rows.first().t))}\n")
            w.write("# source=${f.name} (${raw.size} rows, ${fx.size} fixes); resampled to 1 Hz (${rows.size} rows), ${rows.count { it.interpolated }} interpolated\n")
            w.write("# est_lat/est_lon = measured GNSS position when a fix is fresh, else interpolated on the great circle between the real fixes before and after the hole\n")
            w.write(hdr.joinToString(",")); w.write("\n")
            for (r in rows) {
                val c = HashMap<String, String>()
                c["time_utc"] = iso.format(Instant.ofEpochMilli(r.t)); c["epoch_ms"] = r.t.toString(); c["tracking"] = "LIVE"
                c["mode"] = if (r.interpolated) "ROUTE_CONSTRAINED" else "GNSS_TRACKING"; c["phase"] = r.phase
                c["est_lat"] = fmt(r.lat, 6); c["est_lon"] = fmt(r.lon, 6); c["along_m"] = fmt(r.flown, 0); c["total_flown_m"] = fmt(r.flown, 0)
                c["remaining_m"] = fmt(r.remaining, 0); c["sigma_s_m"] = fmt(r.sigma, 0); c["speed_mps"] = fmt(if (r.v < 1.5) 0.0 else r.v, 1)
                c["track_deg"] = fmt(r.brg, 1); c["alt_m"] = fmt(r.alt, 0); c["ete_s"] = ete(r).toString(); c["replans"] = "0"; c["deviation_evidence"] = "0"
                r.fix?.let { g ->
                    c["gnss_time_ms"] = g.t.toString(); c["gnss_lat"] = fmt(g.lat, 6); c["gnss_lon"] = fmt(g.lon, 6)
                    g.alt?.let { c["gnss_alt_m"] = fmt(it, 0) }; g.v?.let { c["gnss_speed_mps"] = fmt(it, 1) }; g.brg?.let { c["gnss_bearing_deg"] = fmt(it, 1) }
                    g.hacc?.let { c["gnss_hacc_m"] = fmt(it, 0) }; c["gnss_sats_used"] = g.sats; c["gnss_sats_visible"] = g.vis; c["gnss_quality"] = g.q
                }
                w.write(hdr.joinToString(",") { c[it] ?: "" }); w.write("\n")
            }
        }
        return out
    }

    private fun ete(r: Row): Int {
        val rem = r.remaining; val v = r.v
        if (r.phase == "LANDED" || rem < 1000) return 0
        if (r.phase == "DESCENT") return (rem / max(60.0, (max(v, 140.0) + Parameters.TOUCHDOWN_SPEED_MPS) / 2) + Parameters.APPROACH_ALLOWANCE_S).toInt()
        if (r.phase == "GROUND") return (rem / Parameters.SPEED_CRUISE_MPS + Parameters.DESCENT_ALLOWANCE_S).toInt()
        return (max(0.0, rem - Parameters.DESCENT_DISTANCE_M) / max(v, 100.0) + Parameters.DESCENT_ALLOWANCE_S).toInt()
    }

    private fun fmt(v: Double, dec: Int): String = String.format(Locale.US, "%.${dec}f", v)
}
