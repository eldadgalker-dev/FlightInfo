package android.location
class Location(provider: String = "gps") { var latitude = 0.0; var longitude = 0.0; var time = 0L; val altitude = 0.0; val speed = 0f; val bearing = 0f; val accuracy = 0f; val verticalAccuracyMeters = 0f; val provider: String = provider
  fun hasAccuracy() = true; fun hasVerticalAccuracy() = true; fun hasAltitude() = true; fun hasSpeed() = true; fun hasBearing() = true }
open class GnssStatus { val satelliteCount = 0; fun usedInFix(i: Int) = false; abstract class Callback { open fun onSatelliteStatusChanged(status: GnssStatus) {} } }
interface LocationListener { fun onLocationChanged(loc: Location); fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}; fun onProviderEnabled(provider: String) {}; fun onProviderDisabled(provider: String) {} }
class LocationManager { fun registerGnssStatusCallback(c: GnssStatus.Callback, h: android.os.Handler): Boolean = true; fun unregisterGnssStatusCallback(c: GnssStatus.Callback) {}
  fun requestLocationUpdates(p: String, t: Long, d: Float, l: LocationListener, lo: android.os.Looper) {}; fun removeUpdates(l: LocationListener) {}; fun getLastKnownLocation(p: String): Location? = null
  val allProviders: List<String> = listOf("gps"); companion object { const val GPS_PROVIDER = "gps"; const val NETWORK_PROVIDER = "network"; const val FUSED_PROVIDER = "fused" } }
