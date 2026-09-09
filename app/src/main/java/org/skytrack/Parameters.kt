// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Parameters
// Version 2.0
// Purpose : Every tunable constant of the application. This is the
//           only file that should need editing to re-tune behaviour.
// Units   : SI throughout (metres, seconds, m/s, degrees, hPa).
//           Display units are converted at emission only (ui/Format.kt).
// =============================================================
package org.skytrack

object Parameters {

    // -- GNSS quality classification --
    const val GNSS_GOOD_HACC_M          = 30.0      // m, horizontal accuracy for GOOD
    const val GNSS_GOOD_MIN_SATS        = 5         // satellites used in fix for GOOD
    const val GNSS_DEGRADED_HACC_M      = 200.0     // m, horizontal accuracy for DEGRADED
    const val GNSS_STALE_MS             = 5_000L    // ms, fix older than this counts as NONE
    const val GNSS_INTERVAL_SCREEN_ON_MS  = 1_000L  // ms, requested update interval
    const val GNSS_INTERVAL_SCREEN_OFF_MS = 5_000L  // ms, interval when screen is off in cruise
    const val GNSS_MIN_SPEED_FOR_TRACK  = 20.0      // m/s, below this GNSS bearing is ignored

    // -- Fusion mode switching --
    const val GNSS_LOSS_TO_CONSTRAINED_MS = 10_000L // ms without fix before route-constrained mode
    const val GYRO_TRUST_WINDOW_S       = 90.0      // s, gyro-integrated heading trusted after fix loss
    const val SPEED_BLEND_TAU_S         = 600.0     // s, blend from last speed to phase-typical speed
    const val SPEED_AVG_WINDOW_S        = 60.0      // s, moving average window for reference speed

    // -- Route anchoring and proven deviation (re-anchoring) --
    // The aircraft is always drawn ON the governing route. A measured lateral
    // offset becomes a "proven deviation" only when all of the following hold,
    // after which the governing route is re-planned from the proven position
    // to the destination and the actual track is drawn.
    const val DEVIATION_MIN_CROSS_M     = 15_000.0  // m, minimum consistent lateral offset
    const val DEVIATION_MIN_FIXES       = 6         // GOOD fixes with consistent offset sign
    const val DEVIATION_MIN_FIXES_TURN  = 3         // ...when the gyro has recently recorded a turn
    const val DEVIATION_MIN_DURATION_S  = 180.0     // s, span of the evidence window
    const val DEVIATION_MIN_HEADING_DEG = 8.0       // deg, GNSS track vs route course, consistent
    const val DEVIATION_SPEED_TOLERANCE = 0.30      // fraction, along-track progress vs measured speed
    const val GYRO_TURN_EVIDENCE_DEG    = 15.0      // deg, integrated turn (60 s window) on a straight route
    const val GYRO_TURN_MEMORY_S        = 600.0     // s, how long a recorded turn lowers the fix requirement
    const val TERMINAL_AREA_M           = 60_000.0  // m, within this of destination every GOOD fix re-anchors
    const val TERMINAL_REANCHOR_MIN_M   = 2_000.0   // m, lateral offset that triggers a terminal re-anchor
    const val ORIGIN_MISMATCH_M         = 50_000.0  // m, ground fix farther than this from origin => warning
    const val TRACK_DECIMATION_M        = 5_000.0   // m, spacing of stored actual-track points

    // -- Estimate-only mode --
    const val TAXI_ALLOWANCE_S          = 900.0     // s, scheduled departure -> assumed takeoff
    const val DEPARTURE_FUTURE_GRACE_S  = 1_800.0   // s, scheduled time this far ahead => flight left yesterday

    // -- Uncertainty growth --
    const val ALONG_TRACK_DRIFT_RATE    = 0.03      // fraction of speed per second since last fix
    const val SIGMA_S_INITIAL_M         = 50_000.0  // m, prior along-track sigma (predicted-only)
    const val SIGMA_MIN_M               = 10.0      // m, floor to keep Kalman gains finite

    // -- Phase-typical ground speeds (typical narrow/wide body jet) --
    const val SPEED_CLIMB_MPS           = 150.0     // m/s (~290 kt ground speed average in climb)
    const val SPEED_CRUISE_MPS          = 235.0     // m/s (~845 km/h, ~455 kt)
    const val SPEED_DESCENT_MPS         = 140.0     // m/s (~270 kt average in descent)
    const val CLIMB_DURATION_S          = 1_200.0   // s, predicted-only profile climb length
    const val DESCENT_ALLOWANCE_S       = 1_200.0   // s, descent time assumed in ETE
    const val DESCENT_DISTANCE_M        = 170_000.0 // m, ground distance covered in descent

    // -- Flight phase detector --
    const val PHASE_TAKEOFF_SPEED_MPS   = 75.0      // m/s (~146 kt) ground speed => airborne
    const val PHASE_GROUND_SPEED_MPS    = 40.0      // m/s, below this while previously descending => landed
    const val PHASE_CLIMB_BARO_RATE     = -0.6      // hPa/min, cabin pressure falling => climb
    const val PHASE_DESCENT_BARO_RATE   = 0.6       // hPa/min, cabin pressure rising => descent
    const val PHASE_CRUISE_STABLE_S     = 180.0     // s, near-zero rate before CLIMB -> CRUISE
    const val PHASE_DESCENT_CONFIRM_S   = 120.0     // s, positive rate before CRUISE -> DESCENT
    const val PHASE_GNSS_VRATE_CLIMB    = 3.0       // m/s, GNSS vertical rate => climb (when GNSS good)
    const val PHASE_GNSS_VRATE_DESCENT  = -3.0      // m/s, GNSS vertical rate => descent
    const val BARO_LOWPASS_ALPHA        = 0.05      // dimensionless, pressure low-pass coefficient
    const val BARO_RATE_WINDOW_S        = 60.0      // s, pressure rate estimation window

    // -- Route model --
    const val ROUTE_SAMPLE_SPACING_M    = 20_000.0  // m, great-circle sample spacing
    const val ORIGIN_MATCH_RADIUS_M     = 15_000.0  // m, ground fix to airport auto-match radius
    const val EARTH_RADIUS_M            = 6_371_008.8 // m, mean Earth radius (spherical model)

    // -- Engine timing --
    const val ENGINE_TICK_MS            = 1_000L    // ms, estimator propagation period
    const val UI_INTERPOLATION_MS       = 1_000     // ms, marker animation between engine ticks
    const val PERSIST_INTERVAL_MS       = 10_000L   // ms, estimate persistence period

    // -- Map --
    const val MAP_MIN_ZOOM              = 1.5
    const val MAP_MAX_ZOOM              = 10.0
    const val MAP_DEFAULT_ZOOM          = 5.0
    const val FOLLOW_RESUME_S           = 20        // s, follow mode auto-resume after user gesture
    const val FIT_PADDING_PX            = 80        // px, padding for fit-route camera
    const val UNCERTAINTY_SIGMAS        = 2.0       // sigma multiplier for drawn along-track band
    const val UNCERTAINTY_BAND_HALF_W_M = 6_000.0   // m, fixed half-width of the drawn band

    // -- Notification --
    const val NOTIFICATION_CHANNEL_ID   = "skytrack_tracking"
    const val NOTIFICATION_ID           = 1001
}
