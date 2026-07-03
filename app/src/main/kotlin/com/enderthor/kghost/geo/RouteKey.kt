package com.enderthor.kghost.geo

/**
 * Stable per-route key for the average-ghost aggregate: the loaded route's name (sanitized) plus its
 * length rounded to 100 m.
 *
 * A route loaded on the Karoo always carries a name (its route-file name), and the same route reloaded
 * has the same name and length — so this keys "the same route" across rides without any geometry math.
 * Length is rounded to 100 m so a trivial re-export jitter still maps to the same key. A name+length
 * collision between two genuinely different routes is rare and does not corrupt pace: the corridor
 * seed is recomputed for whichever route is currently loaded (from that route's own overlapping tracks),
 * so a collision only forces a re-seed each time you alternate the two — never a blended ghost.
 *
 * Pure; the result is also a safe file-name stem (lowercase, `[a-z0-9-]` only).
 */
fun routeKeyOf(name: String, routeLenM: Double): String {
    val sanitized = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)
    // A fully non-ASCII name (Cyrillic, CJK, …) sanitizes to empty. Fall back to a short stable hash of
    // the ORIGINAL name rather than a shared "route", so two different non-Latin routes of the same
    // rounded length get distinct keys instead of blending their aggregates.
    val stem = sanitized.ifEmpty {
        val raw = name.trim()
        if (raw.isEmpty()) "route" else "route-" + Integer.toHexString(raw.hashCode())
    }
    val len = (Math.round(routeLenM / 100.0) * 100).toInt()
    return "${stem}_$len"
}
