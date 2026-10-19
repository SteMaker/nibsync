package com.stemaker.nibsync.desktop

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
import com.stemaker.nibsync.sync.client.AccountId
import com.stemaker.nibsync.sync.client.NextcloudConfig
import com.stemaker.nibsync.sync.client.SyncAccount
import com.stemaker.nibsync.sync.client.displayName
import com.stemaker.nibsync.sync.client.SyncConfig
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncService
import com.stemaker.nibsync.sync.client.SyncStatus
import com.stemaker.nibsync.sync.webdav.NextcloudLoginFlow
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

object SyncTags {
    const val STATUS_ICON = "sync-status"
    const val STATUS_LINE = "sync-status-line"
    fun service(service: SyncService) = "sync-service-${service.name}"
    const val ADD_ACCOUNT = "sync-add-account"
    fun account(name: String) = "sync-account-$name"
    fun syncNow(name: String) = "sync-now-$name"
    fun rename(name: String) = "sync-rename-$name"
    fun relogin(name: String) = "sync-relogin-$name"
    fun remove(name: String) = "sync-remove-$name"
    const val CONFIRM_REMOVE = "sync-confirm-remove"
    const val ACCOUNT_NAME = "sync-account-name"
    const val SAVE_ACCOUNT = "sync-save-account"
    const val SERVER_FIELD = "sync-server"
    const val FOLDER_FIELD = "sync-folder"
    const val LOGIN = "sync-login"
}

/** All accounts together: the worst state wins (failed, then syncing). */
private fun overall(statuses: Collection<SyncStatus>): SyncStatus? = when {
    statuses.isEmpty() -> null
    statuses.any { it is SyncStatus.Failed } -> statuses.first { it is SyncStatus.Failed }
    statuses.any { it is SyncStatus.Syncing } -> SyncStatus.Syncing
    else -> statuses.filterIsInstance<SyncStatus.Synced>().maxByOrNull { it.atEpochMs }
}

/** Cloud symbol for the tool bar; clicking it syncs right away. Hidden without accounts. */
@Composable
fun SyncStatusIcon(controller: SyncController, strings: Strings) {
    val accounts by controller.accounts.collectAsState()
    val statuses by controller.statuses.collectAsState()
    if (accounts.isEmpty()) return
    val status = overall(accounts.mapNotNull { statuses[it.id] })
    val (icon, label) = when (status) {
        is SyncStatus.Synced -> Icons.Filled.CloudDone to strings.syncDone(time(status.atEpochMs))
        is SyncStatus.Failed -> Icons.Filled.CloudOff to strings.syncFailed(status.message)
        else -> Icons.Filled.CloudSync to strings.syncRunning
    }
    IconButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.STATUS_ICON)) {
        Icon(icon, contentDescription = label, tint = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

fun serviceName(service: SyncService, strings: Strings): String = service.displayName

private fun statusText(status: SyncStatus?, strings: Strings): String = when (status) {
    null, SyncStatus.Syncing -> strings.syncRunning
    is SyncStatus.Synced -> strings.syncDone(time(status.atEpochMs))
    is SyncStatus.Failed -> if (status.loginInvalid) strings.syncLoginInvalid else strings.syncFailed(status.message)
    SyncStatus.NotConfigured -> strings.syncNotConfigured
}

/** Bottom of the library: each account with its service and how its sync went. No buttons. */
@Composable
fun SyncStatusLine(controller: SyncController, strings: Strings) {
    val accounts by controller.accounts.collectAsState()
    val statuses by controller.statuses.collectAsState()
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(SyncTags.STATUS_LINE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudOff, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(strings.syncNotConfigured, style = MaterialTheme.typography.bodySmall)
            }
        }
        for (account in accounts) {
            val status = statuses[account.id]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (status is SyncStatus.Failed) Icons.Filled.CloudOff else Icons.Filled.Cloud, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${account.name} · ${account.config.service.displayName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusText(status, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** A login in progress: for a new account, or again for an existing one ([account]). */
private data class Login(val service: SyncService, val account: AccountId? = null)

/**
 * Accounts part of the settings: every account with "sync now", "rename", "log in again" (when
 * its login is no longer valid) and "remove", and adding one. [cloudLogin] runs the browser
 * login for OneDrive and Google Drive.
 */
@Composable
fun AccountSettings(controller: SyncController, strings: Strings, openBrowser: (String) -> Boolean, cloudLogin: suspend (SyncService) -> SyncConfig) {
    val accounts by controller.accounts.collectAsState()
    val statuses by controller.statuses.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<Login?>(null) }
    var naming by remember { mutableStateOf<SyncConfig?>(null) }
    var renaming by remember { mutableStateOf<SyncAccount?>(null) }
    var removing by remember { mutableStateOf<SyncAccount?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.accountsHint, style = MaterialTheme.typography.bodySmall)
        for (account in accounts) {
            val status = statuses[account.id]
            Column(Modifier.fillMaxWidth().testTag(SyncTags.account(account.name)).padding(vertical = 4.dp)) {
                Text("${account.name} · ${account.config.service.displayName}", style = MaterialTheme.typography.titleMedium)
                Text(account.config.description, style = MaterialTheme.typography.bodyMedium)
                Text(statusText(status, strings), style = MaterialTheme.typography.bodySmall, color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status is SyncStatus.Failed && status.loginInvalid) {
                        FilledTonalButton(onClick = { login = Login(account.config.service, account.id) }, modifier = Modifier.testTag(SyncTags.relogin(account.name))) { Text(strings.relogin) }
                    }
                    TextButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.syncNow(account.name))) { Text(strings.syncNow) }
                    TextButton(onClick = { renaming = account }, modifier = Modifier.testTag(SyncTags.rename(account.name))) { Text(strings.rename) }
                    TextButton(onClick = { removing = account }, modifier = Modifier.testTag(SyncTags.remove(account.name))) { Text(strings.remove) }
                }
            }
            HorizontalDivider()
        }
        if (accounts.isEmpty()) Text(strings.syncNotConfigured, style = MaterialTheme.typography.bodyMedium)
        if (!choosing) {
            FilledTonalButton(onClick = { choosing = true }, modifier = Modifier.testTag(SyncTags.ADD_ACCOUNT)) { Text(strings.addAccount) }
        } else {
            Text(strings.syncChooseService, style = MaterialTheme.typography.bodySmall)
            for (service in SyncService.entries) {
                val available = controller.available(service)
                ListItem(
                    headlineContent = { Text(service.displayName) },
                    supportingContent = if (!available) ({ Text(strings.syncServiceUnavailable) }) else null,
                    leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    modifier = Modifier
                        .testTag(SyncTags.service(service))
                        .then(if (available) Modifier.clickable { choosing = false; login = Login(service) } else Modifier),
                )
            }
        }
    }

    val current = login
    if (current != null) {
        val done: (SyncConfig) -> Unit = { config ->
            login = null
            if (current.account == null) naming = config else controller.updateLogin(current.account, config)
        }
        if (current.service == SyncService.NEXTCLOUD) {
            ConnectDialog(strings, openBrowser, onDismiss = { login = null }, onConnected = done)
        } else {
            CloudConnectDialog(current.service, strings, login = { cloudLogin(current.service) }, onDismiss = { login = null }, onConnected = done)
        }
    }
    naming?.let { config ->
        NameDialog(strings, initial = config.service.displayName, onDismiss = { naming = null }) { name ->
            if (controller.addAccount(name, config) != null) null.also { naming = null } else strings.accountExists
        }
    }
    renaming?.let { account ->
        NameDialog(strings, initial = account.name, onDismiss = { renaming = null }) { name ->
            controller.renameAccount(account.id, name)
            renaming = null
            null
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(strings.removeAccountQuestion(account.name)) },
            text = { Text(strings.removeAccountText) },
            confirmButton = {
                TextButton(onClick = { controller.removeAccount(account.id); removing = null }, modifier = Modifier.testTag(SyncTags.CONFIRM_REMOVE)) { Text(strings.remove) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strings.cancel) } },
        )
    }
}

/** Asks for an account's name; [onSave] returns an error to show, or null when done. */
@Composable
private fun NameDialog(strings: Strings, initial: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.accountName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(strings.accountNameHint) },
                    modifier = Modifier.testTag(SyncTags.ACCOUNT_NAME),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(SyncTags.SAVE_ACCOUNT)) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
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
