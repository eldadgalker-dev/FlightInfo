// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Feedback
// Version 1.0
// Purpose : Build the two zero-cost feedback channels from inside the app:
//           an e-mail (any mail app, optional attached flight log and map
//           snapshot) and a pre-filled GitHub issue opened in the browser.
//           Device and version information is appended automatically.
// =============================================================
package org.skytrack.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.skytrack.Parameters
import java.io.File
import java.net.URLEncoder

object Feedback {

    enum class Kind { BUG, IMPROVEMENT, FLIGHT_LOG }

    fun deviceInfo(context: Context): String {
        val ver = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }
        return "FlightInfo $ver | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
    }

    private fun subject(kind: Kind, title: String): String {
        val tag = when (kind) { Kind.BUG -> "[bug]"; Kind.IMPROVEMENT -> "[improvement]"; Kind.FLIGHT_LOG -> "[flight log]" }
        return "FlightInfo $tag ${title.trim().take(80)}"
    }

    /** E-mail with optional attachments; returns false if no app can handle it. */
    fun sendEmail(context: Context, kind: Kind, title: String, body: String, attachments: List<File>): Boolean {
        val text = body.trim() + "\n\n---\n" + deviceInfo(context)
        val uris = ArrayList<Uri>()
        for (f in attachments) if (f.exists()) uris.add(FileProvider.getUriForFile(context, context.packageName + ".files", f))
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Parameters.FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject(kind, title))
            putExtra(Intent.EXTRA_TEXT, text)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try { context.startActivity(Intent.createChooser(intent, subject(kind, title))); true } catch (e: Exception) { false }
    }

    /** Open a pre-filled GitHub issue in the browser (attachments cannot be pre-filled; the text asks to add the log). */
    fun openGithubIssue(context: Context, kind: Kind, title: String, body: String): Boolean {
        val label = when (kind) { Kind.BUG -> "bug"; Kind.IMPROVEMENT -> "enhancement"; Kind.FLIGHT_LOG -> "flight-log" }
        val text = body.trim() + "\n\n---\n" + deviceInfo(context) +
                (if (kind == Kind.FLIGHT_LOG) "\n\n(Attach the CSV flight log from Settings > Flight logs > Share.)" else "")
        val url = "https://github.com/${Parameters.UPDATE_REPO_OWNER}/${Parameters.UPDATE_REPO_NAME}/issues/new" +
                "?title=" + URLEncoder.encode(subject(kind, title), "UTF-8") +
                "&labels=" + label +
                "&body=" + URLEncoder.encode(text, "UTF-8")
        return try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }
}
