package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesTheStackTraceOnceAndKeepsTheFileSmall() {
        val log = CrashLog(tmp.root, version = "1.2.3")
        val crash = IllegalArgumentException("mark out of range", IndexOutOfBoundsException("inner"))
        log.write(crash, "window")
        // Passed on wrapped and caught again: not written twice.
        log.write(RuntimeException("wrapped", crash), "thread AWT-EventQueue-0")
        val text = log.file.readText()
        assertThat(text).contains("NibSync 1.2.3")
        assertThat(text).contains("| window")
        assertThat(text).contains("java.lang.IllegalArgumentException: mark out of range")
        assertThat(text).contains("Caused by: java.lang.IndexOutOfBoundsException: inner")
        assertThat(text).contains("CrashLogTest.writesTheStackTraceOnceAndKeepsTheFileSmall")
        assertThat(Regex("=== ").findAll(text).count()).isEqualTo(1)

        // A full log moves aside.
        log.file.writeText("x".repeat((CrashLog.MAX_BYTES + 1).toInt()))
        log.write(IllegalStateException("next"), "thread main")
        assertThat(tmp.root.resolve(CrashLog.OLD_FILE_NAME).length()).isGreaterThan(CrashLog.MAX_BYTES)
        assertThat(log.file.readText()).contains("IllegalStateException: next")
    }
}
