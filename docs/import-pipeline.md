# Import & storage pipeline — library, permission, performance

How KGhost turns FIT/GPX files into the recorded-ghost library, how that library survives a reinstall,
how it warns when it can't read your files, and how it stays fast at ~1200 rides. This is the subsystem
*upstream* of the racing model in [`route-ghost-model.md`](route-ghost-model.md): it produces the
recorded tracks the per-route grid (§7 there) is seeded from. You don't need any of this to use the app.

Entry points: `import/HistoryImporter.kt` (the sweep), `import/HistoryImportRunner.kt` (process-scoped
runner), `geo/TrackStore.kt` + `geo/TrackStorage.kt` (storage), `import/FitDecoder.kt` /
`import/GpxParser.kt` (parsers), `import/ProcessedLedger.kt` (skip-already-done), and
`managers/StoragePermission.kt` + `managers/PermissionAlertSchedule.kt` + `screens/PermissionBanner.kt`
(the all-files-access gate and its reminders).

## 1. Storage layout & the reinstall behaviour

Processed rides are **plain JSON files on disk**, not a database. `TrackStorage.tracksDir(context)`
resolves the library root:

- **All-files access granted** → `/sdcard/KGhost/tracks` (shared external storage — **survives an
  uninstall/reinstall**).
- **Not granted** → internal `filesDir/tracks` (a private fallback that **is wiped on reinstall**).

Inside `tracks/`: one `<id>.json` per track, plus the bookkeeping — `index.json` (a coarse spatial
index for candidate lookup), `sourcekeys.json` (dedup keys), `.pathcells` (a migration marker),
`processed.json` (the import ledger, §4), and an `archive/` subdir for auto-cleaned rides.

`sourcekeys.json` is a **derived cache**: every key in it is the `sourceKey` of a stored track, so absent
and corrupt mean the same thing — recompute it from the library (live **and** `archive/`, since archiving
deliberately keeps a track's key). Completeness gates only PERSISTENCE: if a directory that exists will
not list, or a file cannot be read, the recomputed set is still deduped against but is not written back,
so it can never erase what it could not see. A file that reads but does not decode is not a track — it
holds no key, and its ride comes back by re-importing its source file.

**Why a reinstall shows 0 rides — and why the count now self-heals.** On reinstall `MANAGE_EXTERNAL_STORAGE`
is revoked, so `tracksDir` resolves to the (wiped) internal fallback → the count reads 0 even though the
processed tracks are still sitting in `/sdcard/KGhost/tracks`. The recorded-track count in
`activity/MainActivity.kt` is Compose state (`trackCount`) recomputed by `LaunchedEffect(refreshKey)`
(on import completion) **and by a `LifecycleResumeEffect` that bumps `refreshKey` on every `ON_RESUME`**.
Returning from the system all-files-access screen fires `ON_RESUME`, so once you grant access the count
recomputes against the now-readable external library immediately — no reprocessing, no manual import.
`dedup by sourceKey` is why a post-reinstall "import all" only adds the handful of genuinely-new rides:
the rest are already present.

## 2. The all-files-access gate and its reminders

`StoragePermission.hasAllFilesAccess` is `Environment.isExternalStorageManager()` on API 30+ (legacy
`WRITE_EXTERNAL_STORAGE` below). Without it KGhost can read neither the Karoo's own `/sdcard/FitFiles`
nor user-dropped `/sdcard/KGhost` files, so it loads **no** recorded ghosts and runs as a fixed-pace
virtual partner only. Because that silently halves what the app does, the missing permission is surfaced
two ways:

- **Passive banner** — `screens/PermissionBanner.kt` exposes `PermissionWarningBanner()` (an
  error-container card + grant button) and `rememberHasAllFilesAccess()` (re-checks on `ON_RESUME`).
  It renders on the **main screen** (`SettingsScreen`) as well as inside the import section
  (`RaceScreen`); it disappears the moment access is granted.
- **Occasional in-ride reminder** — on entering `RideState.Recording`, `KGhostExtension`'s
  `maybeAlertMissingPermission()` fires one `InRideAlert` under a **decaying schedule**
  (`managers/PermissionAlertSchedule.kt`): the first `INITIAL_BURST` (3) rides nudge at most once per
  `SHORT_THROTTLE_MS` (72 h); afterwards it backs off to `LONG_THROTTLE_MS` (10 days), so the intrusive
  channel is insistent early then quiet, while the always-visible banner carries the persistent
  reminder underneath.

Load-bearing details of the schedule:

- **State is persisted as wall-clock** (`permAlertFiredCount` / `permAlertLastFiredEpoch` in
  `KGhostConfig`, schema **v8**) — it must survive process death and reboot, so this is the deliberate
  exception to KGhost's monotonic-clock rule for in-process intervals.
- **Backward-clock guard.** The throttle test is `elapsed in 0L until throttle`, not `elapsed < throttle`.
  On the Karoo the wall clock can jump **backward** (GPS time correction, or a FIT-replay test), which
  would make `now − lastFired` negative and — with a bare `< throttle` — wrongly suppress the reminder
  until real time caught up. The range check treats a negative elapsed as "fire".
- **Gated** on `masterEnabled` and on the permission actually being missing; the pure `decide(state, now)`
  returns the new state to persist or `null` to stay silent.
- **Single-flight.** `maybeAlertMissingPermission` does a read→decide→dispatch→persist across a suspend
  (`loadConfigFlow().first()` … `updateConfig`), so two near-simultaneous `Recording` emissions (a host
  reconnect storm) could both read the old count and double-fire. A `Mutex` serialises the whole body, so
  the second emission reads the first's written state and `decide` throttles it.

## 3. The import sweep

`HistoryImporter.import(onlyNew): Flow<ImportProgress>` is the sweep, run on `Dispatchers.IO` by the
process-scoped `HistoryImportRunner` (so it outlives the settings Activity — switching screens never
cancels it). It scans `/sdcard/FitFiles` (`.fit`) and `/sdcard/KGhost` (`.fit`/`.gpx`); with
`onlyNew = true` it keeps only files `lastModified > lastScanEpoch`. Progress is emitted as
`SCANNING → PARSING → DONE` with running `imported · duplicates(skipped) · not-valid(failed)` counts.

The **invariant** `imported + skippedDuplicates + failed == total` holds for every outcome, where
`total = work.size` is the ledger-filtered list (§4). Each work item contributes exactly one unit: a
decode/decimate result is either `Failed` (null decode, `<2` decimated points, or a thrown exception) or
a track that lands in a chunk and becomes `imported | skipped` on flush.

## 4. The four performance layers (the ~1200-ride story)

A cold "import all" over a big library used to take several minutes; a re-run took nearly as long. Four
composable changes fix that, safest first.

### 4a. Single-pass FIT decode
`FitDecoder.decode` previously streamed each file **twice** — `checkFileIntegrity` then `decode.read`.
The integrity pass is dropped: a corrupt/truncated file still yields `null` because `decode.read` throws
and the existing `runCatching { … }.getOrElse { null }` catches it. ~halves per-file IO.

### 4b. In-memory bulk sink — removing the O(n²) index rewrite
The old path called `TrackStore.addAll(chunk)` once per `FLUSH_EVERY` (25) chunk; each call **re-read and
re-wrote the entire growing `index.json` + `sourcekeys.json`** → ~48 whole-file rewrites over 1200 files,
a quadratic tax that dominated at scale. `TrackStore.openBulkSink()` returns a `BulkSink` that holds the
bookkeeping **in memory** for the whole import:

- Seeds the dedup key set (`known`) from disk once, and triggers the one-time legacy `.pathcells`
  migration at open (via `readPathCellSnapshot()`, for its side-effect only).
- `addAll(chunk)` writes each new `<id>.json` (`fsync = false`) and folds the track into an
  **additions-only** in-memory `SpatialIndex` + `known`. It does **not** touch `index.json`/`sourcekeys.json`.
- `commit()` (once, at end of import, and in a `finally` on cancel) unions **only this import's
  additions** onto the **current** raw on-disk snapshot under `indexLock`, then writes both files once.

Merging additions onto *current* disk (not overwriting from the seed) is what makes it correct against a
**concurrent writer** — a ride finishing mid-import calls `store.add()`/`tidyGroup`→`archive()`:

- a concurrent **add** is on disk at commit → preserved (we never overwrite it);
- a concurrent **archive** removed an id from disk → honored (that id is not in our additions, so the
  union never resurrects it — the earlier whole-seed union *did* resurrect it, leaving a dangling index
  entry).

A hard process **kill** mid-import (no `finally`) leaves a stale index, which `prewarmAndReconcile()` at
extension startup rebuilds from the surviving `<id>.json` files — so the `<id>.json` writes are the
durable work and the aggregate bookkeeping is always recoverable.

### 4c. Bounded parallel decode
Decode+decimate (the dominant cost) runs on `N = min(3, cores − 1)` workers: a producer feeds a
RENDEZVOUS `items` channel; workers `decodeOne` into a `decoded` channel (capacity `N·2`); a **single**
collector coroutine keeps all the chunk/flush/progress/`lastScan` bookkeeping (so no counter needs a
lock). A dedicated joiner closes `decoded` **exactly once** after all workers `join()` (no
send-after-close, no hang; `N ≥ 1` even on a single core). Order-independence is fine: dedup handles it
and `lastScan` advances by the max mtime among *flushed* files.

**Cancel-safety** is preserved end to end: `<id>.json` writes happen per chunk, so a mid-run Cancel keeps
already-flushed rides; `sink.commit()` + `ledger.save()` are blocking calls in the `finally`, so they run
even as the cancelled scope unwinds. A worker's `CancellationException` is re-raised via `scope.cancel(e)`
so it propagates out of the flow (a CE raised inside a `launch` child would otherwise only cancel that
child); `decodeOne` catches `CancellationException` **before** the generic `Exception → Failed` branch, so
a cancel is never miscounted as a failure.

### 4d. Processed-file ledger — skip decode on re-runs
`sourceKey` dedup only avoids re-*storing*, **after** the expensive decode. `ProcessedLedger`
(`processed.json`, keyed by absolute path → `(size, lastModified)`) lets a re-run skip the **decode**
entirely for unchanged files: `import()` `partition`s the work list by `isProcessed` in **one** pass
(one `stat` per file, no TOCTOU skew between a count and a filter), and ledger-skipped files are excluded
from `total` (so the invariant is unaffected). "Import all" over an already-processed library then
finishes in seconds.

Files are **marked at flush time** — inside `flushChunk()`, *after* `sink.addAll` succeeds and *before*
the `lastScan` suspend — never at buffer time. So a cancelled import can never mark a file "processed"
whose track wasn't actually stored (which would orphan the ride by skipping it forever), and the sink and
the ledger reflect the same flushed prefix. Failed files are never marked (they must keep retrying); a
missing/corrupt ledger loads empty (tolerant decode) and is rebuilt as files are re-marked.

### 4e. Rebuild history — deliberately resetting the ledger

Imports now carry **altitude** (`FitDecoder`/`GpxParser` capture each point's elevation, needed by the
gradient pace tier — `engine/GradePace.kt`), so a track imported before that landed has no altitude and
can't feed that tier. The **Rebuild history** button (`RaceScreen`, `HistoryImportRunner.rebuildAll`) is
a rider-triggered, deliberate reset that upgrades an existing library: `prepareRebuild()` archives every
file-sourced track (never a live-`RECORDED` one — those have no source file to re-import) and, via
`resetImportDedup()`, resets BOTH import dedup gates so the follow-on "import all" re-decodes and re-
stores every one of them with altitude:

- `processed.json` (the §4d ledger) is **deleted outright** — the whole point is to force every source
  file to re-decode, not skip it as unchanged.
- `sourcekeys.json` is **rewritten**, not deleted: only the keys of the tracks just archived are dropped
  from it (`TrackStore.dropSourceKeys`), so a live-recorded ride's `sourceKey` collapse (the mechanism
  that stops a ride from being stored twice, once live and once re-imported) keeps working for every
  track NOT part of the rebuild.

Both resets are guarded — see `prepareRebuild`'s doc comment for the "fewer source files than tracks
about to be archived → refuse" and "dedup reset didn't take (an IO failure — the only refusal left) →
refuse" safety checks — so a rebuild can't strand the library in `archive/` with nothing able to
re-import it. It takes as long as a first
import (every file is fully re-decoded), which the button's UI hint says up front.

## 5. Auto-clean (library tidy)

`autoTidy` keeps the library bounded by archiving near-duplicate rides of a route — keeping the **fastest
and two most recent** per route — into `tracks/archive/` (restorable). `archive()` rewrites `index.json`
with the ids removed *before* moving the files (index-first is crash-safe: a crash leaves an un-indexed
live file, re-archived next tidy, not a permanent stale pointer), and deliberately leaves `sourcekeys.json`
untouched so an archived ride's key stays known (a re-scan won't re-ingest it). Tidy shares the per-dir
`indexLock` with the import sink, which is why §4b's commit must honor a concurrent archive.

## Key symbols

| Symbol | Meaning |
|---|---|
| `TrackStorage.tracksDir` | library root (external if all-files access, else internal fallback) |
| `TrackStore.BulkSink` | in-memory import bookkeeping; `commit()` = additions-only union under `indexLock` |
| `ProcessedLedger` | `processed.json`; skip decode of unchanged files, marked at flush time |
| `HistoryImporter.FLUSH_EVERY` (25) | chunk size for Cancel-safe per-chunk `<id>.json` flush |
| `PermissionAlertSchedule` | decaying in-ride reminder (`INITIAL_BURST` 3, 72 h → 10 day), wall-clock, backward-clock-guarded |
| `KGhostConfig.lastScanEpoch` | `onlyNew` cutoff; advanced per flush by max mtime |
| `KGhostConfig.permAlert*` | persisted reminder state (schema v8) |
| `prewarmAndReconcile()` | startup rebuild of `index.json` from `<id>.json` files (self-heals a killed import) |

Diagnostics: the import emits `ImportProgress` (SCANNING/PARSING/DONE); grep logs for
`import: ledger skipped`, `import dropped`, and `import failed for`.
