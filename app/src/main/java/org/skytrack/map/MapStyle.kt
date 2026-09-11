// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapStyle
// Version 4.0
// Purpose : Offline map style. The style JSON holds only the background
//           and the bundled glyph endpoint; all sources and layers are
//           added programmatically from the bundled Natural Earth GeoJSON
//           so no network or tile server is ever required.
// =============================================================
package org.skytrack.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** Colour palette for a theme. Hex strings are consumed by MapLibre. */
data class Palette(
    val background: String,
    val land: String,
    val lake: String,
    val border: String,
    val countryFills: List<String>,   // 7 tones, indexed by Natural Earth MAPCOLOR7 (1..7)
    val countryText: String,
    val placeDot: String,
    val placeText: String,
    val placeHalo: String,
    val routeOriginal: String,   // original plan after a re-plan (faint)
    val routePlanned: String,    // governing route, remaining part
    val routeFlown: String,      // governing route, flown part
    val trackActual: String,     // measured track (drawn once a deviation is proven)
    val uncertainty: String,
    val airport: String,
    val airportText: String,
    val aircraft: Int,
    val aircraftOutline: Int
)

object MapStyle {

    val NIGHT = Palette(
        background = "#0b1622", land = "#1f2a36", lake = "#0b1622", border = "#4a5c70",
        countryFills = listOf("#233241", "#2a3138", "#26343a", "#2c2f3d", "#243a3c", "#2f3239", "#28363f"),
        countryText = "#8fa3b8",
        placeDot = "#d8e0e8", placeText = "#e6ecf2", placeHalo = "#0b1622",
        routeOriginal = "#4a5a6c", routePlanned = "#7aa2c4", routeFlown = "#ffb454", trackActual = "#5ad1c6",
        uncertainty = "#ffb454",
        airport = "#ffffff", airportText = "#ffffff",
        aircraft = Color.rgb(255, 214, 102), aircraftOutline = Color.rgb(11, 22, 34)
    )

    val DAY = Palette(
        background = "#a9c4dc", land = "#e8e2d4", lake = "#a9c4dc", border = "#8a8a8a",
        countryFills = listOf("#f2e6c9", "#e4efd2", "#f5dfd4", "#e2e6f4", "#f0e2ea", "#e6f0ea", "#f4ecd8"),
        countryText = "#5a5a5a",
        placeDot = "#333333", placeText = "#222222", placeHalo = "#ffffff",
        routeOriginal = "#a9b4c0", routePlanned = "#2e5f8a", routeFlown = "#d9581e", trackActual = "#0f8f83",
        uncertainty = "#d9581e",
        airport = "#111111", airportText = "#111111",
        aircraft = Color.rgb(20, 20, 20), aircraftOutline = Color.WHITE
    )

    // Source and layer identifiers
    const val SRC_LAND = "ne-land"
    const val SRC_LAKES = "ne-lakes"
    const val SRC_BORDERS = "ne-borders"
    const val SRC_PLACES = "ne-places"
    const val SRC_COUNTRIES = "ne-countries"
    const val SRC_COUNTRY_LABELS = "ne-country-labels"
    const val SRC_ROUTE_ORIGINAL = "route-original"
    const val SRC_ROUTE_PLANNED = "route-planned"
    const val SRC_ROUTE_FLOWN = "route-flown"
    const val SRC_TRACK_ACTUAL = "track-actual"
    const val SRC_UNCERTAINTY = "uncertainty"
    const val SRC_AIRPORTS = "airports"
    const val SRC_AIRCRAFT = "aircraft"
    /** Aircraft icon ids by sensor level 0..3: red (time only), orange (inertial), yellow (weak fix), green (good fix). */
    val IMG_AIRCRAFT_LEVEL = arrayOf("aircraft-0", "aircraft-1", "aircraft-2", "aircraft-3")
    val AIRCRAFT_LEVEL_COLORS = intArrayOf(Color.rgb(230, 57, 70), Color.rgb(255, 140, 0), Color.rgb(255, 214, 0), Color.rgb(76, 201, 106))

    fun styleJson(p: Palette): String = """
        {"version":8,"name":"skytrack",
         "glyphs":"asset://glyphs/{fontstack}/{range}.pbf",
         "sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"${p.background}"}}]}
    """.trimIndent()

    /** Read a bundled GeoJSON asset as a string (call off the main thread). */
    fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    /**
     * Add all sources and layers. `base` holds the four Natural Earth GeoJSON
     * strings keyed by source id, read beforehand on a background thread.
     */
    const val SRC_AERIAL = "aerial"

    /**
     * @param aerialTileUrl  mbtiles:// URL of the Blue Marble pack, or null for the vector-only map.
     *                       With imagery the land / country fills are omitted; borders, water
     *                       outlines and all labels stay on top.
     */
    fun install(style: Style, p: Palette, base: Map<String, String>, hebrew: Boolean, aerialTileUrl: String? = null) {
        // Labels are English only (decision 3.0): consistent with airport codes and aviation usage.
        val nameExpr: Expression = Expression.get("name")

        // -- Static base layers --
        style.addSource(GeoJsonSource(SRC_LAND, base.getValue(SRC_LAND)))
        style.addSource(GeoJsonSource(SRC_COUNTRIES, base.getValue(SRC_COUNTRIES)))
        style.addSource(GeoJsonSource(SRC_LAKES, base.getValue(SRC_LAKES)))
        style.addSource(GeoJsonSource(SRC_BORDERS, base.getValue(SRC_BORDERS)))
        style.addSource(GeoJsonSource(SRC_PLACES, base.getValue(SRC_PLACES)))
        style.addSource(GeoJsonSource(SRC_COUNTRY_LABELS, base.getValue(SRC_COUNTRY_LABELS)))

        if (aerialTileUrl != null) {
            style.addSource(RasterSource(SRC_AERIAL, TileSet("2.2.0", aerialTileUrl).apply {
                minZoom = 0f; maxZoom = org.skytrack.Parameters.AERIAL_MAX_ZOOM.toFloat()
            }, 256))
            style.addLayer(RasterLayer("aerial", SRC_AERIAL).withProperties(
                PropertyFactory.rasterOpacity(1.0f),
                PropertyFactory.rasterBrightnessMax(if (p === NIGHT) 0.75f else 1.0f)))
            // Coastline hint over imagery so water and land stay readable at low zoom.
            style.addLayer(LineLayer("land-outline", SRC_LAND).withProperties(
                PropertyFactory.lineColor(p.placeHalo), PropertyFactory.lineWidth(0.4f), PropertyFactory.lineOpacity(0.5f)))
        } else {
            style.addLayer(FillLayer("land", SRC_LAND).withProperties(
                PropertyFactory.fillColor(p.land), PropertyFactory.fillAntialias(true)))
            // Country tint: seven tones chosen by Natural Earth so neighbours never share a colour.
            val colorStops = p.countryFills.mapIndexed { i, c -> Expression.stop(i + 1, Expression.color(android.graphics.Color.parseColor(c))) }.toTypedArray()
            style.addLayer(FillLayer("countries", SRC_COUNTRIES).withProperties(
                PropertyFactory.fillColor(Expression.match(Expression.toNumber(Expression.get("color")),
                    Expression.color(android.graphics.Color.parseColor(p.land)), *colorStops)),
                PropertyFactory.fillAntialias(true)))
            style.addLayer(FillLayer("lakes", SRC_LAKES).withProperties(
                PropertyFactory.fillColor(p.lake)))
        }
        style.addLayer(LineLayer("borders", SRC_BORDERS).withProperties(
            PropertyFactory.lineColor(p.border), PropertyFactory.lineWidth(0.9f)))

        // -- Dynamic sources (empty until the engine publishes) --
        style.addSource(GeoJsonSource(SRC_UNCERTAINTY))
        style.addSource(GeoJsonSource(SRC_ROUTE_ORIGINAL))
        style.addSource(GeoJsonSource(SRC_ROUTE_PLANNED))
        style.addSource(GeoJsonSource(SRC_ROUTE_FLOWN))
        style.addSource(GeoJsonSource(SRC_TRACK_ACTUAL))
        style.addSource(GeoJsonSource(SRC_AIRPORTS))
        style.addSource(GeoJsonSource(SRC_AIRCRAFT))

        style.addLayer(FillLayer("uncertainty", SRC_UNCERTAINTY).withProperties(
            PropertyFactory.fillColor(p.uncertainty), PropertyFactory.fillOpacity(0.18f)))
        style.addLayer(LineLayer("route-original", SRC_ROUTE_ORIGINAL).withProperties(
            PropertyFactory.lineColor(p.routeOriginal), PropertyFactory.lineWidth(1.2f),
            PropertyFactory.lineDasharray(arrayOf(1f, 3f))))
        // Dark casing under the route lines keeps them readable over satellite imagery.
        style.addLayer(LineLayer("route-planned-casing", SRC_ROUTE_PLANNED).withProperties(
            PropertyFactory.lineColor(p.placeHalo), PropertyFactory.lineWidth(4.5f), PropertyFactory.lineOpacity(0.6f)))
        style.addLayer(LineLayer("route-planned", SRC_ROUTE_PLANNED).withProperties(
            PropertyFactory.lineColor(p.routePlanned), PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
        style.addLayer(LineLayer("route-flown-casing", SRC_ROUTE_FLOWN).withProperties(
            PropertyFactory.lineColor(p.placeHalo), PropertyFactory.lineWidth(5.5f), PropertyFactory.lineOpacity(0.6f)))
        style.addLayer(LineLayer("route-flown", SRC_ROUTE_FLOWN).withProperties(
            PropertyFactory.lineColor(p.routeFlown), PropertyFactory.lineWidth(3.5f)))
        style.addLayer(LineLayer("track-actual", SRC_TRACK_ACTUAL).withProperties(
            PropertyFactory.lineColor(p.trackActual), PropertyFactory.lineWidth(2f)))

        // Country names at Natural Earth's curated label points. Density grows with zoom via
        // separate layers per zoom band (filters must not contain zoom expressions).
        val countryBands = listOf(Triple(1.5f, 3.0f, 2), Triple(3.0f, 4.5f, 4), Triple(4.5f, 6.0f, 6), Triple(6.0f, 24.0f, 99))
        for ((i, band) in countryBands.withIndex()) {
            val layer = SymbolLayer("country-label-$i", SRC_COUNTRY_LABELS).withProperties(
                PropertyFactory.textField(nameExpr),
                PropertyFactory.textFont(arrayOf("notosansbold")),
                PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(2, 10f), Expression.stop(5, 13f), Expression.stop(8, 17f))),
                PropertyFactory.textColor(p.countryText),
                PropertyFactory.textHaloColor(p.placeHalo),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textLetterSpacing(0.12f),
                PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                PropertyFactory.textPadding(4f),
                PropertyFactory.textAllowOverlap(false))
                .withFilter(Expression.lte(Expression.get("labelrank"), Expression.literal(band.third)))
            layer.minZoom = band.first; layer.maxZoom = band.second
            style.addLayer(layer)
        }

        // Capitals and the largest cities are labelled at every zoom.
        style.addLayer(SymbolLayer("places-capitals", SRC_PLACES).withProperties(
            PropertyFactory.textField(nameExpr),
            PropertyFactory.textFont(arrayOf("notosansbold")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(1, 9f), Expression.stop(5, 12f), Expression.stop(8, 14f))),
            PropertyFactory.textColor(p.placeText),
            PropertyFactory.textHaloColor(p.placeHalo),
            PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.5f)))
            .withFilter(Expression.any(
                Expression.gte(Expression.toNumber(Expression.get("adm0cap")), Expression.literal(1)),
                Expression.lte(Expression.get("scalerank"), Expression.literal(1)))))

        // Populated places: zoom bands select the scalerank cut-off (0 = largest cities).
        val placeBands = listOf(Triple(0f, 3f, 0), Triple(3f, 4f, 1), Triple(4f, 5f, 3), Triple(5f, 6f, 5),
            Triple(6f, 7f, 6), Triple(7f, 8f, 7), Triple(8f, 24f, 99))
        for ((i, band) in placeBands.withIndex()) {
            val f = Expression.lte(Expression.get("scalerank"), Expression.literal(band.third))
            val dot = CircleLayer("places-dot-$i", SRC_PLACES).withProperties(
                PropertyFactory.circleColor(p.placeDot),
                PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(3, 1.5f), Expression.stop(8, 3f))),
                PropertyFactory.circleStrokeColor(p.placeHalo), PropertyFactory.circleStrokeWidth(0.8f)).withFilter(f)
            dot.minZoom = band.first; dot.maxZoom = band.second
            style.addLayer(dot)
            val label = SymbolLayer("places-label-$i", SRC_PLACES).withProperties(
                PropertyFactory.textField(nameExpr),
                PropertyFactory.textFont(arrayOf("notosans")),
                PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(3, 10f), Expression.stop(8, 13f))),
                PropertyFactory.textColor(p.placeText),
                PropertyFactory.textHaloColor(p.placeHalo),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.5f)),
                PropertyFactory.textOptional(true)).withFilter(f)
            label.minZoom = band.first; label.maxZoom = band.second
            style.addLayer(label)
        }

        style.addLayer(SymbolLayer("airports", SRC_AIRPORTS).withProperties(
            PropertyFactory.textField(Expression.get("code")),
            PropertyFactory.textFont(arrayOf("notosansbold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(p.airportText),
            PropertyFactory.textHaloColor(p.placeHalo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
            PropertyFactory.textOffset(arrayOf(0f, -0.6f)),
            PropertyFactory.textAllowOverlap(true)))
        style.addLayer(CircleLayer("airports-dot", SRC_AIRPORTS).withProperties(
            PropertyFactory.circleColor(p.airport), PropertyFactory.circleRadius(4f),
            PropertyFactory.circleStrokeColor(p.background), PropertyFactory.circleStrokeWidth(1.5f)))

        for (i in 0..3) style.addImage(IMG_AIRCRAFT_LEVEL[i], aircraftBitmap(AIRCRAFT_LEVEL_COLORS[i], i >= 2))
        style.addLayer(SymbolLayer("aircraft", SRC_AIRCRAFT).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(1.0f)))
    }

    /**
     * Draw a top-down aircraft silhouette, nose pointing up (north), so that
     * icon-rotate = track works directly. 64 px canvas.
     */
    /**
     * Aircraft marker, 80 px: translucent dark disc, coloured ring (sensor level), white or
     * outlined silhouette (solid = position measured, hollow = propagated).
     */
    fun aircraftBitmap(ringColor: Int, solid: Boolean): Bitmap {
        val size = 80
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(150, 8, 16, 26)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = ringColor
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3f, paint)
        val scale = 0.70f
        c.save()
        c.translate((size - 64 * scale) / 2f, (size - 64 * scale) / 2f)
        c.scale(scale, scale)
        val path = Path().apply {
            moveTo(32f, 4f)
            lineTo(36f, 10f); lineTo(36f, 26f)
            lineTo(60f, 40f); lineTo(60f, 45f); lineTo(36f, 38f)
            lineTo(35f, 52f); lineTo(43f, 57f); lineTo(43f, 60f)
            lineTo(32f, 58f); lineTo(21f, 60f); lineTo(21f, 57f)
            lineTo(29f, 52f); lineTo(28f, 38f)
            lineTo(4f, 45f); lineTo(4f, 40f); lineTo(28f, 26f)
            lineTo(28f, 10f)
            close()
        }
        if (solid) {
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Color.rgb(8, 16, 26)
            c.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f; paint.color = Color.WHITE
            c.drawPath(path, paint)
        }
        c.restore()
        return bmp
    }
}
