package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.GRADE_SCHEMA_VERSION
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.engine.GradePaceDto
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File

/**
 * One-file persistence for the global [GradePace] model. Tiny (41 bins), rewritten only when an import
 * finishes, read once per route load.
 *
 * A stale schema or an unparseable file is DISCARDED, never partially trusted: the caller then falls back
 * to the neutral fill, which is a silence rather than a wrong verdict. Same policy as [AggregateStore].
 */
class GradePaceStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)

    fun load(): GradePace? {
        val f = file
        if (!f.isFile) return null
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<GradePaceDto>(f.readText())
                .takeIf { it.schemaVersion == GRADE_SCHEMA_VERSION }
                ?.let(GradePace::fromDto)
                ?: run {
                    Timber.i("grade-pace model is a stale schema; discarding (will rebuild on next import)")
                    null
                }
        }.getOrElse {
            Timber.w(it, "grade-pace model unreadable; discarding")
            null
        }
    }

    /**
     * True only if the model reached disk: [atomicWriteText] preserves the old file on an IO error and
     * reports it solely through its Boolean, so callers clearing a "model dirty" debt must see it.
     */
    fun save(model: GradePace): Boolean = runCatching {
        if (!dir.exists()) dir.mkdirs()
        atomicWriteText(file, jsonForStorage.encodeToString(model.toDto()))
    }.onFailure { Timber.w(it, "could not persist the grade-pace model") }.getOrDefault(false)

    companion object {
        const val FILE_NAME = "gradepace.json"
    }
}
