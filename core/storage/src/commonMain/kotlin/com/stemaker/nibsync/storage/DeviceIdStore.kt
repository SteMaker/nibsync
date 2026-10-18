package com.stemaker.nibsync.storage

import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.IdGenerator
import okio.FileSystem
import okio.Path

/**
 * Keeps the id of this app installation in a local (never synced) file. It is generated on
 * first use and must stay stable, because it names this device's op logs.
 */
object DeviceIdStore {
    fun loadOrCreate(fs: FileSystem, file: Path, ids: IdGenerator = IdGenerator()): DeviceId {
        if (fs.exists(file)) {
            val text = fs.read(file) { readUtf8() }.trim()
            runCatching { DeviceId(text) }.getOrNull()?.let { return it }
        }
        val id = ids.newDeviceId()
        file.parent?.let(fs::createDirectories)
        val tmp = file.parent!! / "${file.name}.tmp"
        fs.write(tmp) { writeUtf8(id.value) }
        fs.atomicMove(tmp, file)
        return id
    }
}
