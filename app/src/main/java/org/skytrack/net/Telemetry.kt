// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Telemetry
// Version 1.1
// Purpose : Tester-programme events (register / install / update / unregister)
//           sent to the project's telemetry endpoint. Strictly opt-in: nothing
//           is sent unless the user joined the programme in Settings and the
//           endpoint URL is configured. Payload: app version, device model,
//           Android version, the tester id the app generated, the nickname
//           and (optional) e-mail the user typed. The server adds time, IP
//           and country on its side.
// =============================================================
package org.skytrack.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.skytrack.Parameters
import org.skytrack.data.Stores
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class Telemetry(private val context: Context, private val stores: Stores) {

    private val scope = CoroutineScope(Dispatchers.IO)

    val enabled: Boolean get() = Parameters.TELEMETRY_URL.isNotBlank()

    /** Join: store consent + identity, send "register". */
    fun join(nickname: String, email: String) {
        val id = stores.testerId ?: UUID.randomUUID().toString().also { stores.testerId = it }
        stores.testerNickname = nickname.trim(); stores.testerEmail = email.trim(); stores.testerConsent = true
        stores.testerLastReportedVersion = currentVersion()
        send("register", id)
    }

    /** Leave: send "unregister", then forget consent (identity kept so a re-join is the same tester). */
    fun leave() {
        val id = stores.testerId
        if (id != null && stores.testerConsent) send("unregister", id)
        stores.testerConsent = false
    }

    /** On every launch: report install (first time) or update (version changed) - only with consent. */
    fun reportLaunchIfNeeded() {
        if (!stores.testerConsent) return
        val id = stores.testerId ?: return
        val ver = currentVersion()
        val last = stores.testerLastReportedVersion
        if (last == ver) return
        stores.testerLastReportedVersion = ver
        send(if (last.isNullOrEmpty()) "install" else "update", id)
    }

    private fun currentVersion(): String = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private val isGoogleForm: Boolean get() = Parameters.TELEMETRY_URL.contains("docs.google.com/forms")

    private fun send(event: String, testerId: String) {
        if (!enabled) return
        val fields = linkedMapOf(
            "event" to event, "testerId" to testerId, "nickname" to (stores.testerNickname ?: ""), "email" to (stores.testerEmail ?: ""),
            "version" to currentVersion(), "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android" to android.os.Build.VERSION.RELEASE, "platform" to "android", "tz" to java.util.TimeZone.getDefault().id
        )
        scope.launch {
            try {
                // Public IP of this device (the form cannot see it by itself); best effort, 4 s.
                fields["ip"] = try { URL(Parameters.IP_ECHO_URL).openConnection().let { it.connectTimeout = 4000; it.readTimeout = 4000; it.getInputStream().bufferedReader().readText().trim().take(45) } } catch (e: Exception) { "" }
                if (isGoogleForm) {
                    val form = fields.filterKeys { Parameters.TELEMETRY_FORM_FIELDS.containsKey(it) }
                        .map { (k, v) -> java.net.URLEncoder.encode(Parameters.TELEMETRY_FORM_FIELDS[k], "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
                        .joinToString("&")
                    val c = URL(Parameters.TELEMETRY_URL).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true; c.instanceFollowRedirects = false
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(form.toByteArray()) }
                    c.responseCode; c.disconnect()
                } else {
                    val body = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) }; put("consent", true) }.toString()
                    val c = URL(Parameters.TELEMETRY_URL.trimEnd('/') + "/event").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    c.responseCode; c.disconnect()
                }
            } catch (e: Exception) { /* offline or endpoint down: the next launch retries the version report */
                if (event == "install" || event == "update") stores.testerLastReportedVersion = null
            }
        }
    }
}
