# FlightInfo

Offline in-flight position tracker for Android. Shows your aircraft on a map using GNSS when the phone can see satellites and a route-constrained estimate when it cannot. No network, no accounts, no API keys, no servers. Free software under the BSD-3-Clause licence.

## What it does

- Aircraft marker on an offline world map (Natural Earth, bundled: 1:50m land, lakes, borders and country fills in seven tones; 1:10m populated places, ~6,800 cities), rotated to the current track. Country and city names in Hebrew when the phone is in Hebrew, English otherwise
- Planned route (great circle) and flown segment
- Uncertainty band that grows while GNSS is lost
- Values for **origin / now / destination**: distance flown and remaining, ground speed, altitude, track, vertical rate, elapsed time, time to go, ETA, local time at origin, destination and UTC, takeoff time
- Every value carries a confidence marker: measured / fused / predicted / stale
- Zoom in/out, fit route, recenter, north-up / track-up, day/night themes
- Runs in a foreground service so the estimate keeps updating with the screen off
- **Visual fix**: pick a city you see out of the window (side + rough distance) to move the estimate along the route when there is no GPS (about 15 km accuracy)
- **Aerial imagery** (optional): NASA Blue Marble mosaic, public domain, downloaded once (~60-80 MB) from the project's GitHub Release and shown under the vector layers
- **Country below** the aircraft, from bundled polygons (point-in-polygon)
- **Boarding-pass scan**: camera or screenshot; reads origin, destination and flight number from the IATA BCBP barcode (PDF417 / Aztec / QR), fully offline
- English and Hebrew (full RTL)

## How position is estimated

**The governing route is the strongest hypothesis; the aircraft is always drawn on it.** Measurements move the aircraft along the route and accumulate evidence; only a *proven* deviation changes the route.

| Mode | When | Behaviour |
|------|------|-----------|
| Tracking | GNSS fix within the last 10 s | Along-track Kalman update; lateral offset is measured, shown as a number, never applied to the drawn position |
| Route estimate | GNSS lost > 10 s | Moves along the governing route at the last measured speed blending toward a phase-typical speed; heading follows the gyro for 90 s, then the route course |
| Predicted only | No fix ever received, or estimate-only mode | Time-since-takeoff profile (climb / cruise / descent) along the route |

**Proven deviation and re-planning.** A deviation is accepted when at least 6 GOOD fixes (3 if the gyro recorded a sustained turn) spanning at least 180 s all show a lateral offset of at least 15 km on the same side, a GNSS track at least 8 degrees off the route course, and along-track progress consistent with the measured speed. Then the governing route becomes the great circle from the proven position to the destination, the original plan is drawn faintly, and the measured track is drawn in a third colour. Within 60 km of the destination every GOOD fix re-anchors, since approaches never follow the great circle. On the ground the aircraft sits at the origin; a fix far from the origin produces a "check the flight plan" warning instead of moving it.

**Estimate-only mode** (switch in Setup, or the GPS toggle on the map) ignores the phone's sensors entirely and shows where a flight *should* be given its scheduled departure time (+15 min taxi). Use it to follow a flight you are not on. A scheduled time later than now is taken as yesterday's departure.

Flight phase (ground / climb / cruise / descent / landed) is detected from the cabin-pressure rate and GNSS vertical rate. Takeoff time is captured automatically on the ground-to-airborne transition.

All tunables are in `app/src/main/java/org/skytrack/Parameters.kt`.

## Building

Requirements: JDK 17, Android SDK (API 34). Android Studio Koala or newer opens the project directly.

```
./gradlew test             # JVM unit tests (geodesy, route, estimator, BCBP)
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

`.github/workflows/build.yml` runs the tests on every push and publishes the APK (debug-signed, installable) as a workflow artifact and, on every push to `main`, as a GitHub Release tagged `v<version>` with two assets: `FlightInfo-<version>.apk` and a fixed-name `FlightInfo.apk`. No unsigned APK is produced.

### Updating the phone directly from GitHub

- **Fixed link** (open on the phone, then install): `https://github.com/<owner>/FlightInfo/releases/latest/download/FlightInfo.apk`
- **Obtainium** (free, open source, no account): install Obtainium, tap **+**, paste `https://github.com/<owner>/FlightInfo`. It watches the Releases page and offers each new version as an in-place update. Play Protect will still warn once per install for a sideloaded app.

### Publishing a new version from Windows (no Git knowledge needed)

`tools/update_github.bat` takes a project zip, mirrors it into a local clone (including `.github` and deleted files), commits and pushes; GitHub Actions then builds the APK. Requirements: Git for Windows (the tool opens the download page if it is missing). Usage: drag the zip onto `update_github.bat`, or double-click it and pick the zip. Append `tag` (`update_github.bat x.zip tag`) to also create a `v<version>` tag, which produces a GitHub Release with the APK attached. Repository URL and author are Parameters at the top of `update_github.ps1`.

### Aerial imagery pack

`.github/workflows/bluemarble.yml` (run manually from the Actions tab) downloads a NASA Blue Marble Next Generation image, reprojects it with `tools/build_bluemarble.py` into `bluemarble_z0-6.mbtiles` (Web Mercator, zoom 0-6, ~60-80 MB estimated) and attaches it to the release `data-v1`. The app downloads that file on demand from Settings (`Parameters.AERIAL_PACK_URL`) and reads it locally through MapLibre's `mbtiles://` scheme. The default source URL is on NASA's Visible Earth image server; if NASA moves it, pass another equirectangular Blue Marble URL as the workflow input.

### Regenerating bundled data

```
pip install timezonefinder
mkdir data && cd data
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_land.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_lakes.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_boundary_lines_land.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_populated_places.geojson
curl -LO https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/airports.csv
cd ..
python3 tools/build_assets.py data app/src/main/assets
```

Glyphs (`assets/glyphs/`) are Noto Sans PBF ranges from the openmaptiles/fonts v2.0 release; only Latin, Latin Extended, Cyrillic and Hebrew ranges are bundled.

## Testing without a flight

Android Studio > Emulator > Extended controls > Location lets you play back a GPX route at speed. Enable airplane mode in the emulator to confirm the map and all values render offline. Interrupt playback to observe the switch to route-constrained mode and the growing uncertainty band; resume to observe reacquisition.

## Known limitations

- **Altitude is GNSS-only.** The barometer measures cabin pressure in a pressurised aircraft; it is used solely to detect climb and descent. Altitude shows as stale when there is no fix.
- **Heading after GNSS loss** follows the gyro for 90 s only; consumer IMUs drift too fast for longer use. After that the route course is shown.
- **Without GNSS the aircraft follows the governing great circle.** Real routing may deviate by hundreds of kilometres for weather or airspace; the deviation is proven (and the route re-planned) only after GNSS returns and ~3 minutes of consistent fixes.
- **Fewer than 4 satellites give no position at all** (Android provides no fix); such states are shown but cannot move the aircraft.
- **Current-position local time is UTC.** No timezone polygon database is bundled to keep the APK small; origin and destination times use IANA zones from the airport table.
- **Middle seats** may receive no GNSS at all. The app then runs in predicted-only mode and says so.
- **Spherical Earth** model (0.3 % error); far below the estimator's own uncertainty.
- Historical flight tracks (OpenSky) are not fetched; the planned route is always the great circle.
- **No flight-number lookup.** There is no free, offline schedule database; the boarding-pass barcode is the zero-cost substitute. Departure time is not in the barcode and stays optional/manual.

## Data licences

- Natural Earth: public domain
- OurAirports: public domain
- Noto Sans glyphs: SIL Open Font License 1.1
- MapLibre Native: BSD-2-Clause
- ZXing core: Apache-2.0
- CameraX (AndroidX): Apache-2.0
- Application code: Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/ — BSD-3-Clause (see `LICENSE.txt`)
