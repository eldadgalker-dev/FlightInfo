# FlightInfo

> **Status: 4.3-beta6 — testers only.** See [BETA.md](BETA.md) for what to test and how to report. Not yet validated on a real flight.

**Install (Android):** https://github.com/eldadgalker-dev/FlightInfo/releases/latest/download/FlightInfo.apk — or scan the QR on the [installation page](https://eldadgalker-dev.github.io/FlightInfo/) ([INSTALL.md](INSTALL.md)).

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
- **Online enrichment (optional, never required)**: when the phone has internet, the real position of the flight is fetched from community ADS-B data (adsb.lol) by flight number — in estimate-only mode always, in live mode when the phone's GNSS is silent; plus a silent update check
- **Boarding-pass scan**: camera or screenshot; reads origin, destination and flight number from the IATA BCBP barcode (PDF417 / Aztec / QR), fully offline
- English and Hebrew (full RTL)

## How position is estimated

**Measured first.** With a usable GPS fix (any accuracy up to 2 km) the aircraft is drawn where it was measured and the turquoise track records the real path at 0.5 km resolution, including manoeuvres around the airports. The governing route (blue dashed) is the great circle from the latest measured position to the destination and re-anchors as the aircraft moves; the remaining distance is measured along it, and the original plan stays as a faint dotted line.

| Mode | When | Behaviour |
|------|------|-----------|
| GNSS | fix within the last 10 s | position = measurement, short dead reckoning between fixes; ring colour green (good fix) or yellow (weak / network fix) |
| Route estimate | fix lost > 10 s | propagate along the governing route from the last measured position: last speed blending toward a phase-typical speed, gyro heading for 90 s, progress scaled by the gyro-measured deviation for 5 min (a holding circle nets zero; "manoeuvring" flag); ring orange |
| Predicted only | no fix ever, or estimate-only mode | time-since-takeoff profile along the planned route; ring red |

Escalating "no GPS" banner (30 s / 3 min / 10 min) tells the passenger to hold the phone to the window; a green notice says when it can be put down. Flight phase comes from cabin-pressure rate, GNSS vertical rate, the accelerometer (takeoff roll, landing deceleration) and route context (descent near the destination). A takeoff measured by the sensors replaces any manual or scheduled value. "I am on the ground now" calibrates altitude and cabin pressure. Estimate-only mode and the optional ADS-B network source are described in the Help.

All tunables are in `app/src/main/java/org/skytrack/Parameters.kt`.

### Validation against a real flight (MUC-TLV, 10 Sep 2026, 2 h 49 min of log)

Replaying the flight log through the v4 estimator: position error against the next fix while tracking median 1 m, p90 4 m; after the longest GNSS outage (24 min) the propagated position was 34 km off at re-acquisition (v3 route anchoring: 63 km). Fixes were available 94 % of the time, median 8 satellites used, median horizontal accuracy 57 m (almost never "GOOD" by the old 30 m rule, which is why v3 failed). ETA error in cruise -3 to -6 min, in descent up to -13 min before the 4.1 descent model. Cabin pressure stepped 799 -> 754 -> 776 hPa during cruise (cabin altitude ~2,000-2,400 m), which produced false CLIMB/DESCENT phases under the barometer-first logic; 4.1 makes GNSS the primary phase source whenever it is fresh.

## Building

Requirements: JDK 17, Android SDK (API 34). Android Studio Koala or newer opens the project directly.

```
./gradlew test             # JVM unit tests (geodesy, route, estimator, BCBP)
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

`.github/workflows/build.yml` runs the tests on every push and publishes the APK (debug-signed, installable) as a workflow artifact and, on every push to `main`, as a GitHub Release tagged `v<version>` with two assets: `FlightInfo-<version>.apk` and a fixed-name `FlightInfo.apk`. No unsigned APK is produced.

### Signing

All builds are signed with the committed key `keystore/flightinfo.jks` (see `keystore/README.md`), so every APK from the workflow updates the previous one in place. GitHub-hosted runners would otherwise create a new throw-away debug key per run and Android would refuse each update ("App not installed"). To use a private key instead, add the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; the workflow prefers them automatically.

### Installation page (GitHub Pages)

`docs/index.html` is a self-contained RTL landing page with the QR code (`docs/qr-install.png`, `.svg`), step-by-step installation, and links to Releases, README, source, Issues and licence. Enable it once: repository **Settings > Pages > Build and deployment > Source: Deploy from a branch > Branch: main, folder: /docs > Save**. The page is then served at `https://<owner>.github.io/FlightInfo/`. Regenerate the QR with `python -c "import qrcode; qrcode.make('<url>').save('docs/qr-install.png')"` if the repository moves.

### Updating the phone directly from GitHub

- **In-app**: Settings > App update > *Check for update* queries the GitHub Releases API (`Parameters.UPDATE_REPO_OWNER/NAME`), compares the tag with the installed version and, on request, downloads `FlightInfo.apk` and opens the system installer. Manual only; nothing runs in the background. Requires the release created by the build workflow.

- **Fixed link** (open on the phone, then install): `https://github.com/<owner>/FlightInfo/releases/latest/download/FlightInfo.apk`
- **Obtainium** (free, open source, no account): install Obtainium, tap **+**, paste `https://github.com/<owner>/FlightInfo`. It watches the Releases page and offers each new version as an in-place update. Play Protect will still warn once per install for a sideloaded app.

### Publishing a new version from Windows (no Git knowledge needed)

`tools/update_github.bat` takes a project zip, mirrors it into a local clone (including `.github` and deleted files), commits and pushes; GitHub Actions then builds the APK. Requirements: Git for Windows (the tool opens the download page if it is missing). Usage: drag the zip onto `update_github.bat`, or double-click it and pick the zip. Append `tag` (`update_github.bat x.zip tag`) to also create a `v<version>` tag, which produces a GitHub Release with the APK attached. Repository URL and author are Parameters at the top of `update_github.ps1`.

### Aerial imagery pack

`.github/workflows/bluemarble.yml` (run manually from the Actions tab) builds `bluemarble_z0-6.mbtiles` and attaches it to the release `data-v1`. Default mode fetches ready-made Web Mercator tiles from NASA GIBS (`tools/build_bluemarble_gibs.py`, layer `BlueMarble_ShadedRelief_Bathymetry`); the alternative mode reprojects an equirectangular image with `tools/build_bluemarble.py`. NASA's `eoimages` image server refuses connections from GitHub-hosted runners, hence the GIBS default. The app downloads that file on demand from Settings (`Parameters.AERIAL_PACK_URL`) and reads it locally through MapLibre's `mbtiles://` scheme. The default source URL is on NASA's Visible Earth image server; if NASA moves it, pass another equirectangular Blue Marble URL as the workflow input.

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
- Historical flight tracks are not fetched; the planned route is always the great circle. Live ADS-B (adsb.lol) is used opportunistically when a network exists; its availability and terms are those of a community service.
- **No flight-number lookup.** There is no free, offline schedule database; the boarding-pass barcode is the zero-cost substitute. Departure time is not in the barcode and stays optional/manual.

## Data licences

- Natural Earth: public domain
- OurAirports: public domain
- Noto Sans glyphs: SIL Open Font License 1.1
- MapLibre Native: BSD-2-Clause
- ZXing core: Apache-2.0
- CameraX (AndroidX): Apache-2.0
- Application code: Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/ — BSD-3-Clause (see `LICENSE.txt`)
