package com.enderthor.kghost.engine

import com.enderthor.kghost.extension.jsonForStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class GhostCheckpointTest {
    @Test fun `scalar state round-trips`() {
        val cp = GhostCheckpoint(rideEpoch = 123L, leadS = 42.5, lastRiderDist = 1000.0, pick = GhostPick.BEST, vpTimePerM = 0.3, savedAtEpoch = 999L, routeHash = 777)
        val s = jsonForStorage.encodeToString(GhostCheckpoint.serializer(), cp)
        val back = jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), s)
        assertEquals(cp, back)
    }
}
