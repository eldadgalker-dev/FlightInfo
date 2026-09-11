// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Parameters
// Version 4.2
// Purpose : Every tunable constant of the application. This is the
//           only file that should need editing to re-tune behaviour.
// Units   : SI throughout (metres, seconds, m/s, degrees, hPa).
//           Display units are converted at emission only (ui/Format.kt).
// =============================================================
package org.skytrack

object Parameters {

    // -- GNSS quality classification --
    const val GNSS_GOOD_HACC_M          = 100.0     // m, horizontal accuracy for GOOD (in-flight median was 57 m; 30 m never happened)
    const val GNSS_GOOD_MIN_SATS        = 5         // satellites used in fix for GOOD
    const val GNSS_DEGRADED_HACC_M      = 2_000.0   // m, horizontal accuracy for DEGRADED (still positions the aircraft)
    const val GNSS_STALE_MS             = 5_000L    // ms, fix older than this counts as NONE
    const val GNSS_INTERVAL_SCREEN_ON_MS  = 1_000L  // ms, requested update interval
    const val GNSS_INTERVAL_SCREEN_OFF_MS = 5_000L  // ms, interval when screen is off in cruise
    const val GNSS_MIN_SPEED_FOR_TRACK  = 20.0      // m/s, below this GNSS bearing is ignored
    const val GNSS_SPEED_NOISE_MPS      = 1.5       // m/s, reported speed below this is receiver noise => 0

    // -- Fusion mode switching --
    const val GNSS_LOSS_TO_CONSTRAINED_MS = 10_000L // ms without fix before route-constrained mode
    const val GYRO_TRUST_WINDOW_S       = 90.0      // s, gyro-integrated heading DISPLAYED after fix loss
    const val GYRO_PROGRESS_WINDOW_S    = 300.0     // s, gyro heading used to scale along-track progress (cos of deviation)
    const val MANEUVER_HEADING_DEG      = 60.0      // deg, heading away from route course => manoeuvring (hold, vectoring)
    const val MANEUVER_CONFIRM_S        = 45.0      // s, sustained before the manoeuvring flag is raised
    const val MANEUVER_SIGMA_FACTOR     = 3.0       // uncertainty growth multiplier while manoeuvring
    const val GYRO_TURN_EVIDENCE_DEG    = 15.0      // deg, integrated turn (60 s window) that widens the uncertainty
    const val SPEED_BLEND_TAU_S         = 600.0     // s, blend from last speed to phase-typical speed
    const val SPEED_AVG_WINDOW_S        = 60.0      // s, moving average window for reference speed

    // -- Measured-first positioning (4.0) --
    // With a fix the aircraft is drawn where it was measured; the governing route is the
    // great circle from the latest measured position to the destination, re-anchored
    // whenever the aircraft has moved away from the current one. Without a fix the aircraft
    // propagates along that route.
    const val REANCHOR_CROSS_M          = 1_000.0   // m, lateral offset from the governing route that re-anchors it
    const val REANCHOR_MIN_INTERVAL_S   = 20.0      // s, do not rebuild the route more often than this
    const val WEAK_FIX_MAX_HACC_M       = 2_000.0   // m, fixes worse than this are ignored entirely
    const val TRACK_DECIMATION_M        = 500.0     // m, spacing of stored actual-track points
    const val TRACK_MAX_POINTS          = 20_000    // memory bound (~10,000 km at 500 m)
    const val ORIGIN_MISMATCH_M         = 50_000.0  // m, ground fix farther than this from origin => warning
    const val TERMINAL_AREA_M           = 60_000.0  // m, within this of destination and descending => DESCENT phase
    const val DESCENT_NEAR_DEST_M       = 150_000.0 // m, remaining distance below which a descent is expected

    // -- GNSS warning escalation (live mode) --
    const val GNSS_WARN_AFTER_S         = 30.0      // s without a fix before the first warning
    const val GNSS_WARN_LEVEL2_S        = 180.0     // s, stronger warning
    const val GNSS_WARN_LEVEL3_S        = 600.0     // s, strongest warning
    const val GNSS_RELIEF_SHOW_S        = 20.0      // s, "you can put the phone down" notice after re-acquisition

    // -- Manual visual fix (user identifies a landmark out of the window) --
    const val VISUAL_FIX_SEARCH_RADIUS_M = 200_000.0 // m, landmarks offered around the current estimate
    const val VISUAL_FIX_MAX_CANDIDATES  = 10
    const val VISUAL_DIST_NEAR_M         = 20_000.0  // m, "close" landmark
    const val VISUAL_DIST_MID_M          = 55_000.0  // m, "some distance"
    const val VISUAL_DIST_FAR_M          = 100_000.0 // m, "far, near the horizon"
    const val VISUAL_FIX_SIGMA_M         = 15_000.0  // m, 1-sigma of a visual fix (bearing +-10 deg, angle +-1 deg)
    const val VISUAL_FIX_SIGMA_BELOW_M   = 8_000.0   // m, landmark straight below

    // -- Aerial imagery pack (NASA Blue Marble, public domain), downloaded once from GitHub Releases --
    const val AERIAL_PACK_URL  = "https://github.com/eldadgalker-dev/FlightInfo/releases/download/data-v1/bluemarble_z0-6.mbtiles"
    const val AERIAL_PACK_FILE = "bluemarble_z0-6.mbtiles"
    const val AERIAL_MAX_ZOOM  = 6

    // -- Online enrichment (only when a network happens to be available; never required) --
    const val LIVE_ADSB_URL_TEMPLATE  = "https://api.adsb.lol/v2/callsign/%s"   // %s = ICAO callsign, e.g. ELY315
    const val LIVE_POLL_INTERVAL_MS   = 30_000L    // ms between ADS-B queries
    const val LIVE_FIX_HACC_M         = 150.0      // m, assumed accuracy of an ADS-B position
    const val LIVE_MAX_AGE_S          = 120.0      // s, ADS-B report older than this is ignored
    const val LIVE_ONLY_WHEN_GNSS_OLDER_MS = 60_000L // ms, in live mode use ADS-B only when GNSS is this stale

    // -- In-app update (manual check against GitHub Releases) --
    const val UPDATE_REPO_OWNER = "eldadgalker-dev"
    const val UPDATE_REPO_NAME  = "FlightInfo"
    const val UPDATE_ASSET_NAME = "FlightInfo.apk"   // fixed-name asset published by the build workflow

    // -- Estimate-only mode --
    const val TAXI_ALLOWANCE_S          = 900.0     // s, scheduled departure -> assumed takeoff
    const val DEPARTURE_FUTURE_GRACE_S  = 1_800.0   // s, scheduled time this far ahead => flight left yesterday

    // -- Uncertainty growth --
    const val ALONG_TRACK_DRIFT_RATE    = 0.06      // fraction of distance flown since the last fix (MUC-TLV: 34 km after 24 min = 10 % of 346 km; 2-sigma covers)
    const val SIGMA_S_INITIAL_M         = 50_000.0  // m, prior along-track sigma (predicted-only)
    const val SIGMA_MIN_M               = 10.0      // m, floor to keep Kalman gains finite

    // -- Phase-typical ground speeds (typical narrow/wide body jet) --
    const val SPEED_CLIMB_MPS           = 150.0     // m/s (~290 kt ground speed average in climb)
    const val SPEED_CRUISE_MPS          = 235.0     // m/s (~845 km/h, ~455 kt)
    const val SPEED_DESCENT_MPS         = 140.0     // m/s (~270 kt average in descent)
    const val CLIMB_DURATION_S          = 1_200.0   // s, predicted-only profile climb length
    const val DESCENT_ALLOWANCE_S       = 1_500.0   // s, descent time assumed in ETE (MUC-TLV log: 33 min from FL370 incl. approach)
    const val APPROACH_ALLOWANCE_S      = 300.0     // s, added while in DESCENT for the approach pattern
    const val TOUCHDOWN_SPEED_MPS       = 70.0      // m/s, ground speed at touchdown for the descent average
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
    const val PHASE_GNSS_CONFIRM_S      = 60.0      // s, sustained GNSS vertical rate before a phase change
    const val PHASE_LANDED_ALT_ABOVE_M  = 300.0     // m, GNSS altitude above the destination elevation counts as on the ground
    const val PHASE_LOW_ALT_LOCK_M      = 1_500.0   // m above destination: below this DESCENT never reverts to CLIMB
    // -- Accelerometer: takeoff roll and landing deceleration --
    const val ACCEL_GRAVITY_TAU_S       = 3.0       // s, low-pass for the gravity estimate
    const val ACCEL_WINDOW_S            = 12.0      // s, averaging window for horizontal acceleration
    const val ACCEL_TAKEOFF_MPS2        = 1.6       // m/s^2, sustained horizontal acceleration => takeoff roll
    const val ACCEL_TAKEOFF_S           = 15.0      // s, minimum duration of the roll
    const val ACCEL_LANDING_MPS2        = 1.6       // m/s^2, sustained deceleration after descent => landed
    const val ACCEL_LANDING_S           = 8.0       // s
    const val ACCEL_DIRECTION_STD_DEG   = 25.0      // deg, direction must be stable (phone at rest, not handled)
    const val BARO_LOWPASS_ALPHA        = 0.05      // dimensionless, pressure low-pass coefficient
    const val BARO_RATE_WINDOW_S        = 60.0      // s, pressure rate estimation window

    // -- Ground reference (user confirms "on the ground now" before takeoff) --
    const val GROUND_REF_MAX_AGE_H      = 18.0      // h, reference discarded after this
    const val STD_ATMOS_SCALE_M         = 44_330.0  // m, barometric formula constant
    const val STD_ATMOS_EXP             = 0.190263  // 1/5.2559, barometric formula exponent

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
