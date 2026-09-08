# FlightInfo

Offline in-flight position tracker for Android. Shows your aircraft on a map using GNSS when the phone can see satellites and a route-constrained estimate when it cannot. No network, no accounts, no API keys, no servers. Free software under the BSD-3-Clause licence.

## What it does

- Aircraft marker on an offline world map (Natural Earth 1:50m, bundled), rotated to the current track
- Planned route (great circle) and flown segment
- Uncertainty band that grows while GNSS is lost
- Values for **origin / now / destination**: distance flown and remaining, ground speed, altitude, track, vertical rate, elapsed time, time to go, ETA, local time at origin, destination and UTC, takeoff time
- Every value carries a confidence marker: measured / fused / predicted / stale
- Zoom in/out, fit route, recenter, north-up / track-up, day/night themes
- Runs in a foreground service so the estimate keeps updating with the screen off
- **Boarding-pass scan**: camera or screenshot; reads origin, destination and flight number from the IATA BCBP barcode (PDF417 / Aztec / QR), fully offline
- English and Hebrew (full RTL)

## How position is estimated

| Mode | When | Behaviour |
|------|------|-----------|
| Tracking | GNSS fix within the last 10 s | Scalar Kalman updates on along-track and cross-track distance |
| Route estimate | GNSS lost > 10 s | Moves along the planned route at the last measured speed, blending toward a phase-typical speed; cross-track offset decays to zero; heading follows the gyro for 90 s, then the route course |
| Predicted only | No fix ever received | Time-since-takeoff profile (climb / cruise / descent) along the route |
| Off route | Two consecutive good fixes > 30 km from the route | Free dead reckoning from raw GNSS until back within the corridor for 60 s |

Flight phase (ground / climb / cruise / descent / landed) is detected from the cabin-pressure rate and GNSS vertical rate. Takeoff time is captured automatically on the ground-to-airborne transition.

All tunables are in `app/src/main/java/org/skytrack/Parameters.kt`.

## Building

Requirements: JDK 17, Android SDK (API 34). Android Studio Koala or newer opens the project directly.

```
./gradlew test             # JVM unit tests (geodesy, route, estimator, BCBP)
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

`.github/workflows/build.yml` runs the tests on every push and publishes one file, `FlightInfo-<version>.apk`, as a workflow artifact; pushing a tag `v*` attaches it to a GitHub Release. The APK is signed with the standard Android debug key, which is sufficient for direct installation and for updating an existing installation built by the same workflow. No unsigned APK is produced.

### Publishing a new version from Windows (no Git knowledge needed)

`tools/update_github.bat` takes a project zip, mirrors it into a local clone (including `.github` and deleted files), commits and pushes; GitHub Actions then builds the APK. Requirements: Git for Windows (the tool opens the download page if it is missing). Usage: drag the zip onto `update_github.bat`, or double-click it and pick the zip. Append `tag` (`update_github.bat x.zip tag`) to also create a `v<version>` tag, which produces a GitHub Release with the APK attached. Repository URL and author are Parameters at the top of `update_github.ps1`.

### Regenerating bundled data

```
pip install timezonefinder
mkdir data && cd data
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_land.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_lakes.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_boundary_lines_land.geojson
curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_populated_places_simple.geojson
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
- **Route-constrained mode assumes the planned great circle.** Real routing may deviate by hundreds of kilometres for weather or airspace. Off-route detection catches this only when GNSS returns.
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
