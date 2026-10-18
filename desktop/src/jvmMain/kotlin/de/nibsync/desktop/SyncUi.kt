package de.nibsync.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.nibsync.sync.client.NextcloudConfig
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncController
import de.nibsync.sync.client.SyncService
import de.nibsync.sync.client.SyncStatus
import de.nibsync.sync.webdav.NextcloudLoginFlow
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

object SyncTags {
    const val STATUS_ICON = "sync-status"
    const val STATUS_LINE = "sync-status-line"
    fun service(service: SyncService) = "sync-service-${service.name}"
    const val SYNC_NOW = "sync-now"
    const val SIGN_OUT = "sync-sign-out"
    const val SERVER_FIELD = "sync-server"
    const val FOLDER_FIELD = "sync-folder"
    const val LOGIN = "sync-login"
}

/** Cloud symbol for the tool bar; clicking it syncs right away. Hidden when not set up. */
@Composable
fun SyncStatusIcon(controller: SyncController, strings: Strings) {
    val status by controller.status.collectAsState()
    val (icon, label) = when (val s = status) {
        SyncStatus.Syncing -> Icons.Filled.CloudSync to strings.syncRunning
        is SyncStatus.Synced -> Icons.Filled.CloudDone to strings.syncDone(time(s.atEpochMs))
        is SyncStatus.Failed -> Icons.Filled.CloudOff to strings.syncFailed(s.message)
        SyncStatus.NotConfigured -> return
    }
    IconButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.STATUS_ICON)) {
        Icon(icon, contentDescription = label, tint = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

fun serviceName(service: SyncService, strings: Strings): String = when (service) {
    SyncService.NEXTCLOUD -> "Nextcloud"
    SyncService.ONEDRIVE -> "OneDrive"
    SyncService.GOOGLE_DRIVE -> "Google Drive"
}

private fun statusText(status: SyncStatus, strings: Strings): String = when (status) {
    SyncStatus.Syncing -> strings.syncRunning
    is SyncStatus.Synced -> strings.syncDone(time(status.atEpochMs))
    is SyncStatus.Failed -> if (status.loginInvalid) strings.syncLoginInvalid else strings.syncFailed(status.message)
    SyncStatus.NotConfigured -> strings.syncNotConfigured
}

/** Bottom of the library: which service, which account, how the sync went. No buttons. */
@Composable
fun SyncStatusLine(controller: SyncController, strings: Strings) {
    val status by controller.status.collectAsState()
    val config by controller.config.collectAsState()
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(16.dp).testTag(SyncTags.STATUS_LINE), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (status is SyncStatus.Failed) Icons.Filled.CloudOff else Icons.Filled.Cloud, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column {
            val c = config
            if (c != null) {
                Text(serviceName(c.service, strings), style = MaterialTheme.typography.titleSmall)
                Text(c.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                statusText(status, strings),
                style = MaterialTheme.typography.bodySmall,
                color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Sync part of the settings: the connected service with "sync now" and "disconnect", or the
 * services to connect to. [connect] starts the login for OneDrive and Google Drive; null for a
 * service that is not set up in this build.
 */
@Composable
fun SyncSettings(controller: SyncController, strings: Strings, openBrowser: (String) -> Boolean, connect: (SyncService) -> (() -> Unit)?) {
    val status by controller.status.collectAsState()
    val config by controller.config.collectAsState()
    var connectingNextcloud by remember { mutableStateOf(false) }
    val c = config
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c != null) {
            Text(serviceName(c.service, strings), style = MaterialTheme.typography.titleMedium)
            Text(c.description, style = MaterialTheme.typography.bodyMedium)
            Text(statusText(status, strings), style = MaterialTheme.typography.bodySmall, color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.SYNC_NOW)) { Text(strings.syncNow) }
                TextButton(onClick = { controller.configure(null) }, modifier = Modifier.testTag(SyncTags.SIGN_OUT)) { Text(strings.syncSignOut) }
            }
        } else {
            Text(strings.syncNotConfigured, style = MaterialTheme.typography.bodyMedium)
            Text(strings.syncChooseService, style = MaterialTheme.typography.bodySmall)
            for (service in SyncService.entries) {
                val start: (() -> Unit)? = if (service == SyncService.NEXTCLOUD) ({ connectingNextcloud = true }) else connect(service)
                ListItem(
                    headlineContent = { Text(serviceName(service, strings)) },
                    supportingContent = if (start == null) ({ Text(strings.syncServiceUnavailable) }) else null,
                    leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    modifier = Modifier
                        .testTag(SyncTags.service(service))
                        .then(if (start != null) Modifier.clickable(onClick = start) else Modifier),
                )
            }
        }
    }
    if (connectingNextcloud) ConnectDialog(strings, openBrowser, onDismiss = { connectingNextcloud = false }) { config ->
        connectingNextcloud = false
        controller.configure(config)
    }
}

/**
 * Asks for the server, then lets the user log in on the Nextcloud page in the browser
 * (Login Flow v2) and waits for the app password, like the Android app.
 */
@Composable
private fun ConnectDialog(strings: Strings, openBrowser: (String) -> Boolean, onDismiss: () -> Unit, onConnected: (SyncConfig) -> Unit) {
    var server by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(SyncConfig.DEFAULT_FOLDER) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualUrl by remember { mutableStateOf<String?>(null) }
    val flow = remember { NextcloudLoginFlow() }

    if (waiting) {
        LaunchedEffect(Unit) {
            try {
                val started = flow.start(server)
                if (!openBrowser(started.loginUrl)) manualUrl = started.loginUrl
                val deadline = System.currentTimeMillis() + 10 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    flow.poll(started)?.let {
                        onConnected(NextcloudConfig(it, folder.trim('/', ' ').ifEmpty { SyncConfig.DEFAULT_FOLDER }))
                        return@LaunchedEffect
                    }
                    delay(2_000)
                }
                error = strings.syncLoginTimeout
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            waiting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.syncConnectNextcloud) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, singleLine = true, enabled = !waiting,
                    label = { Text(strings.syncServer) },
                    placeholder = { Text("cloud.example.org") },
                    modifier = Modifier.testTag(SyncTags.SERVER_FIELD),
                )
                OutlinedTextField(
                    value = folder, onValueChange = { folder = it }, singleLine = true, enabled = !waiting,
                    label = { Text(strings.syncFolder) },
                    modifier = Modifier.testTag(SyncTags.FOLDER_FIELD),
                )
                if (waiting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(strings.syncWaiting)
                    }
                }
                manualUrl?.let { SelectionContainer { Text(strings.syncOpenBrowser(it)) } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { error = null; manualUrl = null; waiting = true },
                enabled = server.isNotBlank() && !waiting,
                modifier = Modifier.testTag(SyncTags.LOGIN),
            ) { Text(strings.syncLoginInBrowser) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/**
 * Waits for the browser login of OneDrive or Google Drive ([login]) and reports the result;
 * the dialog can be cancelled meanwhile.
 */
@Composable
fun CloudConnectDialog(service: SyncService, strings: Strings, login: suspend () -> SyncConfig, onDismiss: () -> Unit, onConnected: (SyncConfig) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            onConnected(login())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.syncConnectTo(serviceName(service, strings))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(strings.syncWaiting)
                    }
                } else {
                    SelectionContainer { Text(error!!, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text(strings.syncLoginInBrowser) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private fun time(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

/** Opens [url] in the default browser; false if that is not possible (then show it). */
fun openInBrowser(url: String): Boolean {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            return true
        }
    }
    // Linux desktops without AWT browse support.
    return runCatching { ProcessBuilder("xdg-open", url).start(); true }.getOrDefault(false)
}
