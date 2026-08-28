# Route racing model — position, ghost clock, alerts

How KGhost decides **where the rider is on a route**, **where the ghost is**, and **when to alert** —
and why it behaves the way it does in the awkward cases (no GPS yet, GPS locks late, loops, reroutes,
abutting segments). All of this lives in `KGhostExtension` ②-route-mode branch of the ~1 Hz tick.

There are two modes:

- **① Ghost Pace (VP)** — no navigated route: race a constant-pace ghost off the whole-ride DISTANCE
  odometer. Always available; the fallback.
- **② Route mode** — a route is being navigated and `raceEnabled`: race the **whole-route ghost**
  (your recorded stretches stitched with VP-pace fills) using the rider's position *on the route*.

This doc is about ② route mode.

## 1. Route position comes from the Karoo, not from us

We do **not** project the GPS coordinate onto the route ourselves any more. The Karoo already
map-matches the rider to the navigated route and exposes it:

```
routeDist (distance along route) = routeDistance − DISTANCE_TO_DESTINATION
```

- `routeDistance` (total route length) comes from the `NavigatingRoute` nav event → stored on
  `RouteMode.routeDistanceM`.
- `DISTANCE_TO_DESTINATION` and the `ON_ROUTE` flag come from `streamDataFlow(DISTANCE_TO_DESTINATION)`
  → `lastDistToDestM` / `lastOnRoute` (updated by `destJob`).

Why this and not our own projection:

- **Loops are unambiguous.** On a route that passes the same place twice (or starts/ends at home), a
  nearest-point projection can flip between passes; the Karoo tracks monotonic progress along the
  *navigated* route, so it knows which pass you're on.
- **A bogus pre-lock fix can't place us.** Before a true satellite lock the Karoo serves its
  cached/default position (e.g. a city 20+ km away). When there's no real on-route position the Karoo
  reports `ON_ROUTE = false` / no remaining, and we hold `---`. We never anchor on garbage.

Trust the position only when **on-route AND not mid-rejoin**: `haveRoutePos = lastOnRoute &&
!lastRejoinActive && remaining.isFinite()`. A non-null `rejoinPolyline`/`rejoinDistance` on the
`NavigatingRoute` event means the Karoo is guiding the rider back — its `remaining` is then
`rejoin_path_length + remaining_from_rejoin_point`, not the rider's own along-route position —
so we can't use `routeLen − remaining` directly. `lastRejoinActive` is read live from the nav stream
(updated even when the heavy route match dedups on the polyline).

However, when rejoin is active and `lastRejoinDistM` (= `state.rejoinDistance`, stored live) is
available, we can estimate the **planned rejoin point** on the original route:

```
estimatedRoutePos = routeLen − (remaining − rejoinDist)
```

This is used as a dynamic position estimate instead of the frozen exit-point: it tracks the real
planned re-entry and updates as the Karoo refines its rejoin calculation. The gap is still shown
marked as an estimate (`fresh = false` → orange field). We only accept the estimate when it is
≥ the last good position (the rider can't go backward on the route) and ≤ routeLen. If
`rejoinDistM` is not yet available (e.g. right as the Karoo first detects off-route), we fall
back to the frozen last-good position.

An implausible `DISTANCE_TO_DESTINATION` is rejected before D0 is latched (a wrong D0 is invariant →
wrong for the whole route). Two cases, both gated on `routeStartDistM == null` (first latch only): a
`remaining < 1 m` — "at the route end", the host's `0`/default served before the position settles (at
the genuine end D0 is already latched, so this doesn't fire there); and a `remaining > routeLen` —
which clamps the position to the start, a rejoin-relative remaining the rejoin gate missed by a tick or
a transient scale skew at the start. Either way hold `---` until a plausible value arrives.

### Scale note

`remaining` is measured against the Karoo's own `routeDistance` (`karooLenM`); the segments and the
ghost curve live on the decoded-polyline scale (`path.totalM`). These are the same geometry but can
differ by a few percent (different smoothing/snapping), which on a long route would shift segment
boundaries by real distance. The tick therefore **rescales every derived position to the polyline
scale**: `routeDist = (karooLenM − remaining) × (path.totalM / karooLenM)`, clamped into
`[0, path.totalM]`. All downstream consumers (D0, the odometric filter, the lap capture, segment
lookup, the ghost curve) are on the polyline scale, which also matches the physical odometer metres
the plausibility filter compares against. If the ratio is absurd (outside `0.5–2.0`) the Karoo length
is treated as garbage and the polyline length is used outright — otherwise a bogus `karooLen` would
pin the position at 0 and deadlock D0. The `route mode ON` log prints `karooLen`/`polyLen`/`delta`
so the real difference stays visible.

### First-fix confirmation

The odometric plausibility filter needs a trusted baseline, and D0 is latched once (invariant), but
the very first on-route fix can be a wrong-pass snap on a self-intersecting route — and the filter
cannot catch it (there is no baseline yet). So the first fix is only a **candidate**: a second,
odometrically-consistent fix (`|Δpos| ≤ odoΔ + slack`) confirms it; an inconsistent second fix
replaces the candidate and waits one more tick. Costs one tick of `---` at route acquisition; the
race waits for first movement anyway, so a stationary start loses nothing.

## 2. The ghost clock — `D0` and "race starts when you move"

The whole-route ghost (`RouteGhost`/`GhostCurve`) maps **route distance → time**. To race it we need a
clock: at ride-elapsed `t`, the ghost is at `curve.distanceAt(ghostElapsed)`. Getting `ghostElapsed`
right is the whole game. Two quantities:

### D0 — where the rider started this route

```
D0 = routeDist − (rideOdometer − rideOdometerAtRouteStart)
```

i.e. *current along-route position minus the distance ridden since this route began*. Computed **once**
on the first trustworthy on-route fix; it is invariant while on-route. It:

- **back-figures a head start ridden BLIND** before GPS locked (if the odometer counted that distance),
- **detects a deliberate mid-route start** (you begin the route at km 5 → `D0 = 5000`),
- stays correct across a **reroute** (see §4) because we subtract only distance ridden *since this
  route*, captured in `rideDistAtRouteStartM`.

### firstMove — when the race actually starts

The ghost clock is anchored to the moment the rider **first starts moving** (`speed > MIN_MOVING`),
captured as `firstMoveElapsedS` — **not** ride-elapsed 0:

```
ghostStartElapsedS = firstMoveElapsedS − curve.timeAt(D0)
ghostElapsed       = elapsedS − ghostStartElapsedS = (elapsedS − firstMoveElapsedS) + curve.timeAt(D0)
```

At the first move the ghost sits at `D0`; thereafter it advances on real elapsed time. Until the rider
moves we hold `---` ("race not started").

**Why anchor on first movement and not ride-start?** Because **auto-pause is optional**. If a rider
sits at the start line for two minutes waiting for a GPS lock with auto-pause *off*, `ELAPSED_TIME`
keeps running. Anchoring at elapsed 0 would make the ghost run ahead during that wait → a false
two-minute deficit. Anchoring at `firstMoveElapsedS` excludes the pre-race wait regardless of the
auto-pause setting.

### What each case produces (gap at GPS lock)

| Case | D0 | firstMove | Result |
|---|---|---|---|
| Stationary wait for lock, then ride | 0 | when you move | gap 0 at start — **no false deficit** |
| Rode blind WITH speed sensor (odometer counts) | 0 | ~ride start | **compensated** (ghost ran that time) |
| Rode blind, no sensor (unmeasurable) | lock position | at lock | gap 0 at lock — no penalty, no free advantage |
| Deliberate mid-route start at km 5 | 5000 | when you move | gap 0 at km 5 |

The worst case is always **gap ≈ 0** — never the old "explosion" (25 km / 1h42m) and never a free
head start.

> The old model anchored the ghost *beside the rider on the first fix* and never re-anchored. A bogus
> first fix (cached position) therefore poisoned the gap forever ("it never recalculated"). The model
> above has **no rider-position anchor that can go stale**: the ghost is a pure function of elapsed
> time and `D0`, so the gap self-corrects the instant the Karoo position becomes real.

## 3. GPS dropout (mid-ride, after the race started)

Route-mode loss is keyed off **route-position staleness**, not the whole-ride odometer: while moving
(`speed > MIN_MOVING`), if the Karoo's `remaining` stops changing the route fix is lost (a stationary
rider's unchanged `remaining` is legitimate, hence the movement gate). `lastDestChangeMs` stamps the
last real change; `routePosStaleS` feeds the same `handleGpsLoss` that fires the one-shot "GPS lost"
alert at `GPS_ALERT_S` and gives up + blanks at `GPS_GIVEUP_S`. The gap is marked an estimate
(`fresh = false`) past the coast window. A brief loss just holds the last `routeDist`; the ghost keeps
moving on elapsed, so it's a transparent estimate, never blanked for a short gap.

## 4. Reroute / going off-route

- **You deviate, same route** (the Karoo just guides you back): `ON_ROUTE` goes false → `---` while
  off route; when you rejoin → race **continues** (same polyline → `D0`/anchor preserved).
- **The Karoo recalculates a NEW route** (new polyline to destination): arrives as a new
  `NavigatingRoute` → KGhost re-matches recorded tracks → new **① map-marker** ghost, and
  `D0`/anchor/firstMove **reset** with the odometer baseline re-captured, so `D0` is correct on the new
  route. If the new route no longer overlaps your history, the map marker falls back to the VP-pace
  target — but that fallback is about the marker only. The **② gap number** is unaffected by any of
  this: it comes from `GhostIntegrator`, a route-agnostic pace integrator that rides the metres the
  rider actually covers, keyed on the rider's real position — not on `D0` or the loaded polyline — so a
  reroute **carries the accrued lead** rather than restarting it. Its per-tick pace is a three-tier
  lookup: tier 1 `PacePatch` (this exact road, ridden before), tier 2 `GradePace` (the rider's
  historical pace at the current gradient, on a road never ridden), tier 3 a neutral fill baked into
  the integrator (contributes exactly 0 to the gap). When a rerouted road has no history at either
  tier, the number simply neutral-fills — it gives no verdict rather than fabricating one from the VP
  target.
- **Micro-recalc flapping**: route matching dedups on the polyline (only re-matches when it actually
  changes) and a teardown grace debounces transient `Idle` blips.

## 5. Segment entry / exit alerts

Two recorded stretches the rider crosses produce optional in-ride alerts, each with its own toggle
(`segmentEntryAlert` / `segmentExitAlert`):

- **Entry** fires once when crossing INTO a recorded stretch (or into a different one) — **always**, so
  a far stretch is never missed.
- **Exit** fires once when leaving a stretch back to Ghost-Pace fill — but **not** on GPS-loss /
  off-route / route-cleared paths (those are "lost the race position", not a genuine ride-off-the-end;
  `publishSegment(null, …, fireExit = false)`, which also KEEPS `prevSegStartM` so a brief map-match
  drop on the SAME stretch isn't read as a re-entry).

**Abutting-stretch suppression (distance, not time).** When the rider rides off a stretch and the
**next stretch begins within `SEG_CLOSE_GAP_M` (~1 km)**, the EXIT is suppressed — the stretches are
effectively continuous, so the upcoming ENTRY speaks instead of an exit+entry double-pop. A genuine
return to long Ghost-Pace fill still fires the exit. This is distance-based (not a wall-clock throttle),
so it's pause/speed-independent and never swallows a legitimate entry to a far stretch. A direct
stretch→stretch change (no fill tick between) is an entry only.

## 6. Recording & accuracy

The ride is still recorded as a decimated GPS track (for future ghosts) from the `LOCATION` DataType.
`lastLat/lastLng` are written only for a fix with `LOC_ACCURACY ≤ GPS_GOOD_ACCURACY_M`, so a
cached/default pre-lock fix never lands in the recorded track. This is the only thing GPS accuracy
gates now — route *position* comes from the Karoo (§1), not from this stream.

## 7. The route grid — BEST / LAST / AVERAGE from one per-route aggregate

`best / last / average` is the per-route choice of WHICH past self to race (§ README). **All three race
the SAME persisted per-route grid** (`engine/RouteAggregate.kt` `PerRouteAggregate`, persisted by
`geo/AggregateStore.kt`); they differ only in which reducer they read from each node, so switching pick
needs no re-match.

**Grid of per-segment deltas (origin-invariant).** The route is sampled every `AGG_STEP_M` (25 m) into
nodes. Node `k` stores three reducers of the time to traverse the segment *into* it (node `k-1 → k`),
all updated in one fold: **`dtS`** (recency-weighted EMA → AVERAGE), **`minDtS`** (fastest → BEST),
**`lastDtS`** (most-recent good → LAST), plus `count` (laps that covered it). It stores the per-segment
**delta**, not a cumulative time from route 0, so the absolute start point cancels out: **a lap starting
anywhere contributes to the stretches it covers** (no "must start at the route origin" gate). Node 0 has
no incoming segment (`count` stays 0).

**Folding a lap (`updateAggregate`).** A lap is a `(routeDist, riderTimeS)` series (the live
`lapAggBuffer`, or a history slice). It is walked against the grid; for each consecutive covered
node-pair the single-step delta is folded into ALL THREE reducers (EMA: running mean for the first
`AGG_SEED_LAPS` laps then `AGG_ALPHA`; min: smallest seen; last: most-recent), guarded against GPS
spikes (`AGG_MAX_SPEED_MS`), long dwells (`AGG_MIN_SPEED_MS` clip) and backward time — the guards run
BEFORE the fold, so a glitch segment can never poison any reducer (it can't become the min). The first
covered node re-baselines (no full segment to fold), so a covered run can start up to one 25 m step late
— deliberate and symmetric between the live and seeded paths.

**Raceable stretches (`toLiveSegments(pick)`).** AVERAGE needs `count ≥ AGG_MIN_LAPS` (2) per node — a
single noisy GPS lap is not a smoothed mean — while BEST/LAST race at `count ≥ 1` (a single recorded
ride is exactly what they represent). A contiguous run `[firstK, lastK]` of qualifying nodes becomes one
`LiveSegment` over `[(firstK-1)·step, lastK·step]` (built from `firstK-1` so the first covered segment
is included); runs shorter than `AGG_MIN_SEG_M` (300 m) are dropped as isolated noise. The curve is the
cumulative sum of the pick's per-node delta: AVERAGE = `dtS`, LAST = `lastDtS`, BEST = `maxOf(minDtS,
dtS / BEST_MAX_SPEEDUP)` — a self-anchored plausibility clamp (BEST ≤ ~2× the average there) so a
GPS-glitch segment can't spike the curve, while a genuine strong day (≤2× the average) passes through.
`RouteGhost.build` then bridges the un-raceable stretches with the Ghost-Pace fill into one continuous
whole-route ghost. No live match, no per-pick overlay.

**Seeded from history (every pick races from ride 1).** On the first match of a route with no (valid)
grid, KGhost seeds it from the candidate tracks loaded for the match — those tracks come from the recorded
library, whose import/storage/dedup and the performance work behind it are documented in
[`import-pipeline.md`](import-pipeline.md). Seeding: `SegmentMatcher.routeLaps` (a cheap
O(n) single forward pass per track, splitting at >500 m off-route gaps) yields each track's
route-distance laps, folded oldest→newest by `seedAggregateFromLaps` — populating all three reducers —
then persisted. So every pick is raceable from the first ride; later rides load the persisted grid
instantly (no match, no re-seed) and the ride-end `update` folds the just-ridden lap. The legacy O(n²)
`SegmentMatcher.match()` is deprecated and no longer on the race path. Compute runs on
`Dispatchers.Default`; only load/save (blocking fsync IO) hop to `Dispatchers.IO`.

**Persistence & concurrency.** One JSON blob per route key (`routeKeyOf(name, decoded-polyline-length)`),
written atomically (temp + fsync + rename). `AGG_SCHEMA_VERSION` makes `load` discard an
older-layout blob (→ re-seed). The seed (match coroutine) and the ride-end EMA update (`Dispatchers.IO`)
are two writers of the same key: `AggregateStore.save` takes a process-wide per-dir write lock, the
ride-end update is an atomic `update(key){ … }` (locked load-modify-save), and the seed uses
`saveIfAbsent` so it never clobbers an aggregate a ride-end update just created. `stopTickAndJoin` joins
the seed match before the ride-end save.

## Key symbols (in `KGhostExtension`)

| Symbol | Meaning |
|---|---|
| `lastDistToDestM`, `lastOnRoute` | Karoo route progress (from `destJob`) |
| `lastDestChangeMs` | last time `remaining` actually changed → route-position staleness |
| `lastRejoinActive` | rider mid-rejoin (off-route) → position not trustworthy |
| `lastRejoinDistM` | latest rejoin-path length from `NavigatingRoute` → used to estimate rejoin point |
| `RouteMode.routeDistanceM` | total route length from `NavigatingRoute` |
| `routeStartDistM` | **D0** — route position at the start of this route |
| `rideDistAtRouteStartM` | odometer baseline when this route became active (reroute-correct D0) |
| `firstMoveElapsedS` | ride-elapsed at first movement — the ghost clock's start |
| `ghostStartElapsedS` | `firstMove − curve.timeAt(D0)` |
| `SEG_CLOSE_GAP_M` | next-stretch distance under which the exit alert is suppressed |
| `GPS_GOOD_ACCURACY_M` | recorder fix-trust threshold (recording only) |

Diagnostics: grep ride logs for `KVP tick route` (shows `routeDist`, `D0`, `remaining`, `ghostElapsed`,
`onRoute`), `waiting for first movement`, `no on-route position yet`, and `KVP segment … alert`.
