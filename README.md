# KGhost

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that races you against a **ghost** —
a Ghost Pace riding at a fixed pace, or your own past self on a route — and shows the gap
live on a data field and as a marker on the map.

## What it does

- **Ghost Pace** — pick a target pace/speed and race a constant-pace ghost. The live gap (time
  and distance, ahead/behind) is rendered in graphical and numeric data fields.
- **Race your own** — KGhost auto-records each ride as a GPS track. When you load a route, it builds
  **one continuous ghost of the whole route** — your past self on the stretches you've ridden before,
  stitched with the Ghost Pace pace everywhere else — and races you against it, automatically, with no
  setup. The two halves are not separate features: they are the same ghost.
- **External ghosts (import)** — race against rides you did *not* record with KGhost: it scans the
  Karoo's own `/sdcard/FitFiles/*.fit` history and imports GPX/FIT files you drop into
  `/sdcard/KGhost/`, turning them into ghosts to race.
- **Ghost on the map** — during a route race, the ghost is drawn as a marker on the Karoo map,
  sliding along the route at the ghost's pace so you can see it pull ahead or fall behind on the road.
  The marker icon (ghost / cyclist / arrow / dot) is selectable; its size is automatic (it follows the
  map zoom), and it glides smoothly rather than hopping once per second.
- **Fair start** — the race starts when **you** start riding, not when you press record. Waiting at
  the start for a GPS lock is never counted against you, whether or not you use auto-pause. The gap
  field shows `---` until you actually roll.
- **Uses the Karoo's own position on the route** — KGhost reads where you are along the loaded route
  from the Karoo itself, so it works correctly on loops and out-and-backs, and recovers cleanly if you
  go off-route and the Karoo recalculates.
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
   history). Without this you can still race the fixed-pace Ghost Pace.
4. **Set your Ghost Pace** — pick a target speed/pace in the app; this is what you race when you have
   no recorded history for a stretch.
5. **Ride.** Load a route to race your past self on it, or just start riding to race the Ghost Pace.

## Data fields

Add these from the Karoo's data-field picker (Extensions):

| Field | Type id | What it shows |
|---|---|---|
| Gap (graphic) | `kghost-gap` | Two-dot track: you vs the ghost, with the gap (time/distance) below, tagged SEG (racing your past self on a recorded stretch) or GP (fixed-pace Ghost Pace) |
| Gap (numeric) | `kghost-gap-num` | Numeric gap (time / distance, per your preference) |

Ahead is green, behind is red, on-pace is neutral. While your position is briefly uncertain (a GPS
gap) the value is shown in **amber** as an estimate. `---` appears only when there is nothing to show —
no target set, not recording, you haven't started riding yet, you're off-route, or after a sustained
GPS loss (see below). Fields are designed for sunlight readability and respect the Karoo's day/night
theme.

### Stops and GPS dropouts

A normal **stop** (a red light) is not a problem: the ghost keeps to its pace, so if you stop and it
doesn't, you fall behind — exactly like a real race. **Pausing the ride** (the pause button, or the
Karoo's auto-pause) freezes the ghost with you: the ride clock stops, so a coffee stop while paused
costs you nothing against the ghost.

A **GPS dropout** never blanks the gap straight away — KGhost keeps showing an estimate and only gives
up after a sustained loss:

| Time without a position (while moving) | Gap field | Map ghost |
|---|---|---|
| brief gap | gap continues as an estimate | keeps gliding |
| ~30 s+ | gap shown in **amber** (estimate) | visible |
| ~1 min+ | …plus a one-shot **"GPS lost"** alert (clears when the signal returns) | visible |
| ~3 min+ | gives up → `---` | hidden |

When the signal returns, your position corrects and the gap catches up. The map ghost runs on time
alone, so it stays visible and gliding throughout a dropout — it isn't hidden just because *your*
position is briefly unknown.

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
  (so a typical effort, not a one-off PR or single recent ride). It needs a couple of laps **started at
  the route start** before it kicks in; on stretches without enough laps you race your **best** there
  instead. Long stops (a café, a photo) are compressed out so one stop never slows the average, and only
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
  (needs all-files access); "import all" or "new only". Shows the recorded-track count.
- **Auto-clean library** — keeps your recorded rides tidy by archiving near-duplicate rides of a route
  (keeping the **fastest and two most recent** of each), so the ghosts stay meaningful and storage
  stays bounded. Archived rides are moved to `/sdcard/KGhost/tracks/archive` and can be restored. On by
  default.
- **Diagnostic log** — write KGhost's diagnostics to a file so a ride can be studied later without a
  computer. Off by default; leave it off for normal use.

> **How it works under the hood:** the position/ghost/alert model (route position, the "fair start"
> ghost clock, GPS-loss handling, reroutes) is documented for developers in
> [`docs/route-ghost-model.md`](docs/route-ghost-model.md). You don't need it to use the app.

## Install (sideload)

1. Download the latest APK from the project's Releases.
2. On your phone, long-press the APK link and share it to the **Hammerhead Companion** app, which
   installs it to the paired Karoo. (Both Karoo 2 and Karoo 3 are supported.)
3. On the Karoo, add the KGhost data fields to a ride profile and/or open the KGhost app to
   configure it.

To import external rides you must grant **All files access** when prompted (used to read
`/sdcard/FitFiles` and `/sdcard/KGhost`).


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
on your Karoo; KGhost does not collect or transmit any personal data.

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
