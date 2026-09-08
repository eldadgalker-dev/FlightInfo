// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - AirportRepository
// Version 1.3
// Purpose : Load the bundled airports.csv (OurAirports subset with
//           IANA timezones) into memory and provide search and
//           nearest-airport queries. ~3,200 rows; in-memory is adequate.
// =============================================================
package org.skytrack.data

import android.content.Context
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy
import java.io.BufferedReader

data class Airport(
    val iata: String,
    val icao: String,
    val name: String,
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val elevM: Int,
    val tz: String
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
    val label: String get() = "$iata - $city ($name)"
}

class AirportRepository(context: Context) {

    val airports: List<Airport> by lazy { load(context) }

    private val byIata: Map<String, Airport> by lazy { airports.associateBy { it.iata } }
    private val byIcao: Map<String, Airport> by lazy { airports.associateBy { it.icao } }

    fun byCode(code: String): Airport? {
        val c = code.trim().uppercase()
        return byIata[c] ?: byIcao[c]
    }

    /** Prefix / substring search over code, city and name. Code matches rank first. */
    fun search(query: String, limit: Int = 12): List<Airport> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        val codeHits = airports.filter { it.iata.startsWith(q) || it.icao.startsWith(q) }
        val cityHits = airports.filter { it.city.uppercase().startsWith(q) } - codeHits
        val nameHits = airports.filter { it.name.uppercase().contains(q) || it.city.uppercase().contains(q) } - codeHits - cityHits
        return (codeHits + cityHits + nameHits).take(limit)
    }

    /** Nearest airport to p within radius, or null. */
    fun nearest(p: GeoPoint, radiusM: Double): Airport? {
        var best: Airport? = null
        var bestD = radiusM
        for (a in airports) {
            val d = Geodesy.distance(p, a.point)
            if (d < bestD) { bestD = d; best = a }
        }
        return best
    }

    private fun load(context: Context): List<Airport> {
        val out = ArrayList<Airport>(3500)
        context.assets.open("airports.csv").bufferedReader().use { r: BufferedReader ->
            r.readLine() // header
            r.forEachLine { line ->
                val f = parseCsvLine(line)
                if (f.size >= 9) {
                    out.add(
                        Airport(
                            iata = f[0], icao = f[1], name = f[2], city = f[3], country = f[4],
                            lat = f[5].toDoubleOrNull() ?: 0.0,
                            lon = f[6].toDoubleOrNull() ?: 0.0,
                            elevM = f[7].toIntOrNull() ?: 0,
                            tz = f[8].ifEmpty { "UTC" }
                        )
                    )
                }
            }
        }
        return out
    }

    /** Minimal RFC-4180 parser: handles quoted fields with embedded commas and doubled quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(9)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                c == '\r' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
