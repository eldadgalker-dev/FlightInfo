// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - Format
// Version 1.1
// Purpose : Convert SI values to display units at the point of emission.
//           Numbers are always rendered LTR with Latin digits.
// =============================================================
package org.skytrack.ui

import org.skytrack.data.AltitudeUnit
import org.skytrack.data.DistanceUnit
import org.skytrack.data.SpeedUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Format {

    private const val M_TO_NM = 1.0 / 1852.0
    private const val M_TO_MI = 1.0 / 1609.344
    private const val M_TO_FT = 3.28084
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_KT = 1.943844
    private const val MPS_TO_MPH = 2.236936

    private val f24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val f12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun distance(m: Double, u: DistanceUnit): String = when (u) {
        DistanceUnit.KM -> "${group((m / 1000.0).roundToInt())} km"
        DistanceUnit.NM -> "${group((m * M_TO_NM).roundToInt())} nm"
        DistanceUnit.MI -> "${group((m * M_TO_MI).roundToInt())} mi"
    }

    fun altitude(m: Double, u: AltitudeUnit): String = when (u) {
        AltitudeUnit.M -> "${group(m.roundToInt())} m"
        AltitudeUnit.FT -> "${group((m * M_TO_FT).roundToInt())} ft"
    }

    fun speed(mps: Double, u: SpeedUnit): String = when (u) {
        SpeedUnit.KMH -> "${group((mps * MPS_TO_KMH).roundToInt())} km/h"
        SpeedUnit.KT -> "${group((mps * MPS_TO_KT).roundToInt())} kt"
        SpeedUnit.MPH -> "${group((mps * MPS_TO_MPH).roundToInt())} mph"
    }

    fun verticalRate(mps: Double, u: AltitudeUnit): String = when (u) {
        AltitudeUnit.M -> String.format(Locale.US, "%+.1f m/s", mps)
        AltitudeUnit.FT -> String.format(Locale.US, "%+d ft/min", (mps * M_TO_FT * 60).roundToInt())
    }

    fun heading(deg: Double): String = String.format(Locale.US, "%03d\u00B0", ((deg.roundToInt() % 360) + 360) % 360)

    fun percent(p: Double): String = "${p.roundToInt()}%"

    /** h:mm for durations; "--:--" for null. */
    fun duration(s: Long?): String {
        if (s == null || s < 0) return "--:--"
        val h = s / 3600
        val m = (s % 3600) / 60
        return String.format(Locale.US, "%d:%02d", h, m)
    }

    fun time(t: ZonedDateTime?, use24h: Boolean): String =
        if (t == null) "--:--" else (if (use24h) f24 else f12).format(t)

    /** Offset label such as "UTC+03:00" for a zoned time. */
    fun offset(t: ZonedDateTime): String {
        val id = t.offset.id
        return if (id == "Z") "UTC" else "UTC$id"
    }

    fun ageSeconds(ms: Long): String = if (ms < 0) "--" else "${ms / 1000}s"

    private fun group(n: Int): String = String.format(Locale.US, "%,d", n)
}
