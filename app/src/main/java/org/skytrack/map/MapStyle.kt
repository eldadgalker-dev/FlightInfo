// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapStyle
// Version 2.1
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

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
    const val IMG_AIRCRAFT_SOLID = "aircraft-solid"
    const val IMG_AIRCRAFT_OUTLINE = "aircraft-outline"

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
    fun install(style: Style, p: Palette, base: Map<String, String>, hebrew: Boolean) {
        // Label text: localised name when available, else the English name.
        val nameExpr: Expression = if (hebrew) Expression.coalesce(Expression.get("name_he"), Expression.get("name")) else Expression.get("name")

        // -- Static base layers --
        style.addSource(GeoJsonSource(SRC_LAND, base.getValue(SRC_LAND)))
        style.addSource(GeoJsonSource(SRC_COUNTRIES, base.getValue(SRC_COUNTRIES)))
        style.addSource(GeoJsonSource(SRC_LAKES, base.getValue(SRC_LAKES)))
        style.addSource(GeoJsonSource(SRC_BORDERS, base.getValue(SRC_BORDERS)))
        style.addSource(GeoJsonSource(SRC_PLACES, base.getValue(SRC_PLACES)))
        style.addSource(GeoJsonSource(SRC_COUNTRY_LABELS, base.getValue(SRC_COUNTRY_LABELS)))

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
        style.addLayer(LineLayer("route-planned", SRC_ROUTE_PLANNED).withProperties(
            PropertyFactory.lineColor(p.routePlanned), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))))
        style.addLayer(LineLayer("route-flown", SRC_ROUTE_FLOWN).withProperties(
            PropertyFactory.lineColor(p.routeFlown), PropertyFactory.lineWidth(3f)))
        style.addLayer(LineLayer("track-actual", SRC_TRACK_ACTUAL).withProperties(
            PropertyFactory.lineColor(p.trackActual), PropertyFactory.lineWidth(2f)))

        // Country names at Natural Earth's curated label points; more appear as you zoom in.
        style.addLayer(SymbolLayer("country-label", SRC_COUNTRY_LABELS).withProperties(
            PropertyFactory.textField(nameExpr),
            PropertyFactory.textFont(arrayOf("notosansbold")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(2, 9f), Expression.stop(5, 13f), Expression.stop(8, 16f))),
            PropertyFactory.textColor(p.countryText),
            PropertyFactory.textHaloColor(p.placeHalo),
            PropertyFactory.textHaloWidth(1.0f),
            PropertyFactory.textLetterSpacing(if (hebrew) 0f else 0.15f),
            PropertyFactory.textTransform(if (hebrew) Property.TEXT_TRANSFORM_NONE else Property.TEXT_TRANSFORM_UPPERCASE),
            PropertyFactory.textPadding(6f))
            .withFilter(Expression.lte(Expression.get("labelrank"),
                Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(1.5, 2), Expression.stop(3, 4), Expression.stop(4.5, 6), Expression.stop(6, 10)))))

        // Populated places: dot always, label filtered by scalerank per zoom (0 = largest cities).
        val placeRankByZoom = Expression.interpolate(Expression.linear(), Expression.zoom(),
            Expression.stop(2, 0), Expression.stop(3, 1), Expression.stop(4, 3), Expression.stop(5, 5),
            Expression.stop(6, 6), Expression.stop(7, 7), Expression.stop(8, 8))
        style.addLayer(CircleLayer("places-dot", SRC_PLACES).withProperties(
            PropertyFactory.circleColor(p.placeDot),
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(3, 1.5f), Expression.stop(8, 3f))),
            PropertyFactory.circleStrokeColor(p.placeHalo), PropertyFactory.circleStrokeWidth(0.8f))
            .withFilter(Expression.lte(Expression.get("scalerank"), placeRankByZoom)))
        style.addLayer(SymbolLayer("places-label", SRC_PLACES).withProperties(
            PropertyFactory.textField(nameExpr),
            PropertyFactory.textFont(arrayOf("notosans")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(3, 10f), Expression.stop(8, 13f))),
            PropertyFactory.textColor(p.placeText),
            PropertyFactory.textHaloColor(p.placeHalo),
            PropertyFactory.textHaloWidth(1.3f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.5f)),
            PropertyFactory.textOptional(true))
            .withFilter(Expression.lte(Expression.get("scalerank"), placeRankByZoom)))

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

        style.addImage(IMG_AIRCRAFT_SOLID, aircraftBitmap(p.aircraft, p.aircraftOutline, solid = true))
        style.addImage(IMG_AIRCRAFT_OUTLINE, aircraftBitmap(p.aircraft, p.aircraftOutline, solid = false))
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
    fun aircraftBitmap(fill: Int, outline: Int, solid: Boolean): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            // Fuselage and wings in a 64x64 box, nose at (32, 4).
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (solid) {
            paint.style = Paint.Style.FILL; paint.color = fill
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = outline
            c.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL; paint.color = outline
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = fill
            c.drawPath(path, paint)
        }
        return bmp
    }
}
