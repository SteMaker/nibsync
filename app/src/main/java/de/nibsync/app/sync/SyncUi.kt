package de.nibsync.app.sync

import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncController
import de.nibsync.sync.client.SyncStatus
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nibsync.app.R
import de.nibsync.sync.webdav.NextcloudLoginFlow
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

object SyncTags {
    const val STATUS_ICON = "sync-status"
    const val CONNECT = "sync-connect"
    const val SYNC_NOW = "sync-now"
    const val SIGN_OUT = "sync-sign-out"
    const val SERVER_FIELD = "sync-server"
    const val FOLDER_FIELD = "sync-folder"
    const val LOGIN = "sync-login"
}

/** Cloud symbol for the tool bar; tapping it syncs right away. Hidden when not set up. */
@Composable
fun SyncStatusIcon(controller: SyncController) {
    val status by controller.status.collectAsStateWithLifecycle()
    if (status == SyncStatus.NotConfigured) return
    val (icon, label) = when (val s = status) {
        SyncStatus.Syncing -> Icons.Filled.CloudSync to stringResource(R.string.sync_running)
        is SyncStatus.Synced -> Icons.Filled.CloudDone to stringResource(R.string.sync_done, time(s.atEpochMs))
        is SyncStatus.Failed -> Icons.Filled.CloudOff to stringResource(R.string.sync_failed, s.message)
        SyncStatus.NotConfigured -> return
    }
    IconButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.STATUS_ICON)) {
        Icon(icon, contentDescription = label, tint = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** Nextcloud section at the bottom of the library drawer. */
@Composable
fun SyncPanel(controller: SyncController) {
    val status by controller.status.collectAsStateWithLifecycle()
    val config by controller.config.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(false) }

    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Cloud, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nextcloud), style = MaterialTheme.typography.titleSmall)
        }
        val c = config
        if (c == null) {
            Text(stringResource(R.string.sync_not_configured), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { connecting = true }, modifier = Modifier.testTag(SyncTags.CONNECT)) {
                Text(stringResource(R.string.sync_connect))
            }
        } else {
            Text(
                "${c.account.loginName} @ ${c.account.serverUrl.removePrefix("https://").removePrefix("http://")} / ${c.folder}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (val s = status) {
                    SyncStatus.Syncing -> stringResource(R.string.sync_running)
                    is SyncStatus.Synced -> stringResource(R.string.sync_done, time(s.atEpochMs))
                    is SyncStatus.Failed -> if (s.loginInvalid) stringResource(R.string.sync_login_invalid) else stringResource(R.string.sync_failed, s.message)
                    SyncStatus.NotConfigured -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.SYNC_NOW)) {
                    Text(stringResource(R.string.sync_now))
                }
                TextButton(onClick = { controller.configure(null) }, modifier = Modifier.testTag(SyncTags.SIGN_OUT)) {
                    Text(stringResource(R.string.sync_sign_out))
                }
            }
        }
    }
    if (connecting) ConnectDialog(onDismiss = { connecting = false }) { config ->
        connecting = false
        controller.configure(config)
    }
}

/**
 * Asks for the server, then lets the user log in on the Nextcloud page in the browser
 * (Login Flow v2) and waits for the app password.
 */
@Composable
private fun ConnectDialog(onDismiss: () -> Unit, onConnected: (SyncConfig) -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(SyncAccountStore.DEFAULT_FOLDER) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val flow = remember { NextcloudLoginFlow() }
    val timeoutText = stringResource(R.string.sync_login_timeout)

    if (waiting) {
        LaunchedEffect(Unit) {
            try {
                val started = flow.start(server)
                context.startActivity(Intent(Intent.ACTION_VIEW, started.loginUrl.toUri()))
                val deadline = System.currentTimeMillis() + 10 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    flow.poll(started)?.let {
                        onConnected(SyncConfig(it, folder.trim('/', ' ').ifEmpty { SyncAccountStore.DEFAULT_FOLDER }))
                        return@LaunchedEffect
                    }
                    delay(2_000)
                }
                error = timeoutText
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            waiting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_connect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, singleLine = true, enabled = !waiting,
                    label = { Text(stringResource(R.string.sync_server)) },
                    placeholder = { Text("cloud.example.org") },
                    modifier = Modifier.testTag(SyncTags.SERVER_FIELD),
                )
                OutlinedTextField(
                    value = folder, onValueChange = { folder = it }, singleLine = true, enabled = !waiting,
                    label = { Text(stringResource(R.string.sync_folder)) },
                    modifier = Modifier.testTag(SyncTags.FOLDER_FIELD),
                )
                if (waiting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.sync_waiting_for_login))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { error = null; waiting = true },
                enabled = server.isNotBlank() && !waiting,
                modifier = Modifier.testTag(SyncTags.LOGIN),
            ) { Text(stringResource(R.string.sync_login_in_browser)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun time(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
