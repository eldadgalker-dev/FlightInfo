// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - InertialSources
// Version 4.1
// Purpose : Barometer (cabin pressure and its rate), gyroscope yaw rate,
//           and the flight-phase state machine that consumes them.
//
//           IMPORTANT: the barometer measures CABIN pressure in a
//           pressurised aircraft. It is never converted to altitude.
//           Only its rate of change is used, to detect climb/descent.
//           The magnetometer is deliberately not used: the fuselage
//           distorts it beyond usefulness.
// Units   : hPa, hPa/min, rad/s -> deg/s, m/s.
// =============================================================
package org.skytrack.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.skytrack.Parameters
import kotlin.math.abs
import kotlin.math.sqrt

/** Filtered cabin pressure and its rate. rateHpaPerMin < 0 means pressure falling (climb). */
data class BaroSample(val timeMs: Long, val pressureHpa: Double, val rateHpaPerMin: Double)

/** Yaw rate about the gravity axis, degrees per second, positive = turning right. */
data class GyroSample(val timeMs: Long, val yawRateDps: Double)

/**
 * Horizontal (gravity-removed) acceleration averaged over ACCEL_WINDOW_S, and whether
 * its direction was stable over the window. A stable, sustained value of ~2-3 m/s^2
 * is a takeoff roll or a landing deceleration; an unstable one is the phone being handled.
 */
data class MotionSample(val timeMs: Long, val horizAccelMps2: Double, val directionStable: Boolean, val windowS: Double)

class BaroSource(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val available: Boolean = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    fun samples(): Flow<BaroSample> = callbackFlow {
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) { close(); return@callbackFlow }
        var filtered = Double.NaN
        // Ring buffer of (time, pressure) pairs spanning BARO_RATE_WINDOW_S for rate estimation.
        val times = ArrayDeque<Long>()
        val values = ArrayDeque<Double>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val p = e.values[0].toDouble()
                filtered = if (filtered.isNaN()) p else filtered + Parameters.BARO_LOWPASS_ALPHA * (p - filtered)
                val now = System.currentTimeMillis()
                times.addLast(now); values.addLast(filtered)
                while (times.isNotEmpty() && now - times.first() > Parameters.BARO_RATE_WINDOW_S * 1000) {
                    times.removeFirst(); values.removeFirst()
                }
                val dtMin = (now - times.first()) / 60_000.0
                val rate = if (dtMin > 0.1) (filtered - values.first()) / dtMin else 0.0
                trySend(BaroSample(now, filtered, rate))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sm.unregisterListener(listener) }
    }
}

class GyroSource(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val available: Boolean = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null &&
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /**
     * Yaw rate = gyro vector projected on the gravity direction (from a low-passed
     * accelerometer), so the phone may lie in any orientation on the tray table.
     */
    fun samples(): Flow<GyroSample> = callbackFlow {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null || accel == null) { close(); return@callbackFlow }
        val g = doubleArrayOf(0.0, 0.0, 9.81)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        for (i in 0..2) g[i] += 0.02 * (e.values[i] - g[i])
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                        if (norm < 1.0) return
                        // Positive rotation about "up" (against gravity) is a left turn in the
                        // right-handed sensor frame; negate to make right turns positive.
                        val yawRad = -(e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / norm
                        trySend(GyroSample(System.currentTimeMillis(), Math.toDegrees(yawRad)))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}

class AccelSource(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val available: Boolean = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** One MotionSample per second. */
    fun samples(): Flow<MotionSample> = callbackFlow {
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) { close(); return@callbackFlow }
        val g = doubleArrayOf(0.0, 0.0, 9.81)
        var lastMs = 0L
        var lastEmitMs = 0L
        // Window of horizontal acceleration vectors (in the phone frame, gravity removed).
        val win = ArrayDeque<DoubleArray>()
        val winT = ArrayDeque<Long>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                val dt = if (lastMs == 0L) 0.02 else ((now - lastMs) / 1000.0).coerceIn(0.001, 0.5)
                lastMs = now
                val a = 1.0 - Math.exp(-dt / Parameters.ACCEL_GRAVITY_TAU_S)
                for (i in 0..2) g[i] += a * (e.values[i] - g[i])
                val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (gn < 1.0) return
                // Linear acceleration, then remove the component along gravity.
                val lin = doubleArrayOf(e.values[0] - g[0], e.values[1] - g[1], e.values[2] - g[2])
                val dot = (lin[0] * g[0] + lin[1] * g[1] + lin[2] * g[2]) / (gn * gn)
                val h = doubleArrayOf(lin[0] - dot * g[0], lin[1] - dot * g[1], lin[2] - dot * g[2])
                win.addLast(h); winT.addLast(now)
                while (winT.isNotEmpty() && now - winT.first() > Parameters.ACCEL_WINDOW_S * 1000) { win.removeFirst(); winT.removeFirst() }
                if (now - lastEmitMs < 1000 || win.size < 10) return
                lastEmitMs = now
                // Mean vector and mean magnitude; direction stability = |mean| / mean|.| close to 1.
                var mx = 0.0; var my = 0.0; var mz = 0.0; var mag = 0.0
                for (v in win) { mx += v[0]; my += v[1]; mz += v[2]; mag += sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) }
                val n = win.size
                mx /= n; my /= n; mz /= n; mag /= n
                val meanVecMag = sqrt(mx * mx + my * my + mz * mz)
                val coherence = if (mag > 1e-6) meanVecMag / mag else 0.0
                // cos(std) ~ coherence for a narrow spread of directions.
                val stable = coherence > Math.cos(Math.toRadians(Parameters.ACCEL_DIRECTION_STD_DEG))
                trySend(MotionSample(now, meanVecMag, stable, (now - winT.first()) / 1000.0))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}

enum class FlightPhase { GROUND, TAKEOFF, CLIMB, CRUISE, DESCENT, LANDED }

/**
 * Flight phase state machine. Inputs are pushed as they arrive; `phase` is read
 * by the estimator. Takeoff time is captured on the GROUND -> TAKEOFF transition.
 */
class FlightPhaseDetector(initial: FlightPhase = FlightPhase.GROUND) {

    var phase: FlightPhase = initial
        private set
    var takeoffTimeMs: Long? = null
        private set

    private var baroRate = 0.0
    private var baroTimeMs = 0L
    private var gnssSpeed = 0.0
    private var gnssVRate = 0.0
    private var gnssGood = false
    private var gnssTimeMs = 0L
    private var stableSinceMs = 0L
    private var descentSinceMs = 0L
    private var accelSinceMs = 0L
    private var decelSinceMs = 0L

    /** Accelerometer: sustained, direction-stable horizontal acceleration. */
    fun onMotion(m: MotionSample) {
        val strong = m.directionStable && m.horizAccelMps2 >= Parameters.ACCEL_TAKEOFF_MPS2
        when (phase) {
            FlightPhase.GROUND -> {
                if (strong) {
                    if (accelSinceMs == 0L) accelSinceMs = m.timeMs
                    if (m.timeMs - accelSinceMs >= Parameters.ACCEL_TAKEOFF_S * 1000) {
                        // Roll started when the acceleration started, not when it was confirmed.
                        phase = FlightPhase.TAKEOFF
                        takeoffTimeMs = takeoffTimeMs ?: accelSinceMs
                        accelSinceMs = 0L
                    }
                } else accelSinceMs = 0L
            }
            FlightPhase.DESCENT -> {
                val decel = m.directionStable && m.horizAccelMps2 >= Parameters.ACCEL_LANDING_MPS2
                if (decel) {
                    if (decelSinceMs == 0L) decelSinceMs = m.timeMs
                    if (m.timeMs - decelSinceMs >= Parameters.ACCEL_LANDING_S * 1000) { phase = FlightPhase.LANDED; decelSinceMs = 0L }
                } else decelSinceMs = 0L
            }
            else -> { accelSinceMs = 0L; decelSinceMs = 0L }
        }
    }

    private var nearDescentSinceMs = 0L

    /**
     * Route context from the engine: close to the destination a sustained GNSS descent rate
     * (or entering the terminal area) means DESCENT even if the cabin-pressure signal is weak.
     */
    fun onRemaining(remainingM: Double, timeMs: Long) {
        if (phase != FlightPhase.CRUISE) { nearDescentSinceMs = 0L; return }
        val gnssFresh = gnssGood && timeMs - gnssTimeMs < 30_000
        if (remainingM < Parameters.TERMINAL_AREA_M) { phase = FlightPhase.DESCENT; return }
        if (remainingM < Parameters.DESCENT_NEAR_DEST_M && gnssFresh && gnssVRate < -2.0) {
            if (nearDescentSinceMs == 0L) nearDescentSinceMs = timeMs
            if (timeMs - nearDescentSinceMs > 60_000) phase = FlightPhase.DESCENT
        } else nearDescentSinceMs = 0L
    }

    /** User confirmed the aircraft is on the ground: back to GROUND, forget any takeoff. */
    fun confirmGround() { phase = FlightPhase.GROUND; takeoffTimeMs = null; stableSinceMs = 0L; descentSinceMs = 0L; accelSinceMs = 0L }

    fun onBaro(b: BaroSample) { baroRate = b.rateHpaPerMin; baroTimeMs = b.timeMs; step(b.timeMs) }

    fun onGnss(speedMps: Double, verticalRateMps: Double, good: Boolean, timeMs: Long) {
        gnssSpeed = speedMps; gnssVRate = verticalRateMps; gnssGood = good; gnssTimeMs = timeMs; step(timeMs)
    }

    /** Force a phase (e.g. restore after relaunch). */
    fun restore(p: FlightPhase, takeoffMs: Long?) { phase = p; takeoffTimeMs = takeoffMs }

    private var gnssAlt = Double.NaN
    private var destElevM = Double.NaN
    private var vrateUpSinceMs = 0L
    private var vrateDownSinceMs = 0L

    /** Destination field elevation and current GNSS altitude (from the engine) for landing detection. */
    fun onAltitudeContext(gnssAltM: Double?, destinationElevM: Double) { if (gnssAltM != null) gnssAlt = gnssAltM; destElevM = destinationElevM }

    /**
     * Phase logic. When GNSS with altitude is fresh it is the primary source (cabin pressure
     * steps during cruise - re-pressurisation, step climbs - produced false CLIMB/DESCENT in a
     * real flight log); the barometer decides only without GNSS.
     */
    private fun step(now: Long) {
        val baroFresh = now - baroTimeMs < 120_000
        val gnssFresh = gnssGood && now - gnssTimeMs < 30_000
        val fast = gnssFresh && gnssSpeed > Parameters.PHASE_TAKEOFF_SPEED_MPS
        val slow = gnssFresh && gnssSpeed < Parameters.PHASE_GROUND_SPEED_MPS
        val aboveDest = if (!gnssAlt.isNaN() && !destElevM.isNaN()) gnssAlt - destElevM else Double.NaN

        val climbing: Boolean
        val descending: Boolean
        if (gnssFresh) {
            // Sustained vertical rate, not a single sample.
            if (gnssVRate > Parameters.PHASE_GNSS_VRATE_CLIMB) { if (vrateUpSinceMs == 0L) vrateUpSinceMs = now } else vrateUpSinceMs = 0L
            if (gnssVRate < Parameters.PHASE_GNSS_VRATE_DESCENT) { if (vrateDownSinceMs == 0L) vrateDownSinceMs = now } else vrateDownSinceMs = 0L
            climbing = vrateUpSinceMs != 0L && now - vrateUpSinceMs > Parameters.PHASE_GNSS_CONFIRM_S * 1000
            descending = vrateDownSinceMs != 0L && now - vrateDownSinceMs > Parameters.PHASE_GNSS_CONFIRM_S * 1000
        } else {
            vrateUpSinceMs = 0L; vrateDownSinceMs = 0L
            climbing = baroFresh && baroRate < Parameters.PHASE_CLIMB_BARO_RATE
            descending = baroFresh && baroRate > Parameters.PHASE_DESCENT_BARO_RATE
        }
        val level = !climbing && !descending
        val nearGround = !aboveDest.isNaN() && aboveDest < Parameters.PHASE_LANDED_ALT_ABOVE_M
        val lowAlt = !aboveDest.isNaN() && aboveDest < Parameters.PHASE_LOW_ALT_LOCK_M

        when (phase) {
            FlightPhase.GROUND, FlightPhase.LANDED -> if (fast || (!gnssFresh && baroFresh && climbing)) {
                phase = FlightPhase.TAKEOFF; takeoffTimeMs = takeoffTimeMs ?: now; stableSinceMs = 0L
            }
            FlightPhase.TAKEOFF -> if (climbing || fast) phase = FlightPhase.CLIMB
            FlightPhase.CLIMB -> {
                if (level) {
                    if (stableSinceMs == 0L) stableSinceMs = now
                    if (now - stableSinceMs > Parameters.PHASE_CRUISE_STABLE_S * 1000) { phase = FlightPhase.CRUISE; descentSinceMs = 0L }
                } else stableSinceMs = 0L
                if (descending && gnssFresh) { phase = FlightPhase.DESCENT }
            }
            FlightPhase.CRUISE -> {
                if (descending) {
                    if (descentSinceMs == 0L) descentSinceMs = now
                    if (gnssFresh || now - descentSinceMs > Parameters.PHASE_DESCENT_CONFIRM_S * 1000) phase = FlightPhase.DESCENT
                } else descentSinceMs = 0L
            }
            FlightPhase.DESCENT -> {
                if (slow && (nearGround || abs(baroRate) < Parameters.PHASE_DESCENT_BARO_RATE)) phase = FlightPhase.LANDED
                else if (climbing && !lowAlt) { phase = FlightPhase.CLIMB; stableSinceMs = 0L }
            }
        }
    }
}
