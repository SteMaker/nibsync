package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DataDirTest {
    @Test
    fun linuxFollowsXdg() {
        assertThat(DataDir.default("Linux", "/home/u") { null }.toString()).isEqualTo("/home/u/.local/share/nibsync")
        assertThat(DataDir.default("Linux", "/home/u") { if (it == "XDG_DATA_HOME") "/data" else null }.toString()).isEqualTo("/data/nibsync")
        assertThat(DataDir.default("Linux", "/home/u") { if (it == "XDG_DATA_HOME") "" else null }.toString()).isEqualTo("/home/u/.local/share/nibsync")
    }

    @Test
    fun windowsUsesAppData() {
        assertThat(DataDir.default("Windows 11", "C:\\Users\\u") { if (it == "APPDATA") "C:\\Users\\u\\AppData\\Roaming" else null }.toString())
            .isEqualTo("C:\\Users\\u\\AppData\\Roaming\\NibSync")
    }

    @Test
    fun macUsesApplicationSupport() {
        assertThat(DataDir.default("Mac OS X", "/Users/u") { null }.toString()).isEqualTo("/Users/u/Library/Application Support/NibSync")
    }
}
