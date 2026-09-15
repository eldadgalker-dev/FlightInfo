// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - SkyTrackApp
// Version 3.0
// Purpose : Application entry point; owns the singletons (manual DI, no
//           framework) and initialises MapLibre. Resumes the previous
//           flight automatically so the map is populated on relaunch.
// =============================================================
package org.skytrack

import android.app.Application
import org.maplibre.android.MapLibre
import org.skytrack.data.AirportRepository
import org.skytrack.data.GeoData
import org.skytrack.data.Stores
import org.skytrack.service.FlightEngine
import org.skytrack.service.FlightLogger

class SkyTrackApp : Application() {

    lateinit var airports: AirportRepository
        private set
    lateinit var stores: Stores
        private set
    lateinit var engine: FlightEngine
        private set
    lateinit var geo: GeoData
        private set

    val hebrew: Boolean
        get() { val l = resources.configuration.locales[0].language; return l == "he" || l == "iw" }

    /** Result of the automatic update check (only when online and allowed), for the UI to show. */
    val updateInfo = kotlinx.coroutines.flow.MutableStateFlow<org.skytrack.net.UpdateInfo?>(null)

    /** True when a validated internet connection exists right now. */
    fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) { false }
    }

    val telemetry by lazy { org.skytrack.net.Telemetry(this, stores) }

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        MapLibre.getInstance(this)
        airports = AirportRepository(this)
        stores = Stores(this)
        geo = GeoData(this)
        engine = FlightEngine(airports, stores, FlightLogger(this), geo, hebrew, ::isOnline)
        // Restoring a saved flight and the tester report are non-essential: a failure must not stop the launch.
        try { stores.plan.value?.let { engine.start(it) } } catch (e: Exception) { recordNonFatal("restore flight", e) }
        try { telemetry.reportLaunchIfNeeded() } catch (e: Exception) { recordNonFatal("telemetry", e) }
    }

    /**
     * Crash recorder: an uncaught exception is written to logs/crash_<time>.txt and to the
     * preferences, so the next launch can show it and offer to send it (Settings > Report).
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = crashText(e)
                getSharedPreferences("skytrack", MODE_PRIVATE).edit().putString("last_crash", text.take(6000)).apply()
                val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "logs"); dir.mkdirs()
                java.io.File(dir, "crash_" + System.currentTimeMillis() + ".txt").writeText(text)
            } catch (ignored: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    private fun recordNonFatal(where: String, e: Exception) {
        try { getSharedPreferences("skytrack", MODE_PRIVATE).edit().putString("last_crash", "[non-fatal: $where]\n" + crashText(e).take(5000)).apply() } catch (ignored: Exception) { }
    }

    private fun crashText(e: Throwable): String {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (x: Exception) { "?" }
        return "FlightInfo $ver | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE}\n" +
                java.time.Instant.now().toString() + "\n\n" + android.util.Log.getStackTraceString(e)
    }
}
