package de.nibsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.nibsync.sync.client.SyncController
import de.nibsync.sync.client.SyncService

object SettingsTags {
    const val SCREEN = "settings"
    const val CLOSE = "settings-close"
}

/** App settings, grouped in sections; new settings get their own section here. */
@Composable
fun SettingsDialog(
    sync: SyncController,
    strings: Strings,
    openBrowser: (String) -> Boolean,
    connect: (SyncService) -> (() -> Unit)?,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(min = 420.dp, max = 600.dp).testTag(SettingsTags.SCREEN)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.settings, style = MaterialTheme.typography.headlineSmall)
                Text(strings.settingsSync, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SyncSettings(sync, strings, openBrowser, connect)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) { Text(strings.close) }
                }
            }
        }
    }
}
