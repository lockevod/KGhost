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

`routeDist` is on the Karoo's `routeDistance` scale; the segments and ghost curve are on the decoded
polyline (`path.totalM`) scale. These are the **same geometry** and differ only marginally — RouteGraph
mixes them identically (it feeds `routeDistance − remaining` straight into `TurfMeasurement.along(decodedPolyline, …)`)
and trusts the equivalence. The `route mode ON` log prints `karooLen`/`polyLen`/`delta` so the real
difference is visible; if it ever proved large, `routeDist` would need rescaling, but in practice it's
metres.

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
  `NavigatingRoute` → KGhost re-matches recorded tracks → new ghost, and `D0`/anchor/firstMove **reset**
  with the odometer baseline re-captured, so `D0` is correct on the new route. The race restarts on the
  new route from where you are. If the new route no longer overlaps your history → VP-pace fallback.
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
