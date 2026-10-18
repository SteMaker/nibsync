package com.stemaker.nibsync.desktop

import okio.Path
import okio.Path.Companion.toPath

/**
 * Where NibSync keeps its files on a PC: `$XDG_DATA_HOME/nibsync` (default
 * `~/.local/share/nibsync`) on Linux, `%APPDATA%\NibSync` on Windows,
 * `~/Library/Application Support/NibSync` on macOS.
 */
object DataDir {
    fun default(
        osName: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
        env: (String) -> String? = System::getenv,
    ): Path {
        val os = osName.lowercase()
        return when {
            "win" in os -> ((env("APPDATA") ?: "$home\\AppData\\Roaming") + "\\NibSync").toPath()
            "mac" in os -> "$home/Library/Application Support/NibSync".toPath()
            else -> (env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share").toPath() / "nibsync"
        }
    }
}
