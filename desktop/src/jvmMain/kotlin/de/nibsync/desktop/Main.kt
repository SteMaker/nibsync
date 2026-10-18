package de.nibsync.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/**
 * Starts the PC app. `--data-dir <path>` uses another data folder than the default
 * (see [DataDir]), e.g. for trying things out.
 */
fun main(args: Array<String>) {
    val dataDir = args.indexOf("--data-dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toPath() } ?: DataDir.default()
    val container = DesktopContainer(dataDir)
    val settings = Settings(container.fs, dataDir / "settings.properties")
    val strings = Strings()
    val keys = KeyRouter()
    application {
        Window(
            onCloseRequest = {
                runBlocking { container.closeAll() }
                exitApplication()
            },
            title = strings.appName,
            state = rememberWindowState(width = 1400.dp, height = 900.dp),
            onPreviewKeyEvent = keys::onKey,
        ) {
            App(container, settings, strings, keys)
        }
    }
}
