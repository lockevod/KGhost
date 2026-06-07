# KVPartner

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that races you against a **ghost** —
a virtual partner riding at a fixed pace, or your own past self on a route — and shows the gap
live on a data field and as a marker on the map.

Built with the official [`karoo-ext`](https://github.com/hammerheadnav/karoo-ext) SDK for Karoo 2
and Karoo 3.

## What it does

- **Virtual Partner** — pick a target pace/speed and race a constant-pace ghost. The live gap (time
  and distance, ahead/behind) is rendered in a graphical data field.
- **Race your own** — KVPartner auto-records each ride as a decimated GPS track. When you load a
  route, it finds the stretches you have ridden before and races you, segment by segment, against
  your past self — automatically, with no setup.
- **External ghosts (import)** — race against rides you did *not* record with KVPartner: it scans the
  Karoo's own `/sdcard/FitFiles/*.fit` history and imports GPX/FIT files you drop into
  `/sdcard/KVPartner/`, turning them into ghosts the route matcher can use.
- **Ghost on the map** — during an active segment race, the ghost's live (time-based) position is
  drawn as a marker on the Karoo map, sliding along the route at the ghost's pace so you can see it
  pull ahead or fall behind on the road.

## Data fields

Add these from the Karoo's data-field picker (Extensions):

| Field | Type id | What it shows |
|---|---|---|
| Gap (graphic) | `kvpartner-gap` | Graphical ahead/behind gap vs the ghost |
| Gap (numeric) | `kvpartner-gap-num` | Numeric gap (time / distance) |
| Segment | `kvpartner-segment` | Active "race your own" segment info + per-segment gap |

Missing data renders as `---`. Fields are designed for sunlight readability and respect the Karoo's
day/night theme.

## Settings

Open the KVPartner app on the Karoo:

- **Partner** tab — Virtual Partner target pace/speed.
- **Race** tab — enable "race your own", choose which past ride to race (best / last), auto-record
  toggle, **import history** (FitFiles scan + GPX/FIT from `/sdcard/KVPartner/`, needs all-files
  access), and **Show ghost on map**.

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
  virtual-partner and recorded-ghost sources, GPS-staleness logic.
- `geo/` — route polyline projection (`PolylinePath`), recorded-track store with a geohash spatial
  index, segment matcher, track decimation.
- `import/` — GPX (SAX) and FIT (Garmin SDK) decoders + the history importer (with `sourceKey` dedup
  so the same ride ingested twice collapses to one ghost).
- `map/` — pure helpers that turn the ghost's route position into a map marker.
- `extension/KVPartnerExtension.kt` — feeds the engine from `karoo-ext` streams, publishes `GapState`
  for the data fields, and emits `MapEffect` symbols for the on-map ghost.

## Third-party

This project uses the Garmin Flexible and Interoperable Data Transfer (FIT) SDK.
See [third_party/FIT-SDK-LICENSE.txt](third_party/FIT-SDK-LICENSE.txt) for the FIT SDK license.
