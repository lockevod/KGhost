package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.AGG_SCHEMA_VERSION
import com.enderthor.kghost.engine.AggregateNode
import com.enderthor.kghost.engine.PerRouteAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AggregateStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("agg-test").toFile()

    private fun sample(key: String) = PerRouteAggregate(
        routeKey = key,
        routeName = "Loop",
        routeLenM = 100.0,
        stepM = 25.0,
        schemaVersion = AGG_SCHEMA_VERSION,
        nodes = listOf(
            AggregateNode(0.0, 2),
            AggregateNode(5.0, 2),
            AggregateNode(10.0, 1),
        ),
    )

    @Test fun `save then load round-trips`() {
        val store = AggregateStore(tempDir())
        val agg = sample("loop_100")
        store.save(agg)
        assertEquals(agg, store.load("loop_100"))
    }

    @Test fun `load of a missing key is null`() {
        assertNull(AggregateStore(tempDir()).load("nope_0"))
    }

    @Test fun `corrupt file loads as null without throwing`() {
        val dir = tempDir()
        File(dir, "bad_1.json").writeText("{ this is not valid json")
        assertNull(AggregateStore(dir).load("bad_1"))
    }

    @Test fun `sweep deletes blobs older than maxAge and stale tmp files, keeps fresh`() {
        val dir = tempDir()
        val store = AggregateStore(dir)
        store.save(sample("fresh_100"))
        store.save(sample("stale_100"))
        val now = System.currentTimeMillis()
        File(dir, "stale_100.json").setLastModified(now - AggregateStore.SWEEP_MAX_AGE_MS - 1_000L)
        File(dir, "leftover.json.tmp").apply { writeText("x"); setLastModified(now - 25 * 3600_000L) }

        val deleted = store.sweep(nowMs = now)

        assertEquals(2, deleted) // the stale blob + the day-old tmp
        assertEquals(sample("fresh_100"), store.load("fresh_100"))
        assertNull(store.load("stale_100"))
    }

    @Test fun `sweep prunes least-recently-updated beyond maxFiles`() {
        val dir = tempDir()
        val store = AggregateStore(dir)
        val now = System.currentTimeMillis()
        for (i in 1..4) {
            store.save(sample("r${i}_100"))
            // Distinct ascending mtimes: r1 oldest … r4 newest.
            File(dir, "r${i}_100.json").setLastModified(now - (10L - i) * 60_000L)
        }

        val deleted = store.sweep(maxFiles = 2, nowMs = now)

        assertEquals(2, deleted)
        assertNull(store.load("r1_100"))
        assertNull(store.load("r2_100"))
        assertEquals(sample("r3_100"), store.load("r3_100"))
        assertEquals(sample("r4_100"), store.load("r4_100"))
    }

    @Test fun `sweep on a missing dir returns 0`() {
        assertEquals(0, AggregateStore(File(tempDir(), "absent")).sweep())
    }

    @Test fun `load rejects an aggregate from a stale schema version`() {
        val dir = tempDir()
        val store = AggregateStore(dir)
        File(dir, "route-x.json").writeText(
            """{"routeKey":"route-x","routeName":"X","routeLenM":100.0,"stepM":25.0,
               "nodes":[{"timeS":0.0,"count":0},{"timeS":5.0,"count":3}]}""".trimIndent(),
        )
        assertNull("stale-schema blob must be discarded (will re-seed)", store.load("route-x"))
    }
}
