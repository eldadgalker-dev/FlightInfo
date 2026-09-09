// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - AerialPack
// Version 1.0
// Purpose : Optional NASA Blue Marble raster pack (public domain) as an
//           MBTiles file, downloaded ONCE (at home, on Wi-Fi) from the
//           project's GitHub Releases and stored in the app's files
//           directory. In flight it is read locally; no network is used.
//           Download is the only network access in the whole application.
// =============================================================
package org.skytrack.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skytrack.Parameters
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AerialPack(context: Context) {

    val file: File = File(context.getExternalFilesDir(null) ?: context.filesDir, Parameters.AERIAL_PACK_FILE)
    private val tmp: File = File(file.parentFile, Parameters.AERIAL_PACK_FILE + ".part")

    val installed: Boolean get() = file.isFile && file.length() > 1_000_000L

    val sizeMb: Int get() = if (installed) (file.length() / (1024 * 1024)).toInt() else 0

    /** MapLibre tile URL for the local MBTiles file. */
    val tileUrl: String get() = "mbtiles://" + file.absolutePath

    /**
     * Download to a temporary file, then rename atomically. Progress is 0..100,
     * or -1 while the total size is unknown. Returns null on success or an
     * error message.
     */
    suspend fun download(onProgress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            var url = URL(Parameters.AERIAL_PACK_URL)
            // Follow up to 5 redirects manually (GitHub Releases redirect to a CDN).
            var hops = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000; readTimeout = 60_000
                }
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    val loc = conn.getHeaderField("Location") ?: return@withContext "redirect without location"
                    url = URL(url, loc); hops++; conn.disconnect(); continue
                }
                if (code != 200) return@withContext "HTTP $code"
                break
            }
            val c = conn!!
            val total = c.contentLengthLong
            tmp.parentFile?.mkdirs()
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && tmp.length() != total) { tmp.delete(); return@withContext "incomplete (${tmp.length()} of $total bytes)" }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) return@withContext "could not save file"
            null
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }

    fun delete() { file.delete(); tmp.delete() }
}
