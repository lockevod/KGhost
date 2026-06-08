package com.enderthor.kghost.import_

data class ImportProgress(
    val phase: Phase,
    val current: Int,
    val total: Int,
    val imported: Int,
    val skippedDuplicates: Int,
    val failed: Int,
    val message: String? = null,
) {
    enum class Phase { SCANNING, PARSING, DONE, ERROR }
}
