package com.stemaker.nibsync.app.sync

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ListItem
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
import com.stemaker.nibsync.app.R
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

/** Cloud symbol for the tool bar; tapping it syncs right away. Hidden without accounts. */
@Composable
fun SyncStatusIcon(controller: SyncController) {
    val accounts by controller.accounts.collectAsStateWithLifecycle()
    val statuses by controller.statuses.collectAsStateWithLifecycle()
    if (accounts.isEmpty()) return
    val status = overall(accounts.mapNotNull { statuses[it.id] })
    val (icon, label) = when (status) {
        is SyncStatus.Synced -> Icons.Filled.CloudDone to stringResource(R.string.sync_done, time(status.atEpochMs))
        is SyncStatus.Failed -> Icons.Filled.CloudOff to stringResource(R.string.sync_failed, status.message)
        else -> Icons.Filled.CloudSync to stringResource(R.string.sync_running)
    }
    IconButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.STATUS_ICON)) {
        Icon(icon, contentDescription = label, tint = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

fun serviceName(service: SyncService): String = service.displayName

@Composable
private fun statusText(status: SyncStatus?): String = when (status) {
    null, SyncStatus.Syncing -> stringResource(R.string.sync_running)
    is SyncStatus.Synced -> stringResource(R.string.sync_done, time(status.atEpochMs))
    is SyncStatus.Failed -> if (status.loginInvalid) stringResource(R.string.sync_login_invalid) else stringResource(R.string.sync_failed, status.message)
    SyncStatus.NotConfigured -> stringResource(R.string.sync_not_configured)
}

/** Bottom of the library: each account with its service and how its sync went. No buttons. */
@Composable
fun SyncStatusLine(controller: SyncController) {
    val accounts by controller.accounts.collectAsStateWithLifecycle()
    val statuses by controller.statuses.collectAsStateWithLifecycle()
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(SyncTags.STATUS_LINE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudOff, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.sync_not_configured), style = MaterialTheme.typography.bodySmall)
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
                        statusText(status),
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
 * its login is no longer valid) and "remove", and adding one. [cloudLogin] runs the login for
 * OneDrive and Google Drive.
 */
@Composable
fun AccountSettings(controller: SyncController, cloudLogin: suspend (SyncService) -> SyncConfig) {
    val accounts by controller.accounts.collectAsStateWithLifecycle()
    val statuses by controller.statuses.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<Login?>(null) }
    var naming by remember { mutableStateOf<SyncConfig?>(null) }
    var renaming by remember { mutableStateOf<SyncAccount?>(null) }
    var removing by remember { mutableStateOf<SyncAccount?>(null) }
    val exists = stringResource(R.string.account_exists)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.accounts_hint), style = MaterialTheme.typography.bodySmall)
        for (account in accounts) {
            val status = statuses[account.id]
            Column(Modifier.fillMaxWidth().testTag(SyncTags.account(account.name)).padding(vertical = 4.dp)) {
                Text("${account.name} · ${account.config.service.displayName}", style = MaterialTheme.typography.titleMedium)
                Text(account.config.description, style = MaterialTheme.typography.bodyMedium)
                Text(statusText(status), style = MaterialTheme.typography.bodySmall, color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status is SyncStatus.Failed && status.loginInvalid) {
                        FilledTonalButton(onClick = { login = Login(account.config.service, account.id) }, modifier = Modifier.testTag(SyncTags.relogin(account.name))) {
                            Text(stringResource(R.string.relogin))
                        }
                    }
                    TextButton(onClick = controller::syncNow, modifier = Modifier.testTag(SyncTags.syncNow(account.name))) { Text(stringResource(R.string.sync_now)) }
                    TextButton(onClick = { renaming = account }, modifier = Modifier.testTag(SyncTags.rename(account.name))) { Text(stringResource(R.string.rename_account)) }
                    TextButton(onClick = { removing = account }, modifier = Modifier.testTag(SyncTags.remove(account.name))) { Text(stringResource(R.string.remove_account)) }
                }
            }
            HorizontalDivider()
        }
        if (accounts.isEmpty()) Text(stringResource(R.string.sync_not_configured), style = MaterialTheme.typography.bodyMedium)
        if (!choosing) {
            FilledTonalButton(onClick = { choosing = true }, modifier = Modifier.testTag(SyncTags.ADD_ACCOUNT)) { Text(stringResource(R.string.add_account)) }
        } else {
            Text(stringResource(R.string.sync_choose_service), style = MaterialTheme.typography.bodySmall)
            for (service in SyncService.entries) {
                val available = controller.available(service)
                ListItem(
                    headlineContent = { Text(service.displayName) },
                    supportingContent = if (!available) ({ Text(stringResource(R.string.sync_service_unavailable)) }) else null,
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
            NextcloudConnectDialog(onDismiss = { login = null }, onConnected = done)
        } else {
            CloudConnectDialog(current.service, login = { cloudLogin(current.service) }, onDismiss = { login = null }, onConnected = done)
        }
    }
    naming?.let { config ->
        AccountNameDialog(initial = config.service.displayName, onDismiss = { naming = null }) { name ->
            if (controller.addAccount(name, config) != null) null.also { naming = null } else exists
        }
    }
    renaming?.let { account ->
        AccountNameDialog(initial = account.name, onDismiss = { renaming = null }) { name ->
            controller.renameAccount(account.id, name)
            renaming = null
            null
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_account_question, account.name)) },
            text = { Text(stringResource(R.string.remove_account_text)) },
            confirmButton = {
                TextButton(onClick = { controller.removeAccount(account.id); removing = null }, modifier = Modifier.testTag(SyncTags.CONFIRM_REMOVE)) {
                    Text(stringResource(R.string.remove_account))
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Asks for an account's name; [onSave] returns an error to show, or null when done. */
@Composable
private fun AccountNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.account_name_hint)) },
                    modifier = Modifier.testTag(SyncTags.ACCOUNT_NAME),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(SyncTags.SAVE_ACCOUNT)) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * Asks for the server, then lets the user log in on the Nextcloud page in the browser
 * (Login Flow v2) and waits for the app password.
 */
@Composable
private fun NextcloudConnectDialog(onDismiss: () -> Unit, onConnected: (SyncConfig) -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(SyncConfig.DEFAULT_FOLDER) }
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
                        onConnected(NextcloudConfig(it, folder.trim('/', ' ').ifEmpty { SyncConfig.DEFAULT_FOLDER }))
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
        title = { Text(stringResource(R.string.sync_connect_nextcloud)) },
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

/**
 * Waits for the OneDrive or Google Drive login ([login]) and reports the result; the dialog can
 * be cancelled meanwhile.
 */
@Composable
fun CloudConnectDialog(service: SyncService, login: suspend () -> SyncConfig, onDismiss: () -> Unit, onConnected: (SyncConfig) -> Unit) {
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
        title = { Text(stringResource(R.string.sync_connect_to, serviceName(service))) },
        text = {
            if (error == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.sync_waiting_for_login))
                }
            } else {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.sync_login_again)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun time(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
