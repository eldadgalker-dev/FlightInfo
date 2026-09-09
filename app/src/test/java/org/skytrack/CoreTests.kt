// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - CoreTests
// Version 2.3
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
            est.onGnss(fixAt(startS + v * t / 1000.0, t), FlightPhase.CRUISE)
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
            assertTrue(abs(r.project(GeoPoint(e.lat, e.lon)).crossM) < 200.0)  // drawn on the route
            if (t - gapStart > 12_000) assertEquals(FusionMode.ROUTE_CONSTRAINED, e.mode)
            last = e
        }
        // Truth after the gap and reacquisition
        val truthS = startS + v * t / 1000.0
        val errBefore = abs(last.alongTrackM - truthS)
        assertTrue("drift after 40 min = $errBefore m", errBefore < 40_000.0)
        est.onGnss(fixAt(truthS, t), FlightPhase.CRUISE)
        val e2 = est.tick(t + 1000, FlightPhase.CRUISE, null)
        assertTrue("after reacquisition err=${abs(e2.alongTrackM - truthS)}", abs(e2.alongTrackM - truthS) < 3_000.0)
        assertEquals(FusionMode.GNSS_TRACKING, e2.mode)
    }

    @Test
    fun aircraftStaysOnRouteUntilDeviationIsProven_thenReplans() {
        val r = Route(tlv, jfk)
        val est = Estimator(r)
        val v = 235.0
        val s0 = r.lengthM * 0.5
        var t = 0L
        // Fixes 25 km right of the route, heading 12 degrees off the route course.
        fun fix(cross: Double, time: Long): GnssSample {
            val s = s0 + v * time / 1000.0
            val p = r.pointAtWithOffset(s, cross)
            return GnssSample(time, p.lat, p.lon, 11_000.0, true, v, true,
                Geodesy.wrapBearing(r.bearingAt(s) + (if (cross > 0) 12.0 else 0.0)), true, 10.0, 15.0, 9, 12, GnssQuality.GOOD)
        }
        // Five fixes over 100 s: below the 6-fix / 180 s threshold -> still on the planned route.
        for (i in 0 until 5) { est.onGnss(fix(25_000.0, t), FlightPhase.CRUISE); t += 25_000 }
        var e = est.tick(t, FlightPhase.CRUISE, null)
        assertEquals(0, e.replanCount)
        assertTrue(abs(r.project(GeoPoint(e.lat, e.lon)).crossM) < 200.0)        // drawn ON the route
        assertTrue("measured offset exposed", abs((e.measuredCrossM ?: 0.0) - 25_000.0) < 1_500.0)
        assertTrue(e.deviationEvidence >= 4)
        // More consistent fixes -> proven deviation -> governing route re-planned to destination.
        for (i in 0 until 6) { est.onGnss(fix(25_000.0, t), FlightPhase.CRUISE); t += 30_000 }
        e = est.tick(t, FlightPhase.CRUISE, null)
        assertEquals(1, e.replanCount)
        assertTrue(est.route !== est.plannedRoute)
        assertTrue(Geodesy.distance(est.route.destination, jfk) < 1.0)
        assertTrue(abs(est.route.project(GeoPoint(e.lat, e.lon)).crossM) < 200.0) // on the NEW route
        assertTrue(est.actualTrack.size >= 2)
        // Total flown continues to count from the origin.
        assertTrue(e.totalFlownM > s0 && e.totalFlownM < s0 + v * (t / 1000.0) + 5_000.0)
    }

    @Test
    fun noisyOffsetsDoNotTriggerReplan() {
        val r = Route(tlv, jfk)
        val est = Estimator(r)
        var t = 0L
        val s0 = r.lengthM * 0.5
        // Alternating sign, small heading disagreement: never consistent -> no re-plan.
        for (i in 0 until 20) {
            val cross = if (i % 2 == 0) 16_000.0 else -16_000.0
            val s = s0 + 235.0 * t / 1000.0
            val p = r.pointAtWithOffset(s, cross)
            est.onGnss(GnssSample(t, p.lat, p.lon, 11_000.0, true, 235.0, true, r.bearingAt(s), true, 10.0, 15.0, 9, 12, GnssQuality.GOOD), FlightPhase.CRUISE)
            t += 20_000
        }
        assertEquals(0, est.tick(t, FlightPhase.CRUISE, null).replanCount)
    }

    @Test
    fun onGroundAircraftSitsAtOriginAndFlagsMismatch() {
        val r = Route(tlv, jfk)
        val est = Estimator(r)
        val munich = GeoPoint(48.14, 11.58)
        est.onGnss(GnssSample(1000, munich.lat, munich.lon, 500.0, true, 0.0, true, 0.0, false, 10.0, 15.0, 8, 12, GnssQuality.GOOD), FlightPhase.GROUND)
        val e = est.tick(2000, FlightPhase.GROUND, null)
        assertTrue(Geodesy.distance(GeoPoint(e.lat, e.lon), tlv) < 100.0)
        assertTrue(e.originMismatchM != null && e.originMismatchM!! > 2_000_000.0)
    }

    @Test
    fun estimateOnlyIgnoresFixes() {
        val r = Route(tlv, lhr)
        val est = Estimator(r)
        val takeoff = 1_000_000L
        val now = takeoff + 3600_000L
        val p = r.pointAt(r.lengthM * 0.9)
        est.onGnss(GnssSample(now, p.lat, p.lon, 11_000.0, true, 235.0, true, 0.0, false, 10.0, 15.0, 9, 12, GnssQuality.GOOD), FlightPhase.CRUISE)
        val e = est.tick(now, est.predictedPhase(now, takeoff), takeoff, sensorsLive = false)
        assertEquals(FusionMode.PREDICTED_ONLY, e.mode)
        assertTrue(e.measuredCrossM == null)
        assertTrue(e.lastFixAgeMs == -1L)
        // 1 h after takeoff on a ~3,570 km route: 20 min climb + 40 min cruise = 180 km + 564 km
        assertTrue("along=${e.alongTrackM}", abs(e.alongTrackM - (180_000.0 + 40 * 60 * 235.0)) < 5_000.0)
        assertEquals(FlightPhase.CRUISE, e.phase)
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

    @Test
    fun visualFixMovesAlongRouteOnlyAndLeavesPredictedMode() {
        val r = Route(tlv, lhr)
        val est = Estimator(r)
        val takeoff = 1_000_000L
        val now = takeoff + 2 * 3600_000L
        // Predicted-only puts the aircraft far along; the passenger sees a landmark that is
        // actually abeam s = 1,000 km, 50 km to the right of track.
        val truthS = 1_000_000.0
        val landmark = r.pointAtWithOffset(truthS, 50_000.0)
        val before = est.tick(now, FlightPhase.CRUISE, takeoff)
        assertEquals(FusionMode.PREDICTED_ONLY, before.mode)
        est.onVisualFix(landmark, sideRight = true, distanceM = 50_000.0, sigmaM = 15_000.0, now = now, phase = FlightPhase.CRUISE)
        val after = est.tick(now + 1000, FlightPhase.CRUISE, takeoff)
        assertEquals(FusionMode.ROUTE_CONSTRAINED, after.mode)
        assertTrue("along=${after.alongTrackM}", abs(after.alongTrackM - truthS) < 15_000.0)  // within the fix sigma
        assertTrue(abs(r.project(GeoPoint(after.lat, after.lon)).crossM) < 200.0)   // still drawn on the route
        assertTrue(after.groundSpeedMps > 200.0)                                       // keeps moving at cruise speed
        assertEquals(1, after.visualFixCount)
    }
}
