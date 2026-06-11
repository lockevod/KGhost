package com.enderthor.kghost.geo

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
}
