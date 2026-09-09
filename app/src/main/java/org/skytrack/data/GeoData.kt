// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - GeoData
// Version 1.1
// Purpose : In-memory access to the bundled Natural Earth reference data
//           for two features:
//             - nearby populated places (for the manual visual fix)
//             - country polygons (which country the aircraft is over)
//           Parsed once, lazily, off the main thread (~1 s on a mid-range
//           phone for 1.5 MB of polygons; estimate).
// =============================================================
package org.skytrack.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy

class GeoData(private val context: Context) {

    data class Place(val name: String, val nameHe: String?, val lat: Double, val lon: Double, val rank: Int) {
        val point: GeoPoint get() = GeoPoint(lat, lon)
        fun label(hebrew: Boolean): String = if (hebrew && nameHe != null) nameHe else name
    }

    /** One outer ring: interleaved lon/lat pairs plus a bounding box for fast rejection. */
    class Ring(val coords: DoubleArray, val minLon: Double, val maxLon: Double, val minLat: Double, val maxLat: Double) {
        /** Ray-casting point-in-polygon on the outer ring (holes ignored: no country has an enclave that matters here). */
        fun contains(lon: Double, lat: Double): Boolean {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false
            var inside = false
            val n = coords.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = coords[2 * i]; val yi = coords[2 * i + 1]
                val xj = coords[2 * j]; val yj = coords[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }

    class Country(val name: String, val nameHe: String?, private val rings: List<Ring>) {
        fun label(hebrew: Boolean): String = if (hebrew && nameHe != null) nameHe else name
        fun contains(p: GeoPoint): Boolean = rings.any { it.contains(p.lon, p.lat) }
    }

    val places: List<Place> by lazy { loadPlaces() }
    val countries: List<Country> by lazy { loadCountries() }

    /** Force parsing now (call from a background thread at engine start). */
    fun warmUp() { places.size; countries.size }

    /** Places within radius, nearest first. */
    fun nearbyPlaces(p: GeoPoint, radiusM: Double, limit: Int): List<Pair<Place, Double>> =
        places.asSequence()
            .map { it to Geodesy.distance(p, it.point) }
            .filter { it.second <= radiusM }
            .sortedWith(compareBy({ it.first.rank }, { it.second }))
            .take(limit)
            .toList()

    fun countryAt(p: GeoPoint): Country? = countries.firstOrNull { it.contains(p) }

    // -------------------------------------------------------------

    private fun loadPlaces(): List<Place> {
        val root = JSONObject(readAsset("ne_places.geojson"))
        val feats = root.getJSONArray("features")
        val out = ArrayList<Place>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val pr = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            out.add(Place(pr.optString("name", ""), pr.optString("name_he").takeIf { it.isNotEmpty() },
                c.getDouble(1), c.getDouble(0), pr.optInt("scalerank", 9)))
        }
        return out
    }

    private fun loadCountries(): List<Country> {
        val root = JSONObject(readAsset("ne_countries.geojson"))
        val feats = root.getJSONArray("features")
        val out = ArrayList<Country>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val pr = f.getJSONObject("properties")
            val g = f.getJSONObject("geometry")
            val rings = ArrayList<Ring>()
            when (g.getString("type")) {
                "Polygon" -> rings.add(ring(g.getJSONArray("coordinates").getJSONArray(0)))
                "MultiPolygon" -> {
                    val polys = g.getJSONArray("coordinates")
                    for (k in 0 until polys.length()) rings.add(ring(polys.getJSONArray(k).getJSONArray(0)))
                }
            }
            out.add(Country(pr.optString("name", ""), pr.optString("name_he").takeIf { it.isNotEmpty() }, rings))
        }
        return out
    }

    private fun ring(arr: JSONArray): Ring {
        val n = arr.length()
        val coords = DoubleArray(n * 2)
        var minLon = 180.0; var maxLon = -180.0; var minLat = 90.0; var maxLat = -90.0
        for (i in 0 until n) {
            val pt = arr.getJSONArray(i)
            val lon = pt.getDouble(0); val lat = pt.getDouble(1)
            coords[2 * i] = lon; coords[2 * i + 1] = lat
            if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
            if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
        }
        return Ring(coords, minLon, maxLon, minLat, maxLat)
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }
}
