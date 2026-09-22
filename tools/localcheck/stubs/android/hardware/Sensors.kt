package android.hardware
class Sensor { val type: Int = 0; companion object { const val TYPE_PRESSURE = 6; const val TYPE_GYROSCOPE = 4; const val TYPE_ACCELEROMETER = 1 } }
class SensorEvent { val values: FloatArray = FloatArray(3); val sensor: Sensor = Sensor() }
interface SensorEventListener { fun onSensorChanged(e: SensorEvent); fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) }
class SensorManager { fun getDefaultSensor(t: Int): Sensor? = null; fun registerListener(l: SensorEventListener, s: Sensor, d: Int): Boolean = true; fun unregisterListener(l: SensorEventListener) {}
  companion object { const val SENSOR_DELAY_NORMAL = 3; const val SENSOR_DELAY_GAME = 1 } }
