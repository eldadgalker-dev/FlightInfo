// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Stores
// Version 2.3
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
    val estimateOnly: Boolean = false     // true: ignore sensors, show the time-based estimate only
) {
    fun toJson(): String = JSONObject().apply {
        put("o", originIata); put("d", destinationIata); put("fn", flightNumber)
        put("sd", scheduledDepartureMs ?: 0L); put("to", takeoffMs ?: 0L); put("eo", estimateOnly)
    }.toString()

    companion object {
        fun fromJson(s: String?): FlightPlan? = try {
            if (s.isNullOrEmpty()) null else JSONObject(s).let {
                FlightPlan(
                    it.getString("o"), it.getString("d"), it.optString("fn", ""),
                    it.optLong("sd", 0L).takeIf { v -> v > 0 }, it.optLong("to", 0L).takeIf { v -> v > 0 },
                    it.optBoolean("eo", false)
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
    val aerial: Boolean = false         // show the downloaded Blue Marble imagery when available
)

class Stores(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("skytrack", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    private val _plan = MutableStateFlow(FlightPlan.fromJson(prefs.getString(KEY_PLAN, null)))
    val plan: StateFlow<FlightPlan?> = _plan

    fun savePlan(p: FlightPlan?) {
        prefs.edit().putString(KEY_PLAN, p?.toJson()).apply()
        _plan.value = p
    }

    fun saveEstimate(e: SavedEstimate) = prefs.edit().putString(KEY_EST, e.toJson()).apply()
    fun loadEstimate(): SavedEstimate? = SavedEstimate.fromJson(prefs.getString(KEY_EST, null))
    fun clearEstimate() = prefs.edit().remove(KEY_EST).apply()

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

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("du", s.distanceUnit.name).putString("au", s.altitudeUnit.name).putString("su", s.speedUnit.name)
            .putBoolean("h24", s.use24h).putString("theme", s.theme.name)
            .putBoolean("follow", s.autoFollow).putBoolean("trackup", s.trackUp).putBoolean("rotate", s.rotateGestures)
            .putBoolean("log", s.logFlights).putBoolean("aerial", s.aerial)
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
            aerial = prefs.getBoolean("aerial", d.aerial)
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
    }
}
