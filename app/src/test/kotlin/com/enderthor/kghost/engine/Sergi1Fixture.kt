package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.import_.FitDecoder
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared fixture loader for tests that replay the maintainer's real ride (`sergi1.fit`, untracked,
 * dev-local, not committed). Skips cleanly (JUnit Assume) if the file is absent or fails to decode,
 * rather than failing loudly — the same behaviour [B2Sergi1ReplayTest] had inline before this was
 * extracted so [GradeFillReplayTest] could share it instead of duplicating it.
 *
 * Pull it to the repo root with: adb pull /sdcard/FitFiles/<ride>.fit sergi1.fit
 */
fun loadSergi1(): RecordedTrack {
    val file = File(System.getenv("SERGI1_FIT") ?: File(repoRoot(), "sergi1.fit").path)
    assumeTrue("sergi1.fit present at ${file.path}", file.exists())
    val track = FitDecoder.decode(file, Source.FIT_IMPORT)
    assumeTrue("sergi1.fit decoded", track != null)
    return requireNotNull(track)
}

/** Walk up from the JVM working directory to find the repo root (marked by settings.gradle.kts), so the
 *  fixture path isn't gated to gradle's test-task working directory (module dir vs. repo root) or to any
 *  one machine's home directory. Falls back to the starting directory if no marker is found nearby. */
private fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    repeat(6) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile ?: return dir
    }
    return dir
}
