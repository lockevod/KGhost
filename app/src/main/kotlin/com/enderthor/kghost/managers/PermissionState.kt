package com.enderthor.kghost.managers

/**
 * Cached "all-files access is missing" flag for the data fields. The field heartbeat and every frame
 * ask it, and a real check is a system call, so it is re-run at most once per [ttlMs]. Shared by the
 * several startView coroutines of one data type, hence synchronized.
 */
class PermissionState(
    private val check: () -> Boolean,
    private val nowMs: () -> Long,
    private val ttlMs: Long = 5_000,
) {
    private var checkedAt = 0L
    private var granted = false
    private var seen = false

    @Synchronized
    fun missing(): Boolean {
        val now = nowMs()
        if (!seen || now - checkedAt >= ttlMs) {
            granted = check()
            checkedAt = now
            seen = true
        }
        return !granted
    }
}

/** The preview (profile-editor gallery) always shows the sample, never a permission notice. */
fun permissionNotice(missing: Boolean, preview: Boolean): Boolean = missing && !preview
