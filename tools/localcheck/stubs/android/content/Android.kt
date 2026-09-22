package android.content
class AssetManager { fun open(name: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0)) }
interface SharedPreferences { fun getString(k: String, d: String?): String?; fun getBoolean(k: String, d: Boolean): Boolean; fun getLong(k: String, d: Long): Long; fun getFloat(k: String, d: Float): Float; fun getInt(k: String, d: Int): Int; fun contains(k: String): Boolean; fun edit(): Editor
  interface Editor { fun putString(k: String, v: String?): Editor; fun putBoolean(k: String, v: Boolean): Editor; fun putLong(k: String, v: Long): Editor; fun putFloat(k: String, v: Float): Editor; fun putInt(k: String, v: Int): Editor; fun remove(k: String): Editor; fun apply() } }
open class Context { val MODE_PRIVATE = 0; val assets: AssetManager = AssetManager(); val filesDir: java.io.File = java.io.File("/tmp"); fun getExternalFilesDir(t: String?): java.io.File? = null
  fun getSharedPreferences(n: String, m: Int): SharedPreferences = throw UnsupportedOperationException(); val packageManager: android.content.pm.PackageManager = android.content.pm.PackageManager(); val packageName: String = "x"
  fun getSystemService(n: String): Any? = null; fun startActivity(i: Intent) {}; val resources: android.content.res.Resources = android.content.res.Resources(); val applicationContext: Context get() = this
  val contentResolver: ContentResolver = ContentResolver()
  companion object { const val MODE_PRIVATE = 0; const val SENSOR_SERVICE = "s"; const val LOCATION_SERVICE = "l"; const val CONNECTIVITY_SERVICE = "c"; const val NOTIFICATION_SERVICE = "n"; const val BATTERY_SERVICE = "b" } }
class ContentResolver
class Intent(a: String, u: android.net.Uri? = null) { var type: String? = null; fun putExtra(k: String, v: String): Intent = this; fun putExtra(k: String, v: Array<String>): Intent = this; fun putExtra(k: String, v: android.net.Uri): Intent = this
  fun putParcelableArrayListExtra(k: String, v: ArrayList<android.net.Uri>): Intent = this; fun setDataAndType(u: android.net.Uri, t: String): Intent = this; fun addFlags(f: Int): Intent = this
  companion object { fun createChooser(i: Intent, t: String): Intent = i; const val ACTION_SEND = "s"; const val ACTION_SEND_MULTIPLE = "sm"; const val EXTRA_EMAIL = "e"; const val EXTRA_SUBJECT = "sub"; const val EXTRA_TEXT = "t"; const val EXTRA_STREAM = "st"; const val ACTION_VIEW = "v"; const val FLAG_ACTIVITY_NEW_TASK = 1; const val FLAG_GRANT_READ_URI_PERMISSION = 2 } }
