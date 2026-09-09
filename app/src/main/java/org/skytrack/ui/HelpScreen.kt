// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - HelpScreen
// Version 1.1
// Purpose : In-app manual. Plain scrollable sections, all text from string
//           resources (English default, Hebrew in values-iw).
// =============================================================
package org.skytrack.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skytrack.R

@Composable
fun HelpScreen(onBack: () -> Unit) {
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
        R.string.help_logs_title to R.string.help_logs_body,
        R.string.help_limits_title to R.string.help_limits_body
    )
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.help), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        for ((title, body) in sections) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
