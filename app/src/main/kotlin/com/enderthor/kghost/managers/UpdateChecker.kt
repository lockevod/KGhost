package com.enderthor.kghost.managers

import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.Serializable
import timber.log.Timber

/** The two fields of the published `app/manifest.json` the update notice needs. Defaults make a
 *  manifest missing them decode to "no update" rather than throw. */
@Serializable
data class UpdateManifest(
    val latestVersionCode: Int = 0,
    val latestVersion: String = "",
)

/** Pure decision logic for the update notice (ported from KSafe); the extension does the I/O. */
object UpdateChecker {

    /** A newer build exists iff the published versionCode is strictly greater than ours. */
    fun isNewer(latestVersionCode: Int, currentVersionCode: Int): Boolean =
        latestVersionCode > currentVersionCode

    /** Null on any malformed / non-JSON body (caller treats it as "no update"). */
    fun parseManifest(raw: String): UpdateManifest? =
        try {
            jsonWithUnknownKeys.decodeFromString<UpdateManifest>(raw)
        } catch (e: Exception) {
            Timber.d(e, "UpdateChecker: manifest parse failed (${raw.take(60)})")
            null
        }
}
