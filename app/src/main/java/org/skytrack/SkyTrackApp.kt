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

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        airports = AirportRepository(this)
        stores = Stores(this)
        geo = GeoData(this)
        engine = FlightEngine(airports, stores, FlightLogger(this), geo, hebrew, ::isOnline)
        stores.plan.value?.let { engine.start(it) }
    }
}
