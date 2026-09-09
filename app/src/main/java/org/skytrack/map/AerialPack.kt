// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - AerialPack
// Version 1.1
// Purpose : Optional NASA Blue Marble raster pack (public domain) as an
//           MBTiles file, downloaded ONCE (at home, on Wi-Fi) from the
//           project's GitHub Releases and stored in the app's files
//           directory. In flight it is read locally; no network is used.
//           Download is the only network access in the whole application.
// =============================================================
package org.skytrack.map

import android.content.Context
import org.skytrack.Parameters
import org.skytrack.net.Downloader
import java.io.File

class AerialPack(context: Context) {

    val file: File = File(context.getExternalFilesDir(null) ?: context.filesDir, Parameters.AERIAL_PACK_FILE)
    private val tmp: File = File(file.parentFile, Parameters.AERIAL_PACK_FILE + ".part")   // Downloader's temporary name

    val installed: Boolean get() = file.isFile && file.length() > 1_000_000L

    val sizeMb: Int get() = if (installed) (file.length() / (1024 * 1024)).toInt() else 0

    /** MapLibre tile URL for the local MBTiles file. */
    val tileUrl: String get() = "mbtiles://" + file.absolutePath

    /** Download the pack. Progress 0..100 or -1 while the size is unknown. Returns null on success or an error message. */
    suspend fun download(onProgress: (Int) -> Unit): String? = Downloader.download(Parameters.AERIAL_PACK_URL, file, onProgress)

    fun delete() { file.delete(); tmp.delete() }
}
