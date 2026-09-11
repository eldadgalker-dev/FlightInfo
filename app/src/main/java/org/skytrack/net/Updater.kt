// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Updater
// Version 1.3
// Purpose : Manual update check against the project's GitHub Releases
//           (public REST API, no token), download of the APK asset and
//           hand-off to the Android package installer. Nothing runs
//           automatically: the user presses "check" and "install".
//           Requires REQUEST_INSTALL_PACKAGES (manifest) and, once, the
//           user's consent to install from this app (system dialog).
// =============================================================
package org.skytrack.net

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.skytrack.Parameters
import java.io.File

data class UpdateInfo(val latestVersion: String, val currentVersion: String, val apkUrl: String?, val notes: String) {
    val isNewer: Boolean get() = compareVersions(latestVersion, currentVersion) > 0

    companion object {
        /** Compare dotted numeric versions ("2.10" > "2.3"). Non-numeric parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            // "3.0-beta2" -> numeric core 3.0 plus pre-release number 2; a final release ("3.0") ranks above any beta.
            fun parts(v: String): List<Int> {
                val core = v.trimStart('v', 'V').substringBefore('-')
                val pre = v.substringAfter('-', "").filter(Char::isDigit).toIntOrNull()
                return core.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 } + listOf(if (pre == null) Int.MAX_VALUE else pre)
            }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}

class Updater(private val context: Context) {

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")

    fun currentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** Query the latest release. Throws on network / parse errors. */
    suspend fun check(): UpdateInfo {
        // /releases (not /releases/latest) so that beta pre-releases are offered to testers too.
        val url = "https://api.github.com/repos/${Parameters.UPDATE_REPO_OWNER}/${Parameters.UPDATE_REPO_NAME}/releases?per_page=10"
        val arr = org.json.JSONArray(Downloader.getText(url, "application/vnd.github+json"))
        var json: JSONObject? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft", false)) continue
            if (!r.optString("tag_name", "").startsWith("v")) continue          // skip data packs (data-v1)
            json = r; break
        }
        json ?: throw IllegalStateException("no release found")
        val tag = json.optString("tag_name", "")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name == Parameters.UPDATE_ASSET_NAME) { apkUrl = a.optString("browser_download_url"); break }
                if (apkUrl == null && name.endsWith(".apk")) apkUrl = a.optString("browser_download_url")
            }
        }
        return UpdateInfo(latestVersion = tag.trimStart('v', 'V'), currentVersion = currentVersion(),
            apkUrl = apkUrl, notes = json.optString("body", ""))
    }

    /** Download the APK; returns the file or throws with a message. */
    suspend fun download(apkUrl: String, onProgress: (Int) -> Unit): File {
        dir.mkdirs()
        val f = File(dir, Parameters.UPDATE_ASSET_NAME)
        val err = Downloader.download(apkUrl, f, onProgress)
        if (err != null) throw IllegalStateException(err)
        return f
    }

    /**
     * True if the APK is signed with the same certificate as the installed app,
     * false if not (Android would refuse the update), null if it cannot be determined.
     */
    fun signatureMatches(apk: File): Boolean? = try {
        if (android.os.Build.VERSION.SDK_INT < 28) throw UnsupportedOperationException("signingInfo needs API 28")
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)?.signingInfo?.apkContentsSigners
        if (installed == null || archive == null) null
        else installed.map { it.toCharsString() }.toSet() == archive.map { it.toCharsString() }.toSet()
    } catch (e: Exception) { null }

    /** Hand the downloaded APK to the system installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun cleanup() { dir.listFiles()?.forEach { it.delete() } }
}
