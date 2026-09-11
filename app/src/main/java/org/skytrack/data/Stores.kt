// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Stores
// Version 4.2
// Purpose : SharedPreferences-backed persistence for the flight plan,
//           the last position estimate (instant restore on relaunch),
//           the last ground fix (origin auto-detection) and user settings.
//           JSON via org.json keeps the app free of extra dependencies.
// =============================================================
package org.skytrack.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** User-entered flight plan. Times are epoch milliseconds UTC. */
data class FlightPlan(
    val originIata: String,
    val destinationIata: String,
    val flightNumber: String = "",
    val scheduledDepartureMs: Long? = null,
    val takeoffMs: Long? = null,
    val estimateOnly: Boolean = false,    // true: ignore sensors, show the time-based estimate only
    val takeoffMeasured: Boolean = false  // takeoffMs came from the sensors (overrides a manual entry)
) {
    fun toJson(): String = JSONObject().apply {
        put("o", originIata); put("d", destinationIata); put("fn", flightNumber)
        put("sd", scheduledDepartureMs ?: 0L); put("to", takeoffMs ?: 0L); put("eo", estimateOnly); put("tm", takeoffMeasured)
    }.toString()

    companion object {
        fun fromJson(s: String?): FlightPlan? = try {
            if (s.isNullOrEmpty()) null else JSONObject(s).let {
                FlightPlan(
                    it.getString("o"), it.getString("d"), it.optString("fn", ""),
                    it.optLong("sd", 0L).takeIf { v -> v > 0 }, it.optLong("to", 0L).takeIf { v -> v > 0 },
                    it.optBoolean("eo", false), it.optBoolean("tm", false)
                )
            }
        } catch (e: Exception) { null }
    }
}

/** Minimal snapshot of estimator state for restore. */
data class SavedEstimate(val timeMs: Long, val alongM: Double, val speedMps: Double,
                         val trackDeg: Double, val altM: Double, val phase: String) {
    fun toJson(): String = JSONObject().apply {
        put("t", timeMs); put("s", alongM); put("v", speedMps)
        put("psi", trackDeg); put("h", altM); put("ph", phase)
    }.toString()

    companion object {
        fun fromJson(s: String?): SavedEstimate? = try {
            if (s.isNullOrEmpty()) null else JSONObject(s).let {
                SavedEstimate(it.getLong("t"), it.getDouble("s"), it.getDouble("v"),
                    it.getDouble("psi"), it.getDouble("h"), it.optString("ph", "GROUND"))
            }
        } catch (e: Exception) { null }
    }
}

data class GroundFix(val lat: Double, val lon: Double, val timeMs: Long)

enum class DistanceUnit { KM, NM, MI }
enum class AltitudeUnit { M, FT }
enum class SpeedUnit { KMH, KT, MPH }
enum class ThemeMode { NIGHT, DAY, AUTO }

data class Settings(
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.M,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val use24h: Boolean = true,
    val theme: ThemeMode = ThemeMode.NIGHT,
    val autoFollow: Boolean = true,
    val trackUp: Boolean = false,
    val rotateGestures: Boolean = false,
    val logFlights: Boolean = true,     // write CSV flight logs for offline calibration
    val aerial: Boolean = false,        // show the downloaded Blue Marble imagery when available
    val useNetwork: Boolean = true,     // when a network is available: ADS-B position, update check
    val language: String = "system"     // "system", "he", "en"
)

class Stores(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("skytrack", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    private val _plan = MutableStateFlow(FlightPlan.fromJson(prefs.getString(KEY_PLAN, null)))
    val plan: StateFlow<FlightPlan?> = _plan

    fun savePlan(p: FlightPlan?) {
        prefs.edit().putString(KEY_PLAN, p?.toJson()).apply()
        _plan.value = p
    }

    /** Measured track (decimated points) as "lat,lon" lines, so the green line survives a process restart. */
    fun saveTrack(points: List<org.skytrack.route.GeoPoint>, flownM: Double) {
        try {
            val f = java.io.File(context.filesDir, TRACK_FILE)
            f.bufferedWriter().use { w ->
                w.write("# flown_m=${flownM.toLong()}"); w.newLine()
                for (p in points) { w.write(String.format(java.util.Locale.US, "%.5f,%.5f", p.lat, p.lon)); w.newLine() }
            }
        } catch (e: Exception) { }
    }

    /** Returns (points, flownM) or null. */
    fun loadTrack(): Pair<List<org.skytrack.route.GeoPoint>, Double>? = try {
        val f = java.io.File(context.filesDir, TRACK_FILE)
        if (!f.exists()) null else {
            var flown = 0.0
            val pts = ArrayList<org.skytrack.route.GeoPoint>()
            f.forEachLine { ln ->
                if (ln.startsWith("# flown_m=")) flown = ln.substringAfter('=').toDoubleOrNull() ?: 0.0
                else { val a = ln.split(','); if (a.size == 2) pts.add(org.skytrack.route.GeoPoint(a[0].toDouble(), a[1].toDouble())) }
            }
            Pair(pts, flown)
        }
    } catch (e: Exception) { null }

    fun clearTrack() { java.io.File(context.filesDir, TRACK_FILE).delete() }

    fun saveEstimate(e: SavedEstimate) = prefs.edit().putString(KEY_EST, e.toJson()).apply()
    fun loadEstimate(): SavedEstimate? = SavedEstimate.fromJson(prefs.getString(KEY_EST, null))
    fun clearEstimate() { prefs.edit().remove(KEY_EST).apply(); clearTrack() }

    fun saveGroundFix(f: GroundFix) = prefs.edit()
        .putLong(KEY_GF_LAT, f.lat.toBits()).putLong(KEY_GF_LON, f.lon.toBits()).putLong(KEY_GF_T, f.timeMs).apply()

    fun loadGroundFix(): GroundFix? {
        if (!prefs.contains(KEY_GF_T)) return null
        return GroundFix(
            Double.fromBits(prefs.getLong(KEY_GF_LAT, 0L)),
            Double.fromBits(prefs.getLong(KEY_GF_LON, 0L)),
            prefs.getLong(KEY_GF_T, 0L)
        )
    }

    /** Last map zoom, restored on the next launch (not part of Settings to avoid recomposition on every gesture). */
    fun saveLastZoom(z: Double) = prefs.edit().putFloat(KEY_ZOOM, z.toFloat()).apply()
    fun loadLastZoom(): Double? = if (prefs.contains(KEY_ZOOM)) prefs.getFloat(KEY_ZOOM, 5f).toDouble() else null
    fun savePanelExpanded(e: Boolean) = prefs.edit().putBoolean(KEY_PANEL, e).apply()
    fun loadPanelExpanded(): Boolean = prefs.getBoolean(KEY_PANEL, true)

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("du", s.distanceUnit.name).putString("au", s.altitudeUnit.name).putString("su", s.speedUnit.name)
            .putBoolean("h24", s.use24h).putString("theme", s.theme.name)
            .putBoolean("follow", s.autoFollow).putBoolean("trackup", s.trackUp).putBoolean("rotate", s.rotateGestures)
            .putBoolean("log", s.logFlights).putBoolean("aerial", s.aerial).putBoolean("net", s.useNetwork).putString("lang", s.language)
            .apply()
        _settings.value = s
    }

    private fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            distanceUnit = enumOr(prefs.getString("du", null), d.distanceUnit),
            altitudeUnit = enumOr(prefs.getString("au", null), d.altitudeUnit),
            speedUnit = enumOr(prefs.getString("su", null), d.speedUnit),
            use24h = prefs.getBoolean("h24", d.use24h),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            autoFollow = prefs.getBoolean("follow", d.autoFollow),
            trackUp = prefs.getBoolean("trackup", d.trackUp),
            rotateGestures = prefs.getBoolean("rotate", d.rotateGestures),
            logFlights = prefs.getBoolean("log", d.logFlights),
            aerial = prefs.getBoolean("aerial", d.aerial),
            useNetwork = prefs.getBoolean("net", d.useNetwork),
            language = prefs.getString("lang", d.language) ?: d.language
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        private const val KEY_PLAN = "plan"
        private const val KEY_EST = "estimate"
        private const val KEY_GF_LAT = "gf_lat"
        private const val KEY_GF_LON = "gf_lon"
        private const val KEY_GF_T = "gf_t"
        private const val TRACK_FILE = "track.txt"
        private const val KEY_ZOOM = "last_zoom"
        private const val KEY_PANEL = "panel_expanded"
    }
}
