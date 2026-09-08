// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - CoreTests
// Version 1.5
// Purpose : JVM unit tests for Geodesy, Route projection and the
//           Estimator (GNSS gap behaviour, reacquisition, off-route).
//           These run without an emulator: ./gradlew test
// =============================================================
package org.skytrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.skytrack.fusion.Estimator
import org.skytrack.fusion.FusionMode
import org.skytrack.route.GeoPoint
import org.skytrack.route.Geodesy
import org.skytrack.route.Route
import org.skytrack.scan.Bcbp
import org.skytrack.sensors.FlightPhase
import org.skytrack.sensors.GnssQuality
import org.skytrack.sensors.GnssSample
import kotlin.math.abs

class CoreTests {

    private val tlv = GeoPoint(32.0114, 34.8867)
    private val jfk = GeoPoint(40.6398, -73.7789)
    private val lhr = GeoPoint(51.4775, -0.4614)

    @Test
    fun greatCircleDistanceMatchesReference() {
        // TLV-JFK published great-circle distance ~9,132 km; spherical model within 0.5 %.
        val d = Geodesy.distance(tlv, jfk)
        assertTrue("TLV-JFK = $d", abs(d - 9_132_000.0) < 46_000.0)
        val d2 = Geodesy.distance(tlv, lhr)
        assertTrue("TLV-LHR = $d2", abs(d2 - 3_574_000.0) < 20_000.0)
    }

    @Test
    fun routeProjectionRoundTrip() {
        val r = Route(tlv, jfk)
        for (frac in listOf(0.0, 0.1, 0.37, 0.5, 0.83, 1.0)) {
            val s = r.lengthM * frac
            val p = r.pointAt(s)
            val proj = r.project(p)
            assertTrue("s=$s got ${proj.alongM}", abs(proj.alongM - s) < 500.0)
            assertTrue("d should be ~0, got ${proj.crossM}", abs(proj.crossM) < 200.0)
        }
    }

    @Test
    fun lateralOffsetIsRecovered() {
        val r = Route(tlv, lhr)
        val s = r.lengthM * 0.4
        val p = r.pointAtWithOffset(s, 25_000.0)
        val proj = r.project(p)
        assertTrue(abs(proj.alongM - s) < 2_000.0)
        assertTrue("cross=${proj.crossM}", abs(proj.crossM - 25_000.0) < 1_000.0)
    }

    @Test
    fun antimeridianRouteHasNoDiscontinuity() {
        val tokyo = GeoPoint(35.7647, 140.3864)
        val lax = GeoPoint(33.9425, -118.4081)
        val r = Route(tokyo, lax)
        var prev = r.points[0]
        for (p in r.points.drop(1)) {
            assertTrue(Geodesy.distance(prev, p) < 25_000.0)
            prev = p
        }
        val mid = r.pointAt(r.lengthM / 2)
        assertTrue(abs(r.project(mid).alongM - r.lengthM / 2) < 500.0)
    }

    @Test
    fun estimatorStaysOnRouteDuringGapAndReacquiresSmoothly() {
        val r = Route(tlv, jfk)
        val est = Estimator(r)
        val v = 235.0
        var t = 0L
        val startS = r.lengthM * 0.3

        fun fixAt(s: Double, time: Long): GnssSample {
            val p = r.pointAt(s)
            return GnssSample(time, p.lat, p.lon, 11_000.0, true, v, true, r.bearingAt(s), true,
                10.0, 15.0, 9, 12, GnssQuality.GOOD)
        }

        // 120 s of good fixes
        while (t < 120_000) {
            est.onGnss(fixAt(startS + v * t / 1000.0, t))
            val e = est.tick(t, FlightPhase.CRUISE, null)
            if (t > 10_000) assertEquals(FusionMode.GNSS_TRACKING, e.mode)
            t += 1000
        }
        // 40 minute outage: propagate only
        val gapStart = t
        var last = est.tick(t, FlightPhase.CRUISE, null)
        while (t < gapStart + 40 * 60_000) {
            t += 1000
            val e = est.tick(t, FlightPhase.CRUISE, null)
            assertTrue(abs(e.alongTrackM - last.alongTrackM) < 2_000.0)   // no jumps
            assertTrue(abs(e.crossTrackM) < 500.0)                         // on the route
            if (t - gapStart > 12_000) assertEquals(FusionMode.ROUTE_CONSTRAINED, e.mode)
            last = e
        }
        // Truth after the gap and reacquisition
        val truthS = startS + v * t / 1000.0
        val errBefore = abs(last.alongTrackM - truthS)
        assertTrue("drift after 40 min = $errBefore m", errBefore < 40_000.0)
        est.onGnss(fixAt(truthS, t))
        val e2 = est.tick(t + 1000, FlightPhase.CRUISE, null)
        assertTrue("after reacquisition err=${abs(e2.alongTrackM - truthS)}", abs(e2.alongTrackM - truthS) < 3_000.0)
        assertEquals(FusionMode.GNSS_TRACKING, e2.mode)
    }

    @Test
    fun offRouteDetectionTriggersAndClears() {
        val r = Route(tlv, jfk)
        val est = Estimator(r)
        var t = 0L
        val s = r.lengthM * 0.5
        fun fix(cross: Double, time: Long): GnssSample {
            val p = r.pointAtWithOffset(s + 235.0 * time / 1000.0, cross)
            return GnssSample(time, p.lat, p.lon, 11_000.0, true, 235.0, true, r.bearingAt(s), true,
                10.0, 15.0, 9, 12, GnssQuality.GOOD)
        }
        for (i in 0 until 5) { est.onGnss(fix(60_000.0, t)); est.tick(t, FlightPhase.CRUISE, null); t += 1000 }
        assertEquals(FusionMode.OFF_ROUTE, est.tick(t, FlightPhase.CRUISE, null).mode)
        for (i in 0 until 70) { est.onGnss(fix(0.0, t)); est.tick(t, FlightPhase.CRUISE, null); t += 1000 }
        assertEquals(FusionMode.GNSS_TRACKING, est.tick(t, FlightPhase.CRUISE, null).mode)
    }

    @Test
    fun predictedOnlyProfileReachesDestination() {
        val r = Route(tlv, lhr)
        val est = Estimator(r)
        val takeoff = 1_000_000L
        val early = est.tick(takeoff + 60_000, FlightPhase.CRUISE, takeoff)
        assertEquals(FusionMode.PREDICTED_ONLY, early.mode)
        assertTrue(early.alongTrackM > 0.0 && early.alongTrackM < 20_000.0)
        val late = est.tick(takeoff + 6 * 3600_000L, FlightPhase.CRUISE, takeoff)
        assertTrue(abs(late.alongTrackM - r.lengthM) < 1.0)
    }

    @Test
    fun bcbpParsesIataReferenceExample() {
        // Example payload from IATA Resolution 792 documentation (single leg, no optional block).
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val bp = Bcbp.parse(raw, java.time.LocalDate.of(2026, 11, 20))
        assertTrue(bp != null)
        assertEquals("YUL", bp!!.fromIata)
        assertEquals("FRA", bp.toIata)
        assertEquals("AC834", bp.flightNumber)
        assertEquals("1A", bp.seat)
        assertEquals(java.time.LocalDate.of(2026, 11, 22), bp.date)   // day 326 of 2026
    }

    @Test
    fun bcbpRejectsNonBoardingPassText() {
        assertTrue(Bcbp.parse("https://example.invalid/ticket/123456") == null)
        assertTrue(Bcbp.parse("M1SHORT") == null)
        assertTrue(Bcbp.parse("X1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100") == null)
    }

    @Test
    fun bcbpDayOfYearResolvesAcrossNewYear() {
        // Scanned on 30 Dec for a flight on day 2 -> next year.
        val d = Bcbp.resolveDayOfYear(2, java.time.LocalDate.of(2026, 12, 30))
        assertEquals(java.time.LocalDate.of(2027, 1, 2), d)
    }
}
