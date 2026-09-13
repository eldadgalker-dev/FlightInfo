// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - HelpScreen
// Version 2.1
// Purpose : In-app manual. Shows the app version in the title and lets the
//           reader switch the help language (English default, Hebrew)
//           independently of the app language, by resolving the strings
//           through a locale-specific configuration context.
// =============================================================
package org.skytrack.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.skytrack.R
import java.util.Locale

/** Resources resolved for a specific language, independent of the app locale. */
private fun localized(context: Context, lang: String): Context {
    val cfg = Configuration(context.resources.configuration)
    cfg.setLocale(Locale(lang))
    return context.createConfigurationContext(cfg)
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lang by rememberSaveable { mutableStateOf("en") }          // English default, as requested
    val lc = remember(lang) { localized(context, lang) }
    val version = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" } }
    val sections = listOf(
        R.string.help_what_title to R.string.help_what_body,
        R.string.help_setup_title to R.string.help_setup_body,
        R.string.help_map_title to R.string.help_map_body,
        R.string.help_aerial_title to R.string.help_aerial_body,
        R.string.help_lines_title to R.string.help_lines_body,
        R.string.help_modes_title to R.string.help_modes_body,
        R.string.help_confidence_title to R.string.help_confidence_body,
        R.string.help_deviation_title to R.string.help_deviation_body,
        R.string.help_estimate_title to R.string.help_estimate_body,
        R.string.help_gnss_title to R.string.help_gnss_body,
        R.string.help_values_title to R.string.help_values_body,
        R.string.help_terms_title to R.string.help_terms_body,
        R.string.help_logs_title to R.string.help_logs_body,
        R.string.help_limits_title to R.string.help_limits_body
    )
    val dir = if (lang == "he") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(lc.getString(R.string.help), style = MaterialTheme.typography.headlineSmall)
                    Text("FlightInfo $version  \u00B7  ${org.skytrack.BuildConfig.BUILD_DATE}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = lang == "en", onClick = { lang = "en" }, label = { Text("English") })
                FilterChip(selected = lang == "he", onClick = { lang = "he" }, label = { Text("\u05E2\u05D1\u05E8\u05D9\u05EA") })
            }
            for ((title, body) in sections) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(lc.getString(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(lc.getString(body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
