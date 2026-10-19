package com.stemaker.nibsync.desktop

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncService

object SettingsTags {
    const val SCREEN = "settings"
    const val CLOSE = "settings-close"
    const val SHOW_TITLE = "settings-show-title"
    const val SHOW_CREATED = "settings-show-created"
}

/** Display choices of the settings dialog. */
class DisplaySettings(
    val showTitle: Boolean,
    val onShowTitle: (Boolean) -> Unit,
    val showCreated: Boolean,
    val onShowCreated: (Boolean) -> Unit,
)

/** App settings, grouped in sections; new settings get their own section here. */
@Composable
fun SettingsDialog(
    sync: SyncController?,
    display: DisplaySettings,
    strings: Strings,
    openBrowser: (String) -> Boolean,
    cloudLogin: suspend (SyncService) -> com.stemaker.nibsync.sync.client.SyncConfig,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(min = 420.dp, max = 600.dp).testTag(SettingsTags.SCREEN)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.settings, style = MaterialTheme.typography.headlineSmall)
                if (sync != null) {
                    Text(strings.settingsSync, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    AccountSettings(sync, strings, openBrowser, cloudLogin)
                }
                Text(strings.settingsDisplay, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SwitchLine(strings.showTitle, display.showTitle, display.onShowTitle, SettingsTags.SHOW_TITLE)
                SwitchLine(strings.showCreated, display.showCreated, display.onShowCreated, SettingsTags.SHOW_CREATED)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) { Text(strings.close) }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
