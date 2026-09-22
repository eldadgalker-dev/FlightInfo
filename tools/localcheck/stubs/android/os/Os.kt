package android.os
object Build { const val MANUFACTURER = "x"; const val MODEL = "y"; object VERSION { const val RELEASE = "14"; const val SDK_INT = 34 } object VERSION_CODES { const val Q = 29; const val TIRAMISU = 33 } }
class Bundle
class Looper { companion object { fun getMainLooper(): Looper = Looper() } }
class Handler(l: Looper) { fun postDelayed(r: Runnable, ms: Long): Boolean = true; fun post(r: Runnable): Boolean = true }
class HandlerThread(n: String) { fun start() {}; val looper: Looper = Looper(); fun quitSafely(): Boolean = true }
class BatteryManager { fun getIntProperty(p: Int): Int = 0; val isCharging: Boolean = false; companion object { const val BATTERY_PROPERTY_CAPACITY = 4; const val BATTERY_PROPERTY_CURRENT_NOW = 2 } }
object Debug { fun getNativeHeapAllocatedSize(): Long = 0L }
object Process { fun getElapsedCpuTime(): Long = 0L; fun getStartElapsedRealtime(): Long = 0L; fun myPid(): Int = 0; fun killProcess(p: Int) {} }
object SystemClock { fun elapsedRealtime(): Long = 0L }
