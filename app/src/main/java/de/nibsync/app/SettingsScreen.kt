package de.nibsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.nibsync.app.sync.SyncSettings
import de.nibsync.sync.client.CloudClients
import de.nibsync.sync.client.SyncController
import de.nibsync.sync.client.SyncService

object SettingsTags {
    const val ONEDRIVE_ID = "settings-onedrive-id"
    const val SAVE_CLIENTS = "settings-save-clients"
    const val SCREEN = "settings"
    const val CLOSE = "settings-close"
    const val PAGE_BREAKS = "settings-page-breaks"
    const val DRAW_WITH_FINGER = "settings-draw-with-finger"
}

/** App settings, grouped in sections; new settings get their own section or line here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sync: SyncController,
    connect: (SyncService) -> (() -> Unit)?,
    userClients: CloudClients,
    builtInClients: CloudClients,
    onUserClients: (CloudClients) -> Unit,
    showPageBreaks: Boolean,
    onShowPageBreaks: (Boolean) -> Unit,
    drawWithFinger: Boolean,
    onDrawWithFinger: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag(SettingsTags.SCREEN),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Section(stringResource(R.string.settings_sync)) { SyncSettings(sync, connect) }
                    Section(stringResource(R.string.clients_title)) { ClientsSettings(userClients, builtInClients, onUserClients) }
                    HorizontalDivider()
                    Section(stringResource(R.string.settings_display)) {
                        SwitchLine(stringResource(R.string.page_breaks), showPageBreaks, onShowPageBreaks, SettingsTags.PAGE_BREAKS)
                        SwitchLine(stringResource(R.string.draw_with_finger), drawWithFinger, onDrawWithFinger, SettingsTags.DRAW_WITH_FINGER)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    )
}

/** Own app registration: the OneDrive client id (Google Drive needs none on Android). */
@Composable
private fun ClientsSettings(user: CloudClients, builtIn: CloudClients, onSave: (CloudClients) -> Unit) {
    var oneDrive by remember(user) { mutableStateOf(user.oneDriveClientId.orEmpty()) }
    Text(stringResource(R.string.clients_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = oneDrive, onValueChange = { oneDrive = it }, singleLine = true,
        label = { Text(stringResource(R.string.clients_onedrive_id)) },
        placeholder = { Text(stringResource(if (builtIn.oneDriveClientId.isNullOrBlank()) R.string.clients_none else R.string.clients_built_in)) },
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.ONEDRIVE_ID),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        FilledTonalButton(onClick = { onSave(user.copy(oneDriveClientId = oneDrive.trim())) }, modifier = Modifier.testTag(SettingsTags.SAVE_CLIENTS)) {
            Text(stringResource(R.string.clients_save))
        }
    }
}
