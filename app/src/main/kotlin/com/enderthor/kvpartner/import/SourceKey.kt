package com.enderthor.kvpartner.import_

/**
 * Stable dedup key for a track from any source. Buckets the start time to the minute and the total
 * distance into 10 m buckets so the same ride ingested twice (e.g. auto-recorded by ② AND present
 * in /sdcard/FitFiles) collapses to one key. Tracks with the same key are treated as duplicates.
 */
fun sourceKeyOf(startedAtEpoch: Long, totalDistanceM: Double): String {
    val minute = startedAtEpoch / 60_000L
    val distBucket = (totalDistanceM / 10.0).toLong()
    return "$minute:$distBucket"
}
