// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - ResourceMonitor
// Version 1.0
// Purpose : Snapshot of what the app costs the phone: Java + native heap,
//           process CPU time as a share of uptime, disk used by the app's
//           files (logs, imagery), and the device battery state. Battery
//           attribution per app is not available to apps without special
//           permissions, so the device level is shown with that caveat.
// =============================================================
package org.skytrack.data

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

data class ResourceSnapshot(
    val javaHeapMb: Double,
    val nativeHeapMb: Double,
    val cpuPercentSinceStart: Double,   // process CPU time / process uptime
    val cpuPercentRecent: Double?,      // since the previous snapshot, null on the first call
    val filesMb: Double,                // app files dir (logs, track, updates)
    val externalMb: Double,             // external files dir (imagery pack, logs)
    val batteryPercent: Int?,
    val charging: Boolean?,
    val currentMa: Int?                 // instantaneous device current, negative = discharging (device-wide, not app-only)
)

class ResourceMonitor(private val context: Context) {

    private var lastCpuMs = 0L
    private var lastWallMs = 0L

    fun snapshot(): ResourceSnapshot {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576.0

        val cpuMs = Process.getElapsedCpuTime()
        val uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val cpuSince = if (uptimeMs > 0) 100.0 * cpuMs / uptimeMs else 0.0
        val now = SystemClock.elapsedRealtime()
        val recent = if (lastWallMs != 0L && now > lastWallMs) 100.0 * (cpuMs - lastCpuMs) / (now - lastWallMs) else null
        lastCpuMs = cpuMs; lastWallMs = now

        val files = dirSize(context.filesDir) / 1_048_576.0
        val ext = (context.getExternalFilesDir(null)?.let { dirSize(it) } ?: 0L) / 1_048_576.0

        var level: Int? = null; var charging: Boolean? = null; var current: Int? = null
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            charging = bm.isCharging
            current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { it / 1000 }
        } catch (e: Exception) { }

        return ResourceSnapshot(javaMb, nativeMb, cpuSince, recent, files, ext, level, charging, current)
    }

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)
}
