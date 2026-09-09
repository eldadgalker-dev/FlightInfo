// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapController
// Version 2.1
// Purpose : Non-Compose controller around a MapLibreMap: installs the
//           style, pushes route / aircraft / uncertainty geometry, animates
//           the aircraft marker between engine ticks, and implements
//           follow / fit / zoom camera behaviour.
// =============================================================
package org.skytrack.map

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.skytrack.Parameters
import org.skytrack.fusion.Confidence
import org.skytrack.fusion.FlightMetrics
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy
import org.skytrack.route.Route
import java.util.concurrent.Executors
import kotlin.math.max

class MapController(private val context: Context, private val map: MapLibreMap) {

    private var style: Style? = null
    private var palette: Palette = MapStyle.NIGHT
    private var ready = false

    // Marker animation state
    private var shownLat = 0.0
    private var shownLon = 0.0
    private var shownBearing = 0.0
    private var hasShown = false
    private var animator: ValueAnimator? = null

    // Follow behaviour
    var followEnabled = true
    var trackUp = false
    private var lastGestureMs = 0L
    private var lastRouteHash = 0
    private var pendingMetrics: FlightMetrics? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        map.setMinZoomPreference(Parameters.MAP_MIN_ZOOM)
        map.setMaxZoomPreference(Parameters.MAP_MAX_ZOOM)
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                lastGestureMs = System.currentTimeMillis()
            }
        }
    }

    fun setRotateGestures(enabled: Boolean) { map.uiSettings.isRotateGesturesEnabled = enabled }

    /** Load (or reload) the style for a palette. Base GeoJSON is read off the main thread once. */
    fun setPalette(p: Palette) {
        palette = p
        ready = false
        lastRouteHash = 0
        map.setStyle(Style.Builder().fromJson(MapStyle.styleJson(p))) { st ->
            style = st
            ioExecutor.execute {
                val base = baseData(context)
                mainHandler.post {
                    if (style !== st) return@post
                    MapStyle.install(st, p, base, isHebrewLocale())
                    ready = true
                    pendingMetrics?.let { update(it) }
                }
            }
        }
    }

    /** Push a new engine estimate to the map. Safe to call before the style is ready. */
    fun update(m: FlightMetrics) {
        pendingMetrics = m
        val st = style ?: return
        if (!ready) return
        val route = m.route

        // Planned route and airports change only with the plan; the governing route
        // changes on every re-plan. The hash covers both.
        val routeHash = (m.origin.iata + m.destination.iata + m.estimate.replanCount).hashCode()
        if (routeHash != lastRouteHash) {
            lastRouteHash = routeHash
            src(st, MapStyle.SRC_ROUTE_PLANNED)?.setGeoJson(lineFeature(route.points))
            src(st, MapStyle.SRC_ROUTE_ORIGINAL)?.setGeoJson(
                if (m.estimate.replanCount > 0) lineFeature(m.plannedRoute.points) else emptyCollection())
            src(st, MapStyle.SRC_AIRPORTS)?.setGeoJson(FeatureCollection.fromFeatures(listOf(
                pointFeature(m.origin.lat, m.origin.lon).apply { addStringProperty("code", m.origin.iata) },
                pointFeature(m.destination.lat, m.destination.lon).apply { addStringProperty("code", m.destination.iata) }
            )))
        }
        src(st, MapStyle.SRC_ROUTE_FLOWN)?.setGeoJson(lineFeature(route.polylineUpTo(m.estimate.alongTrackM)))
        src(st, MapStyle.SRC_TRACK_ACTUAL)?.setGeoJson(
            if (m.estimate.replanCount > 0 && m.actualTrack.size >= 2) lineFeature(m.actualTrack) else emptyCollection())
        src(st, MapStyle.SRC_UNCERTAINTY)?.setGeoJson(uncertaintyFeature(m, route))

        val e = m.estimate
        val icon = if (e.positionConfidence == Confidence.MEASURED || e.positionConfidence == Confidence.FUSED)
            MapStyle.IMG_AIRCRAFT_SOLID else MapStyle.IMG_AIRCRAFT_OUTLINE
        animateAircraft(e.lat, e.lon, e.trackDeg, icon, st)
        maybeFollow(e.lat, e.lon, e.trackDeg)
    }

    fun zoomIn() = map.animateCamera(CameraUpdateFactory.zoomIn())
    fun zoomOut() = map.animateCamera(CameraUpdateFactory.zoomOut())

    fun fitRoute() {
        val m = pendingMetrics ?: return
        lastGestureMs = System.currentTimeMillis()
        val b = LatLngBounds.Builder()
            .include(LatLng(m.origin.lat, m.origin.lon))
            .include(LatLng(m.destination.lat, m.destination.lon))
            .include(LatLng(m.estimate.lat, m.estimate.lon))
        // Include intermediate route vertices so a curved great circle is fully in view.
        for (p in m.route.points.filterIndexed { i, _ -> i % 5 == 0 }) b.include(LatLng(p.lat, p.lon))
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), Parameters.FIT_PADDING_PX), 800)
        } catch (e: Exception) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(m.estimate.lat, m.estimate.lon), 3.0))
        }
    }

    fun recenter() {
        val m = pendingMetrics ?: return
        lastGestureMs = 0L
        val zoom = max(map.cameraPosition.zoom, Parameters.MAP_DEFAULT_ZOOM)
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(m.estimate.lat, m.estimate.lon)).zoom(zoom)
                .bearing(if (trackUp) m.estimate.trackDeg else 0.0).build()), 600)
    }

    private fun maybeFollow(lat: Double, lon: Double, bearing: Double) {
        if (!followEnabled) return
        if (System.currentTimeMillis() - lastGestureMs < Parameters.FOLLOW_RESUME_S * 1000L) return
        val cp = map.cameraPosition
        map.easeCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(lat, lon)).zoom(cp.zoom)
                .bearing(if (trackUp) bearing else 0.0).build()), Parameters.UI_INTERPOLATION_MS)
    }

    private fun animateAircraft(lat: Double, lon: Double, bearing: Double, icon: String, st: Style) {
        val s = src(st, MapStyle.SRC_AIRCRAFT) ?: return
        animator?.cancel()
        if (!hasShown) {
            hasShown = true
            shownLat = lat; shownLon = lon; shownBearing = bearing
            s.setGeoJson(aircraftFeature(lat, lon, bearing, icon))
            return
        }
        val fromLat = shownLat; val fromLon = shownLon; val fromBrg = shownBearing
        val dLon = Geodesy.wrapLon(lon - fromLon)     // shortest path across the antimeridian
        val dBrg = Geodesy.bearingDiff(bearing, fromBrg)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Parameters.UI_INTERPOLATION_MS.toLong()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                shownLat = fromLat + (lat - fromLat) * f
                shownLon = Geodesy.wrapLon(fromLon + dLon * f)
                shownBearing = Geodesy.wrapBearing(fromBrg + dBrg * f)
                s.setGeoJson(aircraftFeature(shownLat, shownLon, shownBearing, icon))
            }
            start()
        }
    }

    /** Along-route band: +/- k*sigma_s along the governing route, fixed half-width across. */
    private fun uncertaintyFeature(m: FlightMetrics, route: Route): Feature {
        val e = m.estimate
        val k = Parameters.UNCERTAINTY_SIGMAS
        val s0 = (e.alongTrackM - k * e.sigmaAlongM).coerceIn(0.0, route.lengthM)
        val s1 = (e.alongTrackM + k * e.sigmaAlongM).coerceIn(0.0, route.lengthM)
        val w = Parameters.UNCERTAINTY_BAND_HALF_W_M
        val ring = ArrayList<Point>()
        val n = 12
        for (i in 0..n) ring.add(pt(route.pointAtWithOffset(s0 + (s1 - s0) * i / n, w)))
        for (i in n downTo 0) ring.add(pt(route.pointAtWithOffset(s0 + (s1 - s0) * i / n, -w)))
        ring.add(ring[0])
        return Feature.fromGeometry(Polygon.fromLngLats(listOf<List<Point>>(ring)))
    }

    private fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

    private fun aircraftFeature(lat: Double, lon: Double, bearing: Double, icon: String): Feature =
        pointFeature(lat, lon).apply { addNumberProperty("bearing", bearing); addStringProperty("icon", icon) }

    private fun pointFeature(lat: Double, lon: Double): Feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))

    private fun lineFeature(points: List<GeoPoint>): Feature =
        Feature.fromGeometry(LineString.fromLngLats(points.map { pt(it) }))

    private fun pt(p: GeoPoint): Point = Point.fromLngLat(p.lon, p.lat)

    private fun src(st: Style, id: String): GeoJsonSource? = st.getSourceAs(id)

    private fun isHebrewLocale(): Boolean {
        val lang = context.resources.configuration.locales[0].language
        return lang == "he" || lang == "iw"
    }

    companion object {
        private val ioExecutor = Executors.newSingleThreadExecutor()
        @Volatile private var cachedBase: Map<String, String>? = null

        /** Bundled Natural Earth GeoJSON, read once per process. */
        fun baseData(context: Context): Map<String, String> {
            cachedBase?.let { return it }
            val m = mapOf(
                MapStyle.SRC_LAND to MapStyle.readAsset(context, "ne_land.geojson"),
                MapStyle.SRC_COUNTRIES to MapStyle.readAsset(context, "ne_countries.geojson"),
                MapStyle.SRC_COUNTRY_LABELS to MapStyle.readAsset(context, "ne_country_labels.geojson"),
                MapStyle.SRC_LAKES to MapStyle.readAsset(context, "ne_lakes.geojson"),
                MapStyle.SRC_BORDERS to MapStyle.readAsset(context, "ne_borders.geojson"),
                MapStyle.SRC_PLACES to MapStyle.readAsset(context, "ne_places.geojson")
            )
            cachedBase = m
            return m
        }
    }
}
