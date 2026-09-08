// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - MapStyle
// Version 1.1
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
    val placeDot: String,
    val placeText: String,
    val placeHalo: String,
    val routePlanned: String,
    val routeFlown: String,
    val uncertainty: String,
    val airport: String,
    val airportText: String,
    val aircraft: Int,
    val aircraftOutline: Int
)

object MapStyle {

    val NIGHT = Palette(
        background = "#0b1622", land = "#1f2a36", lake = "#0b1622", border = "#3a4a5c",
        placeDot = "#c9d3dd", placeText = "#c9d3dd", placeHalo = "#0b1622",
        routePlanned = "#7aa2c4", routeFlown = "#ffb454", uncertainty = "#ffb454",
        airport = "#ffffff", airportText = "#ffffff",
        aircraft = Color.rgb(255, 214, 102), aircraftOutline = Color.rgb(11, 22, 34)
    )

    val DAY = Palette(
        background = "#b9c8d8", land = "#e8e2d4", lake = "#b9c8d8", border = "#9a9a9a",
        placeDot = "#333333", placeText = "#222222", placeHalo = "#ffffff",
        routePlanned = "#2e5f8a", routeFlown = "#d9581e", uncertainty = "#d9581e",
        airport = "#111111", airportText = "#111111",
        aircraft = Color.rgb(20, 20, 20), aircraftOutline = Color.WHITE
    )

    // Source and layer identifiers
    const val SRC_LAND = "ne-land"
    const val SRC_LAKES = "ne-lakes"
    const val SRC_BORDERS = "ne-borders"
    const val SRC_PLACES = "ne-places"
    const val SRC_ROUTE_PLANNED = "route-planned"
    const val SRC_ROUTE_FLOWN = "route-flown"
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
    fun install(style: Style, p: Palette, base: Map<String, String>) {
        // -- Static base layers --
        style.addSource(GeoJsonSource(SRC_LAND, base.getValue(SRC_LAND)))
        style.addSource(GeoJsonSource(SRC_LAKES, base.getValue(SRC_LAKES)))
        style.addSource(GeoJsonSource(SRC_BORDERS, base.getValue(SRC_BORDERS)))
        style.addSource(GeoJsonSource(SRC_PLACES, base.getValue(SRC_PLACES)))

        style.addLayer(FillLayer("land", SRC_LAND).withProperties(
            PropertyFactory.fillColor(p.land), PropertyFactory.fillAntialias(true)))
        style.addLayer(FillLayer("lakes", SRC_LAKES).withProperties(
            PropertyFactory.fillColor(p.lake)))
        style.addLayer(LineLayer("borders", SRC_BORDERS).withProperties(
            PropertyFactory.lineColor(p.border), PropertyFactory.lineWidth(0.8f)))

        // -- Dynamic sources (empty until the engine publishes) --
        style.addSource(GeoJsonSource(SRC_UNCERTAINTY))
        style.addSource(GeoJsonSource(SRC_ROUTE_PLANNED))
        style.addSource(GeoJsonSource(SRC_ROUTE_FLOWN))
        style.addSource(GeoJsonSource(SRC_AIRPORTS))
        style.addSource(GeoJsonSource(SRC_AIRCRAFT))

        style.addLayer(FillLayer("uncertainty", SRC_UNCERTAINTY).withProperties(
            PropertyFactory.fillColor(p.uncertainty), PropertyFactory.fillOpacity(0.18f)))
        style.addLayer(LineLayer("route-planned", SRC_ROUTE_PLANNED).withProperties(
            PropertyFactory.lineColor(p.routePlanned), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))))
        style.addLayer(LineLayer("route-flown", SRC_ROUTE_FLOWN).withProperties(
            PropertyFactory.lineColor(p.routeFlown), PropertyFactory.lineWidth(3f)))

        // Populated places: dot always, label filtered by scalerank per zoom.
        style.addLayer(CircleLayer("places-dot", SRC_PLACES).withProperties(
            PropertyFactory.circleColor(p.placeDot), PropertyFactory.circleRadius(2f))
            .withFilter(Expression.lte(Expression.get("scalerank"),
                Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(2, 1), Expression.stop(4, 3), Expression.stop(6, 6)))))
        style.addLayer(SymbolLayer("places-label", SRC_PLACES).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("notosans")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(p.placeText),
            PropertyFactory.textHaloColor(p.placeHalo),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
            PropertyFactory.textOptional(true))
            .withFilter(Expression.lte(Expression.get("scalerank"),
                Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(2, 0), Expression.stop(4, 2), Expression.stop(6, 6)))))

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
