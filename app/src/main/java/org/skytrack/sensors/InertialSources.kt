// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - InertialSources
// Version 1.1
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

    fun onBaro(b: BaroSample) { baroRate = b.rateHpaPerMin; baroTimeMs = b.timeMs; step(b.timeMs) }

    fun onGnss(speedMps: Double, verticalRateMps: Double, good: Boolean, timeMs: Long) {
        gnssSpeed = speedMps; gnssVRate = verticalRateMps; gnssGood = good; gnssTimeMs = timeMs; step(timeMs)
    }

    /** Force a phase (e.g. restore after relaunch). */
    fun restore(p: FlightPhase, takeoffMs: Long?) { phase = p; takeoffTimeMs = takeoffMs }

    private fun step(now: Long) {
        val baroFresh = now - baroTimeMs < 120_000
        val gnssFresh = gnssGood && now - gnssTimeMs < 30_000
        val climbing = (baroFresh && baroRate < Parameters.PHASE_CLIMB_BARO_RATE) ||
                (gnssFresh && gnssVRate > Parameters.PHASE_GNSS_VRATE_CLIMB)
        val descending = (baroFresh && baroRate > Parameters.PHASE_DESCENT_BARO_RATE) ||
                (gnssFresh && gnssVRate < Parameters.PHASE_GNSS_VRATE_DESCENT)
        val level = !climbing && !descending
        val fast = gnssFresh && gnssSpeed > Parameters.PHASE_TAKEOFF_SPEED_MPS
        val slow = gnssFresh && gnssSpeed < Parameters.PHASE_GROUND_SPEED_MPS

        when (phase) {
            FlightPhase.GROUND, FlightPhase.LANDED -> if (fast || (baroFresh && climbing && !slow)) {
                phase = FlightPhase.TAKEOFF; takeoffTimeMs = takeoffTimeMs ?: now; stableSinceMs = 0L
            }
            FlightPhase.TAKEOFF -> if (climbing || fast) phase = FlightPhase.CLIMB
            FlightPhase.CLIMB -> {
                if (level) {
                    if (stableSinceMs == 0L) stableSinceMs = now
                    if (now - stableSinceMs > Parameters.PHASE_CRUISE_STABLE_S * 1000) { phase = FlightPhase.CRUISE; descentSinceMs = 0L }
                } else stableSinceMs = 0L
            }
            FlightPhase.CRUISE -> {
                if (descending) {
                    if (descentSinceMs == 0L) descentSinceMs = now
                    if (now - descentSinceMs > Parameters.PHASE_DESCENT_CONFIRM_S * 1000) phase = FlightPhase.DESCENT
                } else descentSinceMs = 0L
            }
            FlightPhase.DESCENT -> {
                if (slow && abs(baroRate) < Parameters.PHASE_DESCENT_BARO_RATE) phase = FlightPhase.LANDED
                else if (climbing) { phase = FlightPhase.CLIMB; stableSinceMs = 0L }
            }
        }
    }
}
