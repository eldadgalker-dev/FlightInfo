// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - TrackingService
// Version 1.1
// Purpose : Foreground service (type location) that keeps GNSS, barometer
//           and gyro flowing into the FlightEngine while the screen is off,
//           and shows remaining distance / ETE in a persistent notification.
// =============================================================
package org.skytrack.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.skytrack.MainActivity
import org.skytrack.Parameters
import org.skytrack.R
import org.skytrack.SkyTrackApp
import org.skytrack.sensors.BaroSource
import org.skytrack.sensors.GnssSource
import org.skytrack.sensors.GyroSource
import org.skytrack.ui.Format

@OptIn(kotlinx.coroutines.FlowPreview::class)
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val app = application as SkyTrackApp
        val engine = app.engine

        startForegroundCompat(buildNotification(getString(R.string.notif_starting)))

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                GnssSource(this@TrackingService).samples()
                    .catch { }
                    .collect { engine.onGnss(it) }
            }
        }
        scope.launch { BaroSource(this@TrackingService).samples().catch { }.collect { engine.onBaro(it) } }
        scope.launch { GyroSource(this@TrackingService).samples().catch { }.collect { engine.onGyro(it) } }

        // Notification refresh once every 30 s.
        scope.launch {
            engine.metrics.sample(30_000).collectLatest { m ->
                if (m != null) {
                    val s = app.stores.settings.value
                    val text = getString(R.string.notif_body,
                        Format.distance(m.remainingM, s.distanceUnit),
                        Format.duration(m.eteS))
                    notificationManager().notify(Parameters.NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Parameters.NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Parameters.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plane)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(Parameters.NOTIFICATION_CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        notificationManager().createNotificationChannel(ch)
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_STOP = "org.skytrack.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
