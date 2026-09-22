package android.util
object Log { fun getStackTraceString(e: Throwable): String = e.toString(); fun w(t: String, m: String): Int = 0; fun e(t: String, m: String): Int = 0 }
