package de.nibsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.nibsync.sync.client.CloudClients
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
    /** The clients the user entered, and how to keep new ones; null hides that section. */
    userClients: CloudClients? = null,
    builtInClients: CloudClients = CloudClients(),
    onUserClients: (CloudClients) -> Unit = {},
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(min = 420.dp, max = 600.dp).testTag(SettingsTags.SCREEN)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.settings, style = MaterialTheme.typography.headlineSmall)
                Text(strings.settingsSync, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SyncSettings(sync, strings, openBrowser, connect)
                if (userClients != null) {
                    HorizontalDivider()
                    ClientsSection(strings, userClients, builtInClients, onUserClients)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) { Text(strings.close) }
                }
            }
        }
    }
}

object ClientTags {
    const val ONEDRIVE_ID = "client-onedrive-id"
    const val GOOGLE_ID = "client-google-id"
    const val GOOGLE_SECRET = "client-google-secret"
    const val SAVE = "client-save"
}

/** Own app registrations for OneDrive and Google Drive (see docs/cloud-setup.md). */
@Composable
private fun ClientsSection(strings: Strings, user: CloudClients, builtIn: CloudClients, onSave: (CloudClients) -> Unit) {
    var oneDrive by remember(user) { mutableStateOf(user.oneDriveClientId.orEmpty()) }
    var googleId by remember(user) { mutableStateOf(user.googleDesktopClientId.orEmpty()) }
    var googleSecret by remember(user) { mutableStateOf(user.googleDesktopSecret.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.clientsTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(strings.clientsHint, style = MaterialTheme.typography.bodySmall)
        @Composable
        fun field(label: String, value: String, builtInValue: String?, tag: String, secret: Boolean = false, onChange: (String) -> Unit) =
            OutlinedTextField(
                value = value, onValueChange = onChange, singleLine = true,
                label = { Text(label) },
                placeholder = { Text(if (builtInValue.isNullOrBlank()) strings.clientsNone else strings.clientsBuiltIn) },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
        field(strings.clientsOneDriveId, oneDrive, builtIn.oneDriveClientId, ClientTags.ONEDRIVE_ID) { oneDrive = it }
        field(strings.clientsGoogleId, googleId, builtIn.googleDesktopClientId, ClientTags.GOOGLE_ID) { googleId = it }
        field(strings.clientsGoogleSecret, googleSecret, builtIn.googleDesktopSecret, ClientTags.GOOGLE_SECRET, secret = true) { googleSecret = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(
                onClick = { onSave(CloudClients(oneDriveClientId = oneDrive.trim(), googleDesktopClientId = googleId.trim(), googleDesktopSecret = googleSecret.trim())) },
                modifier = Modifier.testTag(ClientTags.SAVE),
            ) { Text(strings.clientsSave) }
        }
    }
}
