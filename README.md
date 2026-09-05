# KGhost

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that races you against a **ghost** —
a Ghost Pace riding at a fixed pace, or your own past self on a route — and shows the gap
live on a data field and as a marker on the map.

## What it does

- **Ghost Pace** — pick a target pace/speed and race a constant-pace ghost. The live gap (time
  and distance, ahead/behind) is rendered in graphical and numeric data fields.
- **Race your own** — KGhost auto-records each ride as a GPS track. With a route loaded, it compares
  your pace over the ground you actually ride against your history. On unfamiliar roads it can use
  your historical pace at the current gradient; when neither comparison is available, the time lead
  holds. A separate route curve places the ghost on the map.
- **Best, last, or average** — for the stretches you've ridden before, choose whether the ghost is your
  **fastest** lap, your **most recent**, or a smoothed **average** of your recent laps. The average is
  ready from your **first ride** on a route — KGhost seeds it from your recorded history instead of
  waiting to warm up — and a lap counts toward it even when you don't start at the route's beginning.
- **External ghosts (import)** — race against rides you did *not* record with KGhost: it scans the
  Karoo's own `/sdcard/FitFiles/*.fit` history and imports GPX/FIT files you drop into
  `/sdcard/KGhost/`, turning them into ghosts to race.
- **Ghost on the map** — during a route race, the ghost is drawn as a marker on the Karoo map so you
  can see it pull ahead (when you're behind, it runs up the road for you to chase) or trail behind you.
  If you leave the route, the marker **holds its last position** and snaps to where you rejoin rather
  than guessing; on a second lap of a loop it's simply hidden (the gap number keeps racing). The marker
  icon (ghost / cyclist / arrow / dot) is selectable; its size is automatic (it follows the map zoom),
  and it glides smoothly rather than hopping once per second.
- **Fair start** — the race starts when **you** start riding, not when you press record. Waiting at
  the start for a GPS lock is never counted against you, whether or not you use auto-pause. The gap
  field shows `---` until you actually roll.
- **Races your actual path — reroute-proof** — the gap is measured over the ground **you actually
  ride**, comparing your pace to your historical pace metre by metre. It never depends on projecting you
  onto the route, so it **can't teleport** on loops, self-crossing routes, shortcuts or reroutes. A
  reroute to a different route **carries your lead** — the race continues, it doesn't restart.
- **Judges every stretch it can** — the gap checks your history two ways: first for **this exact road**
  (if you've ridden it before), and if you haven't, for **your typical pace at the current gradient**
  elsewhere (so a climb you've never done still gets judged against how you climb). Only when neither
  applies does the gap simply **hold** rather than guess — see the SEG/GP tag below.
- **Per ride profile** — each Karoo ride profile can have its own setup: how you race (**fixed pace**
  vs **your rides**), its own Ghost Pace base (e.g. faster on the road bike, slower on the MTB), which
  past ghost to use (**best / last / average**), the map icon, and whether KGhost is on at all. So your
  road bike can race your past laps while your MTB just chases a fixed pace. A single **master switch**
  turns the whole extension off when you don't want it.
- **Self-tidying library** — when you ride the same route many times, KGhost automatically keeps your
  **fastest and two most recent** rides of that route and archives the rest, so the ghost you race
  stays meaningful and your storage doesn't pile up. Archived rides are moved (not deleted) and can be
  restored.

## Getting started

1. **Install** KGhost (see [Install](#install-sideload) below) and open the app on the Karoo once.
2. **Add a data field** — on a ride profile, add **Gap (graphic)** and/or **Gap (numeric)** from the
   Extensions section of the Karoo's data-field picker.
3. **(Optional) Bring in past rides** — in the KGhost app, grant **All files access** when asked, then
   drop GPX/FIT files into `/sdcard/KGhost/` and tap *Import* (it also scans your Karoo's own ride
   history). Without this you can still race the fixed-pace Ghost Pace. KGhost needs this access to
   load your recorded ghosts, so if it's missing the app flags it **on its main screen** and with an
   occasional **in-ride reminder** until you grant it.
4. **Set your Ghost Pace** — pick a target speed/pace for the fixed-pace mode. In a route race,
   this target fills gaps in the map curve; unknown ground holds the time lead when neither
   historical pace tier can answer.
5. **Ride.** Load a route to race your past self on it, or just start riding to race the Ghost Pace.

## Data fields

Add these from the Karoo's data-field picker (Extensions):

| Field | Type id | What it shows |
|---|---|---|
| Gap (graphic) | `kghost-gap` | Two-dot track: you vs the ghost, with the gap (time/distance) below, tagged SEG (this stretch is being judged against your history) or GP (no verdict here — nothing recorded to compare against, so the gap just holds) |
| Gap (numeric) | `kghost-gap-num` | Numeric gap (time / distance, per your preference) |
| Ghost Gap (s) | `kghost-gap-time` | Plain number: gap in seconds, positive = ahead (Karoo-native rendering) |
| Ghost Gap (m) | `kghost-gap-dist` | Plain number: gap in metres, positive = ahead (Karoo-native rendering) |

### For developers: the gap is a public stream

The two plain-number fields are also published as Karoo data streams, so **any other extension can
consume the live gap** (the same inter-extension mechanism karoo-headwind uses for weather):

```kotlin
karooSystem.streamDataFlow("TYPE_EXT::kghost::kghost-gap-time").collect { state ->
    val gapSeconds = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
    // gapSeconds > 0 → rider is ahead of the ghost; < 0 → behind
}
```

- `TYPE_EXT::kghost::kghost-gap-time` — gap in **seconds**, positive = ahead.
- `TYPE_EXT::kghost::kghost-gap-dist` — gap in **metres**, positive = ahead.
- Each DataPoint also carries an `estimated` field (`1.0` while the value is an **estimate** — a
  GPS dropout being dead-reckoned — else `0.0`): `dataPoint.values["estimated"]`.
- While there is nothing to show (not recording, no data yet, sustained GPS loss) the stream sits in
  `StreamState.Searching`. If KGhost isn't installed the stream is simply silent — no error.

Ahead is green, behind is red, on-pace is neutral. During a **GPS dropout** the value is shown in
**amber** as an estimate (dead-reckoned) rather than blanking; leaving the **route** does *not* make it
an estimate — the number races your actual path and stays solid off-route. `---` appears only when
there is nothing to show — no target set, not recording, you haven't started riding yet, or after a
sustained GPS loss (see below). Fields are designed for sunlight
readability and respect the Karoo's day/night theme.

### Stops and GPS dropouts

The race runs on **moving time**: when you **stop** (a red light, a photo, the finish line) the gap
**freezes** at its current value and resumes when you roll again — stopping never quietly eats your
lead. **Pausing the ride** (the pause button or the Karoo's auto-pause) freezes it too. So whether you
stop, park, or pause, a break costs you nothing against the ghost; the race is decided by how you ride,
not by how long you rest.

A **GPS dropout** never blanks the gap straight away — KGhost keeps showing an estimate and only gives
up after a sustained loss:

| Time without a position (while moving) | Gap field | Map ghost |
|---|---|---|
| brief gap | gap continues as an estimate | keeps gliding |
| ~30 s+ | gap shown in **amber** (estimate) | visible |
| ~1 min+ | …plus a one-shot **"GPS lost"** alert (clears when the signal returns) | visible |
| ~3 min+ | gives up → `---` | hidden |

When the signal returns, the gap catches up (the odometer dead-reckons through the gap, so the race
keeps running). The map ghost holds its position and stays visible throughout a dropout — it isn't
hidden just because *your* position is briefly unknown.

**Power off mid-ride?** KGhost checkpoints the race a few times a minute, so if the Karoo dies and the
ride resumes, the gap comes back **with the lead you'd built** rather than restarting from zero.

### Detours, reroutes, and shortcuts

The **gap number** races you over the ground you actually ride, at your historical pace — it never
projects you onto the route, so detours, shortcuts, self-crossing loops and reroutes are handled by
construction and **can never teleport the gap**:

- **A detour or a shortcut**: you keep being raced against your historical pace on the ground under
  your wheels. You can't "win" by cutting a corner (you simply cover less ground at your own pace), and
  a longer way round costs you the extra time it takes you — no special cases, no odd jumps.
- **Loops and self-crossing routes**: because the number doesn't rely on *which pass* of the road
  you're on, a self-crossing route can't confuse it. (The **map marker** does place you on the route;
  when it can't tell one pass from another it simply holds or hides — the number always carries.)
- **A reroute to a different route**: your **lead carries over** — the race continues on the new route
  from wherever you'd got to, it doesn't reset to zero.

The only thing tied to the loaded route is the **map marker** (see *Ghost on the map* above): if you
leave the route it holds its last spot and snaps to where you rejoin, and on a second lap it hides. The
number keeps racing throughout.

## Settings

Open the KGhost app on the Karoo. Settings are split across two tabs. Changes apply **immediately,
even mid-ride** — switching how you race, the ghost pick, or the active ride profile re-builds the
ghost on the spot without restarting your race (your start anchor and progress are kept).

### Ghost Pace tab

The Ghost Pace and "race your own" are two halves of one ghost, so they are configured together here.

- **How you race** — pick one: **Fixed pace** (race the constant Ghost-Pace target) or **Your rides**
  (race your recorded history; the Ghost Pace becomes the fill pace where you have none). Choosing
  *Fixed pace* greys out the *Your rides* options below, so the choice is always clear.
- **Ghost Pace** — the target pace/speed (entered as km/h or min/km). This is the pace you race in
  *Fixed pace* mode, and the fill pace the whole-route ghost uses on stretches with no recorded history
  in *Your rides* mode. This is the **global default**.
- **Per profile** — below the global pace, each Karoo ride profile you have used appears as a card. A
  profile can **follow global**, or set its **own** mode, Ghost Pace base, past-ghost pick, map icon and
  on/off — so your road bike can race your past laps while your MTB just chases a fixed pace. A profile
  shows up here after you have selected it on the Karoo at least once.
- **Race your own** — which past ride to race (**best** / **last** / **average**) and optional alerts
  when you **enter** and **leave** a stretch that has a recorded ghost (nearby stretches won't
  double-alert). **Average** races the recency-weighted mean of your recent laps of the loaded route
  (so a typical effort, not a one-off PR or single recent ride). It needs at least **2 laps** covering
  a stretch before it kicks in — whether or not you started from the beginning of the route. On
  stretches without enough history you race the **Ghost Pace** fill instead. Long stops (a café, a photo) are compressed out so one stop never slows the average, and only
  the **first lap of each ride** counts toward it. The average is kept per route (under
  `/sdcard/KGhost/aggregates` when all-files access is granted) and survives the library auto-clean
  below.
- **Ghost on map** — show/hide and the marker **icon** (ghost / cyclist / arrow / dot). The icon size
  is automatic (it scales with the map zoom), so there is no size setting.

(Recording and importing your ride history live in the **Settings** tab below.)

### Settings tab

Device-level switches and recorded-track-library management (not per profile):

- **KGhost enabled** — the master on/off. When off, KGhost does nothing on any profile (no ghost, no
  recording, no alerts).
- **Record rides** — auto-record each ride as a GPS track for future ghost comparison.
- **Import history** — scan the Karoo's `/sdcard/FitFiles` and import GPX/FIT from `/sdcard/KGhost/`
  (needs all-files access); "import all" or "new only". Shows the recorded-track count and, after a
  scan, a summary: *imported · duplicates · not valid*. **Not valid** is not an error — it just counts
  files that can't be used as a ghost because they lack the GPS position **and** per-point timestamps a
  race needs (most often route exports from Strava/Komoot, which carry no time), plus empty/corrupt or
  too-short files. The scan keeps running if you switch screens. Re-running *import all* is **fast** —
  rides already in your library are skipped without being re-read, so a large library only pays the
  full import cost once. The recorded-track count also **refreshes when you return to the app** (for
  example after granting access following a reinstall), so it reflects the rides already on the device
  without a re-scan.
- **Rebuild history** — re-reads every ride file from scratch (tap twice to confirm — it archives your
  current library first, then re-imports it). Use it if your library predates the gradient-pace tier
  above, so older rides pick up the elevation data that tier needs. It **takes as long as a first
  import**.
- **Auto-clean library** — keeps your recorded rides tidy by archiving near-duplicate rides of a route
  (keeping the **fastest and two most recent** of each), so the ghosts stay meaningful and storage
  stays bounded. Archived rides are moved to `/sdcard/KGhost/tracks/archive` and can be restored. On by
  default.
- **Diagnostic log** — write KGhost's diagnostics to a file so a ride can be studied later without a
  computer. Each ride gets its own file (`kghost-YYYY-MM-dd-HHmmss.log`) and the oldest are purged
  automatically once there are more than six. Off by default; leave it off for normal use (logging
  costs a little battery and performance). **When it is on, the log is also sent to the developer**
  (periodically and at ride end) to help diagnose problems — but **GPS coordinates are stripped before
  sending, so no location data leaves your Karoo**, and the upload is tagged only with a random
  **Anon tag** (no personal or device identifier), shown in this screen so you can quote it in a bug
  report. Turn the diagnostic log off and nothing is ever sent.

> **How it works under the hood:** two developer docs, neither needed to use the app —
> [`docs/route-ghost-model.md`](docs/route-ghost-model.md) covers the position/ghost/alert model (route
> position, the "fair start" ghost clock, GPS-loss handling, reroutes), and
> [`docs/import-pipeline.md`](docs/import-pipeline.md) covers the recorded-ghost library (import,
> storage, the all-files-access reminder, and the large-library import performance).

## Install (sideload)

1. Download the latest APK from the project's Releases.
2. On your phone, long-press the APK link and share it to the **Hammerhead Companion** app, which
   installs it to the paired Karoo. (Both Karoo 2 and Karoo 3 are supported.)
3. On the Karoo, add the KGhost data fields to a ride profile and/or open the KGhost app to
   configure it.

To import external rides you must grant **All files access** when prompted (used to read
`/sdcard/FitFiles` and `/sdcard/KGhost`). Without it KGhost can't load recorded ghosts and runs as a
fixed-pace virtual partner only — so it surfaces the missing access on its main screen and with an
occasional in-ride reminder until you grant it.


## Third-party

This project uses the Garmin Flexible and Interoperable Data Transfer (FIT) SDK.
See [third_party/FIT-SDK-LICENSE.txt](third_party/FIT-SDK-LICENSE.txt) for the FIT SDK license.

## Disclaimer

KGhost is a **training aid** that shows an estimated gap to a virtual or recorded ghost. It is **not**
a precision instrument: the gap is derived from GPS position, estimation during signal loss, and your
recorded tracks, so it can be inaccurate — especially during GPS dropouts, off-route deviations, or on
stretches with little recorded history.

**KGhost is provided "as is", without warranty of any kind, express or implied.** The developer
(EnderThor) accepts no responsibility or liability for any harm, injury, loss, or damage arising from
the use or inability to use this application. All configuration and recorded tracks are stored locally
on your Karoo. KGhost transmits no personal data in normal use; the **only** thing ever sent off the
device is the optional **diagnostic log** (off by default) — and only with GPS coordinates removed and
tagged by a random Anon tag, sent to the developer purely to fix problems. Disable the diagnostic log
and nothing is transmitted at all.

> [!WARNING]
> Do not let chasing the ghost distract you from traffic, road conditions, or your own limits. Keep
> your attention on the road, not on the data field — your safety always comes first.

## Credits

- Developed by EnderThor.
- Built on the Karoo Extensions Framework ([`karoo-ext`](https://github.com/hammerheadnav/karoo-ext))
  by Hammerhead.
- Uses the Garmin FIT SDK to read recorded `.fit` rides (see [Third-party](#third-party)).
- Thanks to Hammerhead for the Karoo device and extensions API, and to the Karoo community
  (the [awesome-karoo](https://github.com/timklge/awesome-karoo) list and contributors) for ideas
  and inspiration.

## Support

If KGhost is useful to you, you can support its development:

<a href="https://www.buymeacoffee.com/enderthor" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>
