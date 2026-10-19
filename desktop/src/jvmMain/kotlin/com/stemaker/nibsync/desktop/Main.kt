package com.stemaker.nibsync.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.stemaker.nibsync.sync.client.SyncController
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath

/**
 * Starts the PC app. `--data-dir <path>` uses another data folder than the default
 * (see [DataDir]), e.g. for trying things out.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val dataDir = args.indexOf("--data-dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toPath() } ?: DataDir.default()
    val crashLog = CrashLog(java.io.File(dataDir.toString())).also { it.installAsDefault() }
    val container = DesktopContainer(dataDir)
    val settings = Settings(container.fs, dataDir / "settings.properties")
    val strings = Strings()
    val keys = KeyRouter()
    val sync = SyncController(
        container,
        DesktopSyncStore(java.nio.file.Path.of(dataDir.toString(), "sync-account.properties")),
        container.appScope,
        clients = desktopCloudClients(),
    )
    // A crash in the window is logged first, then shown in Compose's error dialog as before.
    val exceptionHandlers = WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { e ->
            crashLog.write(e, "window")
            DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
                .onException(RuntimeException(strings.crashLogged(crashLog.file.path), e))
        }
    }
    application {
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandlers) {
        Window(
            onCloseRequest = {
                runBlocking {
                    container.closeAll()
                    // Push what was written last; do not hang if the server is unreachable.
                    withTimeoutOrNull(FINAL_SYNC_TIMEOUT_MS) { sync.runOnce() }
                }
                exitApplication()
            },
            title = strings.appName,
            icon = androidx.compose.ui.res.painterResource("nibsync-icon.png"),
            state = rememberWindowState(width = 1400.dp, height = 900.dp),
            onPreviewKeyEvent = keys::onKey,
        ) {
            App(container, settings, strings, keys, sync)
        }
        }
    }
}

/** How long closing the window may wait for the last sync. */
private const val FINAL_SYNC_TIMEOUT_MS = 10_000L
