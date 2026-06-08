# KVPartner

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that races you against a **ghost** —
a virtual partner riding at a fixed pace, or your own past self on a route — and shows the gap
live on a data field and as a marker on the map.

Built with the official [`karoo-ext`](https://github.com/hammerheadnav/karoo-ext) SDK for Karoo 2
and Karoo 3.

## What it does

- **Virtual Partner** — pick a target pace/speed and race a constant-pace ghost. The live gap (time
  and distance, ahead/behind) is rendered in graphical and numeric data fields.
- **Race your own** — KVPartner auto-records each ride as a decimated GPS track. When you load a
  route, it builds **one continuous ghost of the whole route** — your past self on the stretches
  you've ridden before, stitched with the Virtual Partner pace everywhere else — and races you
  against it, automatically, with no setup. The two halves are not separate features: they are the
  same ghost.
- **External ghosts (import)** — race against rides you did *not* record with KVPartner: it scans the
  Karoo's own `/sdcard/FitFiles/*.fit` history and imports GPX/FIT files you drop into
  `/sdcard/KVPartner/`, turning them into ghosts the route matcher can use.
- **Ghost on the map** — during a route race, the ghost's live (time-based) position is drawn as a
  marker on the Karoo map, sliding along the route at the ghost's pace so you can see it pull ahead
  or fall behind on the road. The marker icon (ghost / cyclist / arrow / dot) and size (S/M/L) are
  selectable.
- **Resilient to GPS loss** — a dropout never blanks the gap. It keeps dead-reckoning at your last
  speed (like a GPS unit), marks the value as an estimate after ~30 s, alerts you after a minute, and
  only gives up after ~3 minutes of no signal. See [Behaviour during a GPS dropout](#behaviour-during-a-gps-dropout).

## Data fields

Add these from the Karoo's data-field picker (Extensions):

| Field | Type id | What it shows |
|---|---|---|
| Gap (graphic) | `kvpartner-gap` | Two-dot track: you vs the ghost, with the gap (time/distance) below |
| Gap (numeric) | `kvpartner-gap-num` | Numeric gap (time / distance, per your preference) |
| Segment | `kvpartner-segment` | Active "race your own" stretch: elevation profile or track + the gap |

Ahead is green, behind is red, on-pace is neutral. A value that is a dead-reckoned **estimate**
during a GPS dropout is shown in amber. `---` appears only when there is nothing to show — no target
set, not recording, or after a sustained GPS loss (see below). Fields are designed for sunlight
readability and respect the Karoo's day/night theme.

### Behaviour during a GPS dropout

The Karoo's distance/position freezes when GPS is lost. A navigator should keep estimating, not go
blank, so KVPartner dead-reckons:

| Time without GPS (while moving) | Data field | Map ghost |
|---|---|---|
| 0–30 s | gap continues, estimated at your last speed (shown normally) | visible, keeps gliding |
| 30 s – 3 min | gap continues, value in **amber** (estimate) | visible |
| > 1 min | …plus a one-shot **"GPS lost"** in-ride alert (re-armed on recovery) | visible |
| > 3 min | gives up → `---` | hidden |

When GPS returns, your position snaps back to the real fix and the gap corrects. A normal **stop**
(e.g. a red light) is *not* a GPS loss: the gap keeps showing your real position (the ghost keeps
moving, so you fall behind correctly), and the Karoo's auto-pause freezes the ride clock so nothing
drifts. The map ghost's position is purely time-based, so it is always known and stays visible
throughout a dropout — it is not hidden just because *your* position is briefly uncertain.

## Settings

Open the KVPartner app on the Karoo. Everything lives on one scrolling screen (the Virtual Partner
and "race your own" are two halves of one ghost, so they are configured together):

- **Virtual Partner** — the target pace/speed (entered as km/h or min/km). This is also the pace the
  whole-route ghost uses on stretches you have no recorded history for.
- **Race your own** — master enable, which past ride to race (**best** / **last**), **auto-record**
  toggle, and a segment-entry alert.
- **Ghost on map** — show/hide, plus the marker **icon** (ghost / cyclist / arrow / dot) and **size**
  (S / M / L).
- **Import history** — scan the Karoo's `/sdcard/FitFiles` and import GPX/FIT from `/sdcard/KVPartner/`
  (needs all-files access); "import all" or "new only".

## Install (sideload)

1. Download the latest APK from the project's Releases.
2. On your phone, long-press the APK link and share it to the **Hammerhead Companion** app, which
   installs it to the paired Karoo. (Both Karoo 2 and Karoo 3 are supported.)
3. On the Karoo, add the KVPartner data fields to a ride profile and/or open the KVPartner app to
   configure it.

To import external rides you must grant **All files access** when prompted (used to read
`/sdcard/FitFiles` and `/sdcard/KVPartner`).

## Build

Requirements: **JDK 17** (Gradle itself must run on 17), Android SDK 34.

`karoo-ext` is published on **GitHub Packages**, so you need a GitHub token with `read:packages`.
Put credentials in `local.properties` (gitignored):

```properties
gpr.user=<your-github-username>
gpr.key=<a-github-PAT-with-read:packages>
```

Then:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS; any JDK 17 works
./gradlew :app:assembleDebug         # debug APK
./gradlew :app:assembleRelease       # release APK (R8)
./gradlew :app:testDebugUnitTest     # JVM unit tests
```

Tech: Kotlin 2.0, AGP 8.5, `karoo-ext` 1.1.9, Jetpack Compose (settings UI), DataStore,
kotlinx.serialization, the Garmin FIT Java SDK (for FIT import), JUnit 4. `compileSdk`/`targetSdk`
34, `minSdk` 23 (with core-library desugaring for `java.time`).

## Architecture (brief)

A pure, JVM-tested core (no Android) does the work and the extension is plumbing:

- `engine/` — `GhostCurve` (bidirectional distance↔time interpolation), `GapCalculator` → `GapState`,
  the virtual-partner and recorded-ghost sources, `RouteGhost` (stitches the continuous whole-route
  ghost), `RouteProjectedProgress` (GPS→route distance), and `CoastingEstimator` (the GPS-loss
  dead-reckoning / estimate-quality state machine).
- `geo/` — route polyline projection (`PolylinePath`), recorded-track store with a geohash spatial
  index, segment matcher, track decimation.
- `import/` — GPX (SAX) and FIT (Garmin SDK) decoders + the history importer (with `sourceKey` dedup
  so the same ride ingested twice collapses to one ghost).
- `map/` — pure helpers that turn the ghost's route position into a map marker.
- `extension/KVPartnerExtension.kt` — feeds the engine from `karoo-ext` streams, publishes `GapState`
  for the data fields, emits `MapEffect` symbols for the on-map ghost, and dispatches the GPS-lost
  in-ride alert.

## Third-party

This project uses the Garmin Flexible and Interoperable Data Transfer (FIT) SDK.
See [third_party/FIT-SDK-LICENSE.txt](third_party/FIT-SDK-LICENSE.txt) for the FIT SDK license.
