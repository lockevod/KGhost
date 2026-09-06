package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.import_.FitDecoder
import com.enderthor.kghost.import_.HistoryImporter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Opt-in host benchmark. Set KGHOST_BENCHMARK_FIT_DIR; reports timings, never asserts a time budget. */
class GradePaceRebuildBenchmarkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `measure a complete persisted library grade rebuild`() {
        val input = System.getenv("KGHOST_BENCHMARK_FIT_DIR")
        assumeTrue("Set KGHOST_BENCHMARK_FIT_DIR to a local FIT library", !input.isNullOrBlank())
        val files = File(input!!).walkTopDown().maxDepth(8)
            .filter { it.isFile && it.extension.equals("fit", ignoreCase = true) }.toList()
        val dir = tmp.newFolder()
        val store = TrackStore(dir)
        val sink = store.openBulkSink()
        var tracks = 0
        for (file in files) {
            val track = FitDecoder.decode(file, Source.FIT_IMPORT) ?: continue
            tracks += sink.addAll(listOf(HistoryImporter.defaultDecimate(track)))
        }
        sink.commit()
        assertTrue("The supplied library must contain usable rides", tracks > 0)
        val timesMs = ArrayList<Double>()
        repeat(4) { iteration ->
            val start = System.nanoTime()
            val builder = GradePace.Builder()
            store.forEachTrack(builder::add)
            val model = builder.build()
            GradePaceStore(dir).save(model)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            assertTrue("A rebuild must produce pace coverage", model.coveredM > 0)
            assertTrue("The rebuilt model must be readable", GradePaceStore(dir).load() != null)
            if (iteration > 0) timesMs += ms
        }
        println("GRADE_REBUILD files=${files.size} uniqueTracks=$tracks runsMs=$timesMs medianMs=${timesMs.sorted()[1]}")
    }
}
