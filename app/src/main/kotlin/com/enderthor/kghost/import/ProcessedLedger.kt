package com.enderthor.kghost.import_

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Records source files already decoded+stored, keyed by absolute path → (size, lastModified), so a
 * re-import skips the expensive DECODE for unchanged files (sourceKey dedup only skips re-STORING,
 * after decoding). Failures are never recorded — they must keep retrying. Tolerant decode: a missing
 * or corrupt ledger loads empty and is rebuilt as files are re-marked.
 */
class ProcessedLedger(private val file: File) {
    @Serializable data class Entry(val size: Long, val lastModified: Long)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): MutableMap<String, Entry> = runCatching {
        if (!file.isFile) return@runCatching mutableMapOf<String, Entry>()
        json.decodeFromString<MutableMap<String, Entry>>(file.readText())
    }.getOrElse {
        Timber.w(it, "processed ledger unreadable; starting empty")
        mutableMapOf()
    }

    fun key(f: File): String = f.absolutePath

    fun isProcessed(map: Map<String, Entry>, f: File): Boolean {
        val e = map[key(f)] ?: return false
        return e.size == f.length() && e.lastModified == f.lastModified()
    }

    fun mark(map: MutableMap<String, Entry>, f: File) {
        map[key(f)] = Entry(f.length(), f.lastModified())
    }

    fun save(map: Map<String, Entry>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(map))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Timber.w(it, "could not persist processed ledger") }
    }
}
