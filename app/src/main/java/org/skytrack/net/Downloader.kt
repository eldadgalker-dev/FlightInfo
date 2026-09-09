// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Downloader
// Version 1.0
// Purpose : Minimal HTTP client used only for the two deliberate, user-
//           initiated downloads (aerial pack, app update) and for the
//           update check. Follows redirects manually (GitHub -> CDN), writes
//           to a temporary file and renames atomically. No third-party
//           networking library.
// =============================================================
package org.skytrack.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "FlightInfo-Android"

    /** Open a connection following redirects; caller must disconnect. Throws on non-200. */
    private fun open(startUrl: String, accept: String? = null): HttpURLConnection {
        var url = URL(startUrl)
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                if (accept != null) setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < MAX_REDIRECTS) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                url = URL(url, loc); hops++; conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
            return conn
        }
    }

    /** GET a small text/JSON resource. Returns the body or throws. */
    suspend fun getText(url: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        val conn = open(url, accept)
        try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    /**
     * Download to `dest` via `dest.part`. Progress 0..100, or -1 when the size is
     * unknown. Returns null on success or an error message.
     */
    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = open(url)
            val total = conn.contentLengthLong
            tmp.parentFile?.mkdirs()
            conn.inputStream.use { input ->
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
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return@withContext "could not save file"
            null
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }
}
