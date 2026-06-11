package com.enderthor.kghost

import android.content.Context
import android.os.Environment
import android.util.Log
import com.enderthor.kghost.managers.StoragePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A Timber tree that writes diagnostic logs to a FILE so a ride can be studied afterwards without
 * adb/logcat. Modelled on KSafe's CalibrationLogger threading — an in-memory ring buffer drained
 * by a background IO coroutine — so there is NO file I/O on the calling thread.
 *
 * Performance design:
 *  - A [BufferedWriter] is kept open for the duration of each file (one open/close per ride, not
 *    per flush). Each flush drains the buffer with a single [BufferedWriter.flush] syscall — no
 *    FileOutputStream open/close overhead every second.
 *  - The [buffer] uses a plain [synchronized] block (held for <1 µs on the log path) so Timber
 *    callers are never blocked on I/O.
 *
 * Data availability:
 *  - [FLUSH_INTERVAL_MS] = 1 s — data is on disk within one second of being logged, so the file
 *    can be read during a ride without waiting for it to end.
 *  - [newRide] triggers an immediate flush via a [Channel] signal so the ride-start banner is on
 *    disk within milliseconds of the ride beginning.
 *
 * Log organisation:
 *  - **One file per ride**: `kghost-YYYY-MM-dd-HHmmss.log`. Between-ride logs go to `kghost.log`.
 *  - **6-file cap**: oldest `.log` files are purged when the directory exceeds [MAX_LOG_FILES].
 *
 * Cost when [enabled] is false: a single volatile read per log call.
 */
object FileLogTree : Timber.Tree() {

    /** Toggled from the rider's config flow. Off by default. */
    @Volatile
    var enabled: Boolean = false

    private const val MAX_BUFFER = 4000
    private const val FLUSH_INTERVAL_MS = 1_000L   // 1 s: data on disk fast, visible mid-ride
    private const val IDLE_POLL_MS = 60_000L        // 60 s: slow poll while logging is OFF
    private const val MAX_LOG_FILES = 6

    private val buffer = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val rideFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())

    // Signals the flush loop to wake up immediately (e.g. on newRide).
    // CONFLATED: multiple signals before the loop wakes collapse to one flush.
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var started = false

    // ── Persistent writer — accessed ONLY from the flush loop (no lock needed) ──────────────────
    // Keeping the writer open across flushes avoids a FileOutputStream open/close on every 1-second
    // tick. The loop detects a logFile change (newRide sets a new path) and re-opens lazily.
    private var writer: BufferedWriter? = null
    private var writerFile: File? = null   // which file writer is currently opened for

    /**
     * Resolve the log directory and start the background flush loop. Call ONCE from
     * [KGhostApplication.onCreate].
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val dir = resolveDir(context)
        runCatching { dir.mkdirs() }
        logFile = File(dir, "kghost.log")
        scope.launch {
            while (true) {
                // Wait for either a flush signal (immediate) or the periodic interval.
                // kotlinx-coroutines select would be cleaner, but a simple try-receive + delay
                // keeps the loop readable and avoids the select DSL overhead.
                val signaled = runCatching {
                    flushSignal.tryReceive().isSuccess
                }.getOrDefault(false)
                if (!signaled) {
                    delay(if (enabled) FLUSH_INTERVAL_MS else IDLE_POLL_MS)
                }
                flush()
            }
        }
    }

    /**
     * Called once per genuinely-new ride start. Switches the log file to a fresh per-ride file,
     * writes the ride-start banner to the buffer, signals an immediate flush so the banner is on
     * disk within milliseconds, and schedules a purge of old files.
     *
     * No-op when [enabled] is false.
     */
    fun newRide(epochMs: Long) {
        if (!enabled) return
        val dir = logFile?.parentFile ?: return
        val stamp = rideFmt.format(Instant.ofEpochMilli(epochMs))
        logFile = File(dir, "kghost-$stamp.log")
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast("${ts.format(Instant.now())} I/KGhost: ===== RIDE START ($stamp) =====")
        }
        // Wake the flush loop immediately so banner + any pre-ride lines hit disk right away.
        flushSignal.trySend(Unit)
        scope.launch { purgeOldLogs(dir) }
    }

    /**
     * Prefer `/sdcard/KGhost/logs` when all-files access is granted; otherwise the app-scoped
     * external dir `…/Android/data/<pkg>/files/logs`.
     */
    private fun resolveDir(context: Context): File =
        if (StoragePermission.hasAllFilesAccess(context)) {
            File(Environment.getExternalStorageDirectory(), "KGhost/logs")
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!enabled) return
        val line = buildString {
            append(ts.format(Instant.now())); append(' ')
            append(levelChar(priority)); append('/')
            append(tag ?: "KGhost"); append(": ")
            append(message)
            if (t != null) { append('\n'); append(Log.getStackTraceString(t)) }
        }
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /**
     * Drain the buffer to the current log file.
     *
     * Called ONLY from the flush-loop coroutine (Dispatchers.IO), so [writer] / [writerFile]
     * need no synchronisation — there is exactly one writer at a time.
     */
    private fun flush() {
        val targetFile = logFile ?: return
        // Re-open the writer when the log file changes (newRide sets a new logFile).
        if (writerFile != targetFile) {
            runCatching { writer?.close() }
            writer = runCatching { FileWriter(targetFile, /* append= */ true).buffered() }.getOrNull()
            writerFile = targetFile
        }
        val w = writer ?: return
        val lines: List<String>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            lines = buffer.toList()
            buffer.clear()
        }
        runCatching {
            lines.forEach { line -> w.write(line); w.newLine() }
            w.flush()   // flush to OS; keep the writer open for the next cycle
        }.onFailure {
            // Writer broken — reset so the next cycle re-opens a fresh one.
            runCatching { writer?.close() }
            writer = null
            writerFile = null
        }
    }

    /** Delete the oldest `.log` files in [dir] so at most [MAX_LOG_FILES] remain. */
    private fun purgeOldLogs(dir: File) {
        runCatching {
            val logs = dir.listFiles { f -> f.name.endsWith(".log") } ?: return
            if (logs.size <= MAX_LOG_FILES) return
            logs.sortedBy { it.lastModified() }
                .take(logs.size - MAX_LOG_FILES)
                .forEach { it.delete() }
        }
    }

    private fun levelChar(p: Int): Char = when (p) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    /** Absolute path of the current log file, for the settings hint. */
    fun pathHint(): String = logFile?.absolutePath ?: "(not started yet)"
}
