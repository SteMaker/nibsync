package com.stemaker.nibsync.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes crashes with their full stack trace to `crash.log` in the data folder, so a crash on a
 * PC without a console (Windows) can be reported exactly. The file is kept small: when it gets
 * larger than [MAX_BYTES] it moves to `crash.old.log` and a new one starts.
 */
class CrashLog(dataDir: File, private val version: String = appVersion()) {
    val file = File(dataDir, FILE_NAME)
    private val old = File(dataDir, OLD_FILE_NAME)

    /** Logs exceptions no other code catches, on any thread (also the UI and coroutines). */
    fun installAsDefault() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            write(e, "thread ${thread.name}")
            previous?.uncaughtException(thread, e)
        }
    }

    /** The last exception written, so one that is passed on and caught again is logged once. */
    private var last: Throwable? = null

    /** Appends [e] with the time, the app version and the system; never throws. */
    @Synchronized
    fun write(e: Throwable, where: String) {
        if (generateSequence(e) { it.cause }.any { it === last }) return
        last = e
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) {
                old.delete()
                file.renameTo(old)
            }
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
            val header = buildString {
                append("=== ").append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                append(" | NibSync ").append(version)
                append(" | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                append(" | Java ").append(System.getProperty("java.version"))
                append(" | ").append(where)
            }
            file.appendText(header + "\n" + trace + "\n", Charsets.UTF_8)
        }
    }

    companion object {
        const val FILE_NAME = "crash.log"
        const val OLD_FILE_NAME = "crash.old.log"
        const val MAX_BYTES = 1_000_000L

        /** The version of the installed package, or "dev" when run from the build. */
        fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "dev"
    }
}
