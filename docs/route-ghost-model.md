# Current racing model

This describes the implemented path-following model. Historical implementation plans under
`docs/superpowers/` explain earlier designs; they are not the current runtime contract.

## 1. The race number and the map marker

- **Ghost Pace, without a navigated race:** compare the ride odometer against the configured
  constant target pace.
- **Race your own, with a navigated route:** `GhostIntegrator` accrues historical time over the
  metres the rider actually covers. The time lead does not depend on projecting the rider onto
  the loaded route. Reroutes carry the existing lead.
- **Map marker:** use the loaded route's `RouteGhost` curve to place a chase ghost relative to the
  rider's route position and time lead. This is a separate calculation from accruing the lead.

The extension's `KGhostExtension.startTick` orchestrates these calculations at approximately 1 Hz.
The race clock starts with movement and excludes stops. Scalar checkpoints preserve the lead for a
compatible resume; `GhostCheckpoint` and the tick validate the checkpoint before restoring it.
All in-process intervals use `SystemClock.elapsedRealtime`; persisted ride/checkpoint timestamps
use wall-clock time where required.

## 2. Historical pace lookup

The tick computes one pace value and reuses it for accrual and the field's SEG/GP tag:

1. `PacePatch`: pace on this road, selected by location cell and heading.
2. `GradePace`: historical pace at the current gradient, when this road has no usable match.
3. Neutral fill: the current metres contribute zero to the time lead when neither model answers.

Both historical tiers require a fresh trusted GPS fix and `CoastQuality.LIVE`. The gradient tier
also requires a recent `ELEVATION_GRADE` sample. Dead-reckoned metres do not acquire a historical
verdict. The constant Ghost Pace target does not supply the normal neutral fill.

The visible field tag **SEG** means either historical tier supplied a verdict; **GP** means neutral
fill in route mode. Diagnostic logs distinguish `seg=SEG`, `seg=GRADE`, and `seg=GP`.

`PacePatch` stores AVERAGE/LAST/BEST reducers together. AVERAGE uses its recency-weighted mean,
falling back to the last sample when there is only one contributing ride. The rider's exact cell
has priority over neighbours; a more popular neighbouring road must not displace it.

## 3. GradePace

`GradePace.Builder` reads tracks one at a time and computes gradient over a trailing distance
window (`GRADE_WINDOW_M`, currently 100 m). Distance dropouts and implausible speed spikes restart
that window; dwell steps and invalid/out-of-range historical gradients are excluded.

Unlike the local road model, GradePace's AVERAGE is an **all-time metre-weighted mean**. LAST is the
most recent contributing ride, and BEST is the minimum pace with the existing plausibility clamp.
Bins require `GRADE_MIN_BIN_M` (400 m) before answering. Missing altitude, insufficient coverage,
non-finite gradient or an unavailable model falls through to neutral fill.

`GradePaceStore` persists the global model as `gradepace.json`, with an explicit schema version.
The model is loaded at route load and rebuilt from the live library after a productive import.
See [import-pipeline.md](import-pipeline.md) for FIT elevation, deduplication and rebuild behavior.

## 4. Route grid and history loading

`TrackStore` ranks candidates by spatial overlap, capped by `CorridorSeeder.MAX_CANDIDATES` (250).
The route loader captures one ranked ID set and loads those IDs, tolerating missing/unparseable
files. Index and track reads run on `Dispatchers.IO`; model computation runs on `Dispatchers.Default`.
The loaded tracks feed both the in-memory `PacePatch` and any required corridor-grid rebuild.

`CorridorSeeder` matches history samples to each 25 m route node by location and direction,
choosing a sample per track before reducing oldest-first. The resulting `PerRouteAggregate`
contains all three reducers. All picks race nodes with `count >= 1`; AVERAGE falls back to LAST
below `AGG_MIN_LAPS`. Runs shorter than `AGG_MIN_SEG_M` are omitted from the marker's raceable
segments. `RouteGhost` bridges remaining marker-curve gaps with the configured Ghost Pace.

`shouldReseed` compares candidate **ID sets**, including removals, against `seededTrackIds`.
An absent aggregate reseeds; a warmed aggregate tolerates small changes until the symmetric
set difference reaches its threshold. This bounds rebuild work while allowing auto-tidy churn.
Schema mismatches also discard cached aggregates.

There is **no ride-end `updateAggregate` fold** in the extension. A finished ride is saved to the
track library and may enter the next candidate-set-triggered reseed. The old
`SegmentMatcher.match`/forward-lap seeding path is not used by the current route loader.

## 5. Marker position, reroutes and GPS loss

The marker's rider position is a GPS projection onto the route, constrained by the last reliable
position, odometer movement, distance from the line and heading. Recovery after a shortcut accepts
an unambiguous route position. Ambiguous recovery retains the last reliable marker position;
it does not change the integrated time lead. No position is drawn before a reliable marker exists.

The chase marker uses `curve.timeAt(riderRoutePosition) - leadSeconds`. An ahead rider sees the ghost
trailing; a behind rider sees it further along the route. The displayed distance behind also uses
this route frame; the time lead remains path-integrated. The distance ahead uses the integrator's
breadcrumb history.

On route replacement, the marker loads a new route model while the integrator carries the lead.
A completed first lap followed by a return to the first half latches the marker hidden for later
laps; the race number continues. GPS-loss estimation is handled by `CoastingEstimator`, with
historical pace lookup disabled for estimated metres. Its bounded loss handling and live sensor
freshness still require on-device validation.

## 6. Verification and diagnostics

JVM tests cover the integrator, coasting logic, corridor reducers, interpolation, persistence and
replay cases. They do not prove Karoo stream timing, map IPC or page lifecycle on hardware.

Useful logs: `KVP tick route(B2)`, `KVP route load: gradePace=`, `KVP grid: corridor-seeded`,
`route mode ON`, and the GPS-loss episode diagnostics. When comparing old measurements or plans,
check their commit and model first: the earlier D0/forward-lap race is no longer the time-gap model.
