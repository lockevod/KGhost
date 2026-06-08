package com.enderthor.kghost

import android.content.Context
import android.os.Environment
import android.util.Log
import com.enderthor.kghost.managers.StoragePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A Timber tree that writes the diagnostic logs to a FILE so a ride can be studied afterwards without
 * adb/logcat (you can't tether the Karoo to a laptop on a route). Modelled on KSafe's CalibrationLogger
 * threading — an in-memory ring buffer drained by a background IO coroutine — so there is NO file I/O on
 * the calling thread (the ~1 Hz tick, the ~5 Hz map loop, the main thread). It is a general Timber tree,
 * so it captures every existing `Timber.*` call without touching the ~50 log sites.
 *
 * Improvements over KSafe's logger for this use case (riders don't ride every five minutes, so a ride's
 * log must survive the daily Karoo reboot / a process restart):
 *  - **Append across sessions** (never truncates on start), with a session-start marker so ride
 *    boundaries are findable.
 *  - **Size-based rotation** (`kghost.log` → `.1` → `.2`), so disk stays bounded (~3 files × [MAX_BYTES]).
 *
 * Cost when [enabled] is false: a single volatile read per log call — the buffer is never touched, no
 * string is built. The tree is always planted; the rider turns it on from settings (default OFF), so
 * normal users pay nothing.
 */
object FileLogTree : Timber.Tree() {

    /** Toggled from the rider's config flow. Off by default. */
    @Volatile
    var enabled: Boolean = false

    private const val MAX_BUFFER = 4000               // bounded RAM (~ a few hundred KB of lines)
    private const val FLUSH_INTERVAL_MS = 5_000L      // append at most ~every 5 s (cheap, low loss on a kill)
    private const val MAX_BYTES = 3L * 1024 * 1024    // rotate the current file past 3 MB
    private const val FILE_NAME = "kghost.log"

    private val buffer = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var started = false

    /**
     * Resolve the log directory and start the background flush loop. Call ONCE from
     * [KGhostApplication.onCreate]. Safe to call before [enabled] is ever set true — the flush loop
     * just finds an empty buffer and does nothing until logging is turned on.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val dir = resolveDir(context)
        runCatching { dir.mkdirs() }
        logFile = File(dir, FILE_NAME)
        scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * Prefer `/sdcard/KGhost/logs` (next to the rider's tracks — easy to find with a file manager or
     * over USB) when all-files access is granted; otherwise the app-scoped external dir
     * `…/Android/data/<pkg>/files/logs` (always writable, no permission, pullable via adb/MTP).
     */
    private fun resolveDir(context: Context): File =
        if (StoragePermission.hasAllFilesAccess(context)) {
            File(Environment.getExternalStorageDirectory(), "KGhost/logs")
        } else {
            // getExternalFilesDir can be null if external storage isn't mounted (rare on a Karoo); fall
            // back to internal so we never resolve a bogus relative path that silently swallows writes.
            File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!enabled) return
        val line = buildString {
            append(ts.format(Instant.now())); append(' ')
            append(levelChar(priority)); append('/')
            append(tag ?: "KGhost"); append(": ")
            append(message)
            if (t != null) {
                append('\n'); append(Log.getStackTraceString(t))
            }
        }
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst() // drop oldest under a sustained flood
            buffer.addLast(line)
        }
    }

    /** Drain the buffer to the file on the IO coroutine, rotating if it grew past [MAX_BYTES]. */
    private fun flush() {
        val file = logFile ?: return
        val lines: List<String>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            lines = buffer.toList()
            buffer.clear()
        }
        // A failed flush must never crash the ride; the dropped lines are simply lost.
        runCatching {
            file.appendText(lines.joinToString("\n", postfix = "\n"))
            if (file.length() > MAX_BYTES) rotate(file)
        }
    }

    /** current → .1 → .2, dropping the oldest. The next append re-creates a fresh current file. */
    private fun rotate(file: File) {
        val parent = file.parentFile ?: return
        File(parent, "$FILE_NAME.2").delete()
        File(parent, "$FILE_NAME.1").renameTo(File(parent, "$FILE_NAME.2"))
        file.renameTo(File(parent, "$FILE_NAME.1"))
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
