// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - SkyTrackApp
// Version 2.1
// Purpose : Application entry point; owns the singletons (manual DI, no
//           framework) and initialises MapLibre. Resumes the previous
//           flight automatically so the map is populated on relaunch.
// =============================================================
package org.skytrack

import android.app.Application
import org.maplibre.android.MapLibre
import org.skytrack.data.AirportRepository
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

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        airports = AirportRepository(this)
        stores = Stores(this)
        engine = FlightEngine(airports, stores, FlightLogger(this))
        stores.plan.value?.let { engine.start(it) }
    }
}
