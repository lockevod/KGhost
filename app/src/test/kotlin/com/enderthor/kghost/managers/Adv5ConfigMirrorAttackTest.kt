package com.enderthor.kghost.managers

import com.enderthor.kghost.data.CONFIG_VERSION
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.ProfileSetting
import com.enderthor.kghost.data.migrateToLatest
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.geo.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADVERSARIAL suite against commit 220ab22 (DataStore corruption handler + config mirror), kept as
 * the regression lock for the hardening that followed. Attacks the mirror as a second copy of the
 * truth: staleness, ordering, tearing, and what the "mirror exists ⇒ restore" signal actually proves.
 *
 * The DataStore delegate itself has no JVM harness (needs a real Context + preferences file), so the
 * DataStore-shaped attacks are run against a faithful MODEL of `updateConfig`'s structure — the
 * mirror written INSIDE the serialised edit, which is the shape of the production code. Because a
 * model can drift from the code it models, the ordering test is paired with a structural assertion
 * on the production source. Where a claim can only be checked on-device it is stated, not faked.
 */
class Adv5ConfigMirrorAttackTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * The production source of [ConfigurationManager]. Two of the hazards here live in the SHAPE of
     * code that needs a real Context to run — where the mirror write sits relative to the serialised
     * edit, and whether `loadConfigFlow`'s catch reaches the mirror. A model can drift from the code
     * it models, so those two are additionally pinned by reading the source.
     */
    private fun configManagerSource(): String = listOf(
        "src/main/kotlin/com/enderthor/kghost/managers/ConfigurationManager.kt",
        "app/src/main/kotlin/com/enderthor/kghost/managers/ConfigurationManager.kt",
    ).map(::File).firstOrNull { it.isFile }
        ?.readText()
        ?: error("ConfigurationManager.kt not found from ${File(".").absolutePath}")

    private val saved = KGhostConfig(
        targetSpeedMs = 7.5,
        masterEnabled = false,
        lastScanEpoch = 1_700_000_000_000L,
        tidySweepEpoch = 1_700_000_000_000L,
    )

    // ───────────────────────────────────────────────────────────────────────────
    // H1 — Two concurrent mirror writes share atomicWriteText's fixed `.tmp` name.
    // AggregateStore already documents this exact hazard and takes a lock for it
    // (AggregateStore.kt:20-24); [writeMirrorFile] now takes the same kind of lock.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Reproduces the shared-temp race with the mirror's own call shape (fsync = true, one fixed
     * target) against the production write helper. If the mirror can ever be observed as anything
     * other than one of the two writers' payloads, "a kill mid-mirror can't produce a torn mirror"
     * is not the whole story: a CONCURRENT mirror write can.
     */
    @Test fun `concurrent mirror writes to one target never expose a torn or empty mirror`() {
        val target = File(tmp.newFolder("filesDir"), CONFIG_MIRROR_FILE)
        val a = jsonForStorage.encodeToString(saved.copy(targetSpeedMs = 7.5))
        // A realistically larger blob: profileSettings makes a real rider's config kilobytes long.
        val b = "{\"targetSpeedMs\":4.0,\"pad\":\"" + "x".repeat(200_000) + "\"}"

        val bad = AtomicInteger(0)
        val observations = AtomicInteger(0)
        repeat(40) {
            val start = CountDownLatch(1)
            val w1 = Thread { start.await(); writeMirrorFile(target, a) }
            val w2 = Thread { start.await(); writeMirrorFile(target, b) }
            val reader = Thread {
                start.await()
                repeat(200) {
                    if (target.isFile) {
                        val t = runCatching { target.readText() }.getOrNull()
                        if (t != null) {
                            observations.incrementAndGet()
                            if (t != a && t != b) bad.incrementAndGet()
                        }
                    }
                }
            }
            listOf(w1, w2, reader).forEach { it.start() }
            start.countDown()
            listOf(w1, w2, reader).forEach { it.join(10_000) }
        }

        println(
            "H1 concurrent-mirror-write: $observations reads, ${bad.get()} were neither payload " +
                "(final len=${target.length()})",
        )
        assertEquals(
            "atomicWriteText's fixed `<name>.tmp` is shared by concurrent writers to the same " +
                "target, so a reader could see a truncated/mixed mirror. writeMirrorFile must " +
                "serialise per target (AggregateStore's idiom) to close that window.",
            0,
            bad.get(),
        )
    }

    /**
     * The consequence a torn mirror has for the RESTORE decision: a zero-byte or truncated mirror is
     * still `isFile == true`, so `readMirror()` returns a non-null String and the "mirror exists ⇒
     * this is a corruption restore, not a first run" signal still fires — but [restoredFromMirror]
     * then hands back null and the rider silently gets DEFAULTS. The safety net exists and is empty.
     */
    @Test fun `a zero-byte mirror still reads as present but restores nothing`() {
        val dir = tmp.newFolder("filesDir2")
        val mirror = File(dir, CONFIG_MIRROR_FILE)
        mirror.writeText("")
        // readMirror()'s contract: `takeIf { it.isFile }?.readText()` — present ⇒ "" , not null.
        val asReadMirrorWouldSeeIt: String? = mirror.takeIf { it.isFile }?.readText()
        assertEquals("", asReadMirrorWouldSeeIt)
        assertNull(restoredFromMirror(asReadMirrorWouldSeeIt))
        // …and the write path therefore persists DEFAULTS into the just-reset store.
        assertEquals(
            KGhostConfig().migrateToLatest(),
            runBlocking { configForUpdate(null) { asReadMirrorWouldSeeIt } },
        )
    }

    /** A mirror truncated mid-JSON behaves the same way — present, unusable, silent defaults. */
    @Test fun `a truncated mirror restores nothing`() {
        val full = jsonForStorage.encodeToString(saved)
        assertNull(restoredFromMirror(full.take(full.length / 2)))
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H2 — updateConfig must write the mirror INSIDE the serialised edit. Written
    // after it, two concurrent updateConfig calls can land their mirror writes out
    // of order and leave an OLDER config in the mirror than in the store.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Faithful model of `ConfigurationManager.updateConfig`:
     *   edit { read; transform; write; writeMirror(next) }   ← all serialised by DataStore
     * The model tries to force the interleaving the OLD shape permitted — writer A's mirror write
     * delayed past writer B's — and cannot, because the mirror write no longer happens outside the
     * lock. The latch that used to sequence the two mirror writes in reverse now sequences nothing.
     */
    @Test fun `mirror can never end up older than the store when two updates interleave`() = runBlocking {
        val editLock = Mutex()
        var store: String? = jsonForStorage.encodeToString(KGhostConfig())
        var mirror: String? = null

        // Both writers reach this before either leaves its edit, so the scheduler is free to order
        // the two edits either way — whichever edits last must also mirror last.
        val bothStarted = CountDownLatch(2)

        suspend fun updateConfig(transform: (KGhostConfig) -> KGhostConfig) {
            bothStarted.countDown()
            bothStarted.await(5, TimeUnit.SECONDS)
            editLock.withLock { // ← DataStore.edit serialises here
                val current = configForUpdate(store) { mirror }
                val next = jsonForStorage.encodeToString(transform(current))
                store = next
                mirror = next // ← writeMirror, INSIDE the edit
            }
        }

        withContext(Dispatchers.IO) {
            listOf(
                // A: the in-ride permission-alert counter / import lastScanEpoch writer.
                async { updateConfig { it.copy(targetSpeedMs = 4.0) } },
                // B: the rider toggling a switch in the settings screen.
                async { updateConfig { it.copy(masterEnabled = false) } },
            ).awaitAll()
        }

        val inStore = jsonForStorage.decodeFromString<KGhostConfig>(store!!)
        val inMirror = jsonForStorage.decodeFromString<KGhostConfig>(mirror!!)
        println("H2 store=$store\nH2 mirror=$mirror")
        assertEquals(
            "the mirror must never hold a config older than the store — a writeMirror outside the " +
                "edit lock means the last edit is not necessarily the last mirror write",
            inStore,
            inMirror,
        )
        // Both writers' changes survive: the merge is what the serialised edit buys.
        assertEquals(4.0, inMirror.targetSpeedMs, 1e-9)
        assertFalse(inMirror.masterEnabled)
    }

    /**
     * The model above can only prove the SHAPE is safe; this pins the production code to that shape.
     * `writeMirror(mirrorFile, next)` must sit at the edit-lambda's indentation (16 spaces), i.e.
     * inside `context.dataStore.edit { … }` — not dedented back out after it.
     */
    @Test fun `updateConfig mirrors from inside the serialised edit block`() {
        val text = configManagerSource()
        assertTrue(
            "writeMirror must be called INSIDE context.dataStore.edit {} (16-space indent). Moving " +
                "it out re-opens H2: two concurrent updates can mirror in the opposite order to " +
                "their edits and leave the rider's latest change missing from the mirror.",
            text.contains("\n                writeMirror(mirrorFile, next)"),
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H3 — schema / migration on restored content.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * `jsonForStorage` is the DEFAULT Json instance: `encodeDefaults = false`, so without an explicit
     * `@EncodeDefault` a config at the current version would write NO `version` field at all, and any
     * later CONFIG_VERSION bump would decode every existing mirror (and every existing store blob, it
     * is the same encoder) as "already at the newest version" with [migrateToLatest] running NOTHING.
     *
     * Blobs written BEFORE this fix still carry no version and so still read as [CONFIG_VERSION];
     * each is stamped on its next write, which any settings change performs.
     */
    @Test fun `a mirror records the schema version it was written at`() {
        val text = jsonForStorage.encodeToString(saved)
        println("H3 mirror text = $text")
        assertTrue(
            "the mirror must record the schema version it was written at, otherwise the next " +
                "CONFIG_VERSION bump silently skips every migration on restored content",
            text.contains("\"version\":$CONFIG_VERSION"),
        )
        // …and an all-defaults config too — that is the blob most likely to be missing the field.
        assertTrue(jsonForStorage.encodeToString(KGhostConfig()).contains("\"version\":$CONFIG_VERSION"))
        // Round-trip: what is written is what the restore path reads back.
        assertEquals(CONFIG_VERSION, restoredFromMirror(text)!!.version)
    }

    /** An explicitly-old mirror DOES migrate — the migration path itself is wired up correctly. */
    @Test fun `an explicitly older mirror is migrated on restore`() = runBlocking {
        val old = jsonForStorage.encodeToString(KGhostConfig(version = 2, targetSpeedMs = 0.0))
        assertTrue(old.contains("\"version\":2"))
        // restoredFromMirror alone does NOT migrate…
        assertEquals(2, restoredFromMirror(old)!!.version)
        // …configForUpdate does, and loadConfigFlow has a trailing `.map { it.migrateToLatest() }`.
        val migrated = configForUpdate(null) { old }
        assertEquals(CONFIG_VERSION, migrated.version)
        assertTrue("v2→v3 must lift a zeroed target to the 12 km/h default", migrated.targetSpeedMs > 0)
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H4 — what "mirror exists" actually proves.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * A rider whose settings are all at defaults mirrors nothing but the version stamp (encodeDefaults
     * = false). That is a PRESENT mirror carrying no settings — the restore signal fires and hands
     * back defaults. Harmless in outcome, but it means mirror-existence is not evidence that any
     * setting was ever saved.
     */
    @Test fun `an all-defaults config mirrors as just the version stamp and still signals restore`() {
        val text = jsonForStorage.encodeToString(KGhostConfig())
        assertEquals("{\"version\":$CONFIG_VERSION}", text)
        assertNotNull(restoredFromMirror(text))
        assertEquals(KGhostConfig(), restoredFromMirror(text))
    }

    /**
     * The restore is READ-ONLY: nothing writes the mirror back into the emptied store. Until the
     * next `updateConfig`, every emission of `dataStore.data` re-reads the mirror off disk. Modelled
     * here as: an empty store stays empty across reads.
     */
    @Test fun `restore never re-seeds the store, so every read re-hits the mirror`() = runBlocking {
        var mirrorReads = 0
        val mirror = jsonForStorage.encodeToString(saved)
        repeat(5) {
            // loadConfigFlow's map: raw == null ⇒ restoredFromMirror(readMirror())
            val raw: String? = null
            val cfg = if (raw == null) restoredFromMirror(run { mirrorReads++; mirror }) else null
            assertEquals(7.5, cfg!!.targetSpeedMs, 1e-9)
        }
        assertEquals("no self-heal: the mirror is read on every emission", 5, mirrorReads)
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H5 — the excluded fields, and what the reset destroys that the mirror misses.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Zeroing `tidySweepEpoch` is sufficient to re-arm the sweep ONLY when `autoTidy` is true
     * (KGhostExtension.kt:850 — `cfg.autoTidy && cfg.tidySweepEpoch == 0L`). `autoTidy` IS restored,
     * so a rider who turned it off keeps it off. Confirms the exclusion is correctly gated.
     */
    @Test fun `zeroing tidySweepEpoch re-arms the sweep only while autoTidy is on`() {
        val on = restoredFromMirror(jsonForStorage.encodeToString(saved.copy(autoTidy = true)))!!
        assertTrue(on.autoTidy && on.tidySweepEpoch == 0L)
        val off = restoredFromMirror(jsonForStorage.encodeToString(saved.copy(autoTidy = false)))!!
        assertFalse(off.autoTidy)
        assertEquals(0L, off.tidySweepEpoch)
    }

    /**
     * The install id ("Anon tag") is a SEPARATE preferences key, not part of the config blob, so a
     * corruption reset would regenerate it and break a maintainer's ability to group a device's
     * diagnostic logs across exactly the incident worth investigating. It gets its OWN mirror rather
     * than being folded into [KGhostConfig] — six hex characters need no schema, and it must not
     * become a second source of truth inside the config the settings screens write.
     */
    @Test fun `the install id is mirrored outside the config blob`() {
        val fields = KGhostConfig::class.java.declaredFields.map { it.name }
        assertTrue(
            "the install id must stay out of the config blob — it has its own mirror",
            fields.none { it.contains("install", ignoreCase = true) },
        )
        val f = File(tmp.newFolder("installIdDir"), INSTALL_ID_MIRROR_FILE)
        // Absent (genuine first run) ⇒ nothing to reuse, caller generates a fresh tag.
        assertNull(installIdFromMirror(f.takeIf { it.isFile }?.readText()))
        writeMirrorFile(f, "a1b2c3")
        // Present (corruption reset) ⇒ the SAME tag comes back, so the logs stay groupable.
        assertEquals("a1b2c3", installIdFromMirror(f.readText()))
    }

    /** The mirror is a trust boundary like any other file on disk: only a real 6-hex tag is reused. */
    @Test fun `a damaged install-id mirror is discarded rather than reused`() {
        // A torn/zero-byte write, a truncated tag, an over-long one, or non-hex all regenerate.
        for (bad in listOf(null, "", "   ", "a1b2c", "a1b2c3d", "A1B2C3", "zzzzzz", "{\"id\":1}")) {
            assertNull("must not reuse $bad as an Anon tag", installIdFromMirror(bad))
        }
        // Trailing newline from any text editor / shell redirect is tolerated.
        assertEquals("00ff9e", installIdFromMirror("00ff9e\n"))
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H6 — the write-path guard extraction: is configForUpdate exactly the old inline code?
    // ───────────────────────────────────────────────────────────────────────────

    /** The pre-220ab22 inline guard, transcribed verbatim from the diff's `-` lines. */
    private fun legacyConfigForUpdate(raw: String?): KGhostConfig =
        if (raw == null) {
            KGhostConfig()
        } else {
            com.enderthor.kghost.extension.jsonWithUnknownKeys.decodeFromString<KGhostConfig>(raw)
        }.migrateToLatest()

    @Test fun `configForUpdate matches the old inline guard on every non-empty store`() = runBlocking {
        val blobs = listOf(
            jsonForStorage.encodeToString(saved),
            jsonForStorage.encodeToString(KGhostConfig()),
            jsonForStorage.encodeToString(KGhostConfig(version = 1, targetSpeedMs = 0.0)),
            "{\"ghostPick\":\"NO_SUCH_ENUM\"}", // coerceInputValues path
            "{\"targetSpeedMs\":9.0,\"unknownFutureKey\":123}", // ignoreUnknownKeys path
        )
        for (raw in blobs) {
            assertEquals(
                "raw=$raw",
                legacyConfigForUpdate(raw),
                configForUpdate(raw) { error("mirror must not be consulted for a present blob") },
            )
        }
        // Structurally broken blob: BOTH must throw, neither may fall back.
        for (raw in listOf("{\"targetSpeedMs\":", "not json at all", "[]")) {
            assertTrue("legacy raw=$raw", runCatching { legacyConfigForUpdate(raw) }.isFailure)
            assertTrue(
                "new raw=$raw",
                runCatching { runBlocking { configForUpdate(raw) { jsonForStorage.encodeToString(saved) } } }.isFailure,
            )
        }
    }

    /**
     * The ONE deliberate behaviour change: on an EMPTY store the old code seeded the transform from
     * defaults, the new one seeds it from the mirror. Pinned so a later edit cannot quietly widen it
     * to the non-empty case (which would be the "stale settings written over good ones" route).
     */
    @Test fun `the empty-store write path is the only place the mirror can reach the store`() = runBlocking {
        assertEquals(KGhostConfig().migrateToLatest(), legacyConfigForUpdate(null))
        val viaMirror = configForUpdate(null) { jsonForStorage.encodeToString(saved) }
        assertEquals(7.5, viaMirror.targetSpeedMs, 1e-9)
        assertFalse(viaMirror.masterEnabled)
        // …and the epochs are still zeroed on the way through the write path.
        assertEquals(0L, viaMirror.lastScanEpoch)
        assertEquals(0L, viaMirror.tidySweepEpoch)
    }

    // ───────────────────────────────────────────────────────────────────────────
    // H7 — the hole the mirror does NOT cover: an upstream read failure.
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * `loadConfigFlow`'s `.catch` sits DOWNSTREAM of the `.map` that consults the mirror. The
     * corruption handler stops CorruptionException reaching it, but any other upstream IOException
     * lands there having never read the mirror — so the catch must consult it too, or an unreadable
     * store degrades to raw DEFAULTS with a perfectly good copy of the settings sitting on disk.
     * Modelled with the same flow shape.
     */
    @Test fun `an upstream read failure recovers from the mirror instead of defaults`() = runBlocking {
        var mirrorConsulted = false
        fun mirror(): String? = run { mirrorConsulted = true; jsonForStorage.encodeToString(saved) }

        val emitted = flow<String?> { throw java.io.IOException("boom") }
            .map { raw -> if (raw == null) restoredFromMirror(mirror()) ?: KGhostConfig() else KGhostConfig() }
            .catch { emit(restoredFromMirror(mirror()) ?: KGhostConfig()) } // ← production's catch
            .first()

        assertTrue("the mirror must not be bypassed on an upstream failure", mirrorConsulted)
        assertEquals(
            "an IO failure should recover from the mirror too, not fall to defaults",
            7.5,
            emitted.targetSpeedMs,
            1e-9,
        )
        // Defaults remain the LAST resort: an unusable mirror must still emit, never hang or throw.
        val noMirror = flow<String?> { throw java.io.IOException("boom") }
            .map { KGhostConfig() }
            .catch { emit(restoredFromMirror(null) ?: KGhostConfig()) }
            .first()
        assertEquals(KGhostConfig(), noMirror)

        // …and the production catch really is the mirror-consulting one, not a bare defaults emit.
        assertTrue(
            "loadConfigFlow's .catch must consult the mirror. It sits downstream of the map that " +
                "does, so a bare `emit(KGhostConfig())` there hands the rider defaults on any " +
                "non-corruption IOException while their settings sit readable on disk.",
            configManagerSource().contains("emit(restoredFromMirror(readMirror(mirrorFile)) ?: KGhostConfig())"),
        )
    }

    /** Counts from one race harness run: reads, non-payload reads, zero-byte reads, unparseable ones. */
    private data class RaceCounts(val reads: Int, val neither: Int, val empty: Int, val unparseable: Int) {
        override fun toString() = "reads=$reads neither=$neither empty=$empty unparseable=$unparseable"
    }

    /** 200 races of two writers + one reader over one target, using [write] as the write helper. */
    private fun race(target: File, a: String, b: String, write: (File, String) -> Unit): RaceCounts {
        val neither = AtomicInteger(0); val empty = AtomicInteger(0)
        val reads = AtomicInteger(0); val unparseable = AtomicInteger(0)
        repeat(200) {
            val gate = CountDownLatch(1)
            val ts = listOf(
                Thread { gate.await(); write(target, a) },
                Thread { gate.await(); write(target, b) },
                Thread {
                    gate.await()
                    repeat(300) {
                        if (target.isFile) {
                            val t = runCatching { target.readText() }.getOrNull() ?: return@repeat
                            reads.incrementAndGet()
                            if (t != a && t != b) {
                                neither.incrementAndGet()
                                if (t.isEmpty()) empty.incrementAndGet()
                                if (runCatching { jsonForStorage.decodeFromString<KGhostConfig>(t) }.isFailure) {
                                    unparseable.incrementAndGet()
                                }
                            }
                        }
                    }
                },
            )
            ts.forEach { it.start() }; gate.countDown(); ts.forEach { it.join(10_000) }
        }
        return RaceCounts(reads.get(), neither.get(), empty.get(), unparseable.get())
    }

    /**
     * The measurement that motivated the per-target lock, kept runnable so the numbers can be
     * reproduced: the same race on the RAW [atomicWriteText] (which the mirror used to call directly)
     * and on the locked [writeMirrorFile]. The raw run is reported, not asserted — it is a race, and
     * a machine that happens not to interleave proves nothing. The locked run must be spotless: a
     * zero-byte mirror is the damaging outcome, because it still reads as "present" and so fires the
     * restore signal while restoring nothing.
     */
    @Test fun `a realistic config blob never tears under locked concurrent mirror writes`() {
        val profiles = (1..40).map { ProfileSetting(profileId = "profile-$it", profileName = "Ride profile number $it") }
        val a = jsonForStorage.encodeToString(KGhostConfig(targetSpeedMs = 7.5, profileSettings = profiles))
        val b = jsonForStorage.encodeToString(KGhostConfig(targetSpeedMs = 4.0, profileSettings = profiles.take(3)))
        println("probe sizes: a=${a.length}B b=${b.length}B")

        val before = race(File(tmp.newFolder("raw"), CONFIG_MIRROR_FILE), a, b, ::atomicWriteText)
        println("probe BEFORE (raw atomicWriteText): $before")
        val after = race(File(tmp.newFolder("locked"), CONFIG_MIRROR_FILE), a, b) { f, t -> writeMirrorFile(f, t) }
        println("probe AFTER  (locked writeMirrorFile): $after")

        assertTrue("the harness must actually have observed the mirror", after.reads > 0)
        assertEquals(
            "a locked mirror write must never be observable as anything but one of the two whole " +
                "payloads — a torn one is a zero-byte mirror waiting for a ride-end process kill",
            0,
            after.neither,
        )
    }

}
