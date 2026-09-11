# Development Prompt v2 — FlightInfo (code name SkyTrack), Zero-Budget Offline In-Flight Tracker (Android)

## 0. Role and Objective

You are a senior Android engineer. Build **FlightInfo** (Kotlin package `org.skytrack`, applicationId `com.galker.flightinfo`), a complete, working, free (BSD-3-Clause) Android app that shows the user's aircraft on an offline map during a commercial flight. Position comes from GNSS when available and from a route-constrained estimator otherwise. The app must function with **no network at all**, and the project must incur **no recurring cost of any kind**: no tile servers, no cloud storage, no API keys, no paid services. Distribution is via GitHub Releases (built by GitHub Actions, free for public repositories).

Deliver full source, build configuration, tests, CI workflow and README. No stubs, no TODOs.

Build order: (1) skeleton compiles; (2) offline map; (3) GNSS + display; (4) route model; (5) estimator; (6) UI; (7) tests + CI. Verify `./gradlew assembleDebug` after each stage.

---

## 1. Hard Constraints

1. **Kotlin only.** English identifiers, comments and logs; no non-ASCII in source. User-facing text in `strings.xml` (default English, `values-iw` Hebrew, `supportsRtl=true`).
2. **Min SDK 26, compile/target 34.** Jetpack Compose, MVVM-lite (StateFlow), Coroutines. **No Hilt, no Room, no DataStore** — manual DI, SharedPreferences + org.json. Fewer dependencies = fewer build failures on a machine you cannot see.
3. **Offline-first, zero infrastructure.** Every byte of map data is inside the APK. No optional downloads that depend on someone paying for hosting.
4. **Map engine: MapLibre Native Android** (`org.maplibre.gl:android-sdk`). No Google Play Services dependency for the map. Location via `android.location.LocationManager` (GNSS status needed).
5. **Code structure**: every file starts with a header (purpose, version). All tunables in one `Parameters.kt`. No magic numbers in logic.
6. **Honesty in UI**: every displayed value carries a confidence marker (MEASURED / FUSED / PREDICTED / STALE). Never show a prediction as a measurement.
7. **Licensing**: all bundled data must be public domain or permissively licensed and compatible with a BSD app. Document every source in README.

---

## 2. Zero-Budget Map Data Strategy

The only map data is **Natural Earth** (public domain), bundled as GeoJSON and rendered by MapLibre as `GeoJsonSource` layers. No tiles, no tile server, no PMTiles hosting.

- `ne_50m_land`, `ne_50m_lakes`, `ne_50m_admin_0_boundary_lines_land`, `ne_50m_admin_0_countries` (MAPCOLOR7 fill index, LABEL_X/Y label points, NAME + NAME_HE), `ne_10m_populated_places` (SCALERANK <= 8, NAME + NAME_HE + MIN_ZOOM), coordinates rounded to 2-3 decimals. Country fills in 7 palette tones; country and city labels localised (Hebrew when the device locale is Hebrew) with density increasing by zoom.
- Target total < 6 MB. Adequate for zoom 1.5–10 at cruise altitude; city labels appear progressively by zoom.
- Fetch upstream from the `nvkelso/natural-earth-vector` GitHub repository (raw GeoJSON), not from naturalearthdata.com (CDN reliability).
- **Glyphs**: MapLibre needs PBF glyph ranges for text. Bundle Noto Sans Regular ranges 0–255, 256–511, 1024–1279 (Hebrew), 1280–1535 and Bold 0–255, 256–511 from the `openmaptiles/fonts` v2.0 GitHub release (OFL licence). Serve via `asset://glyphs/{fontstack}/{range}.pbf`.
- **Airports**: OurAirports `airports.csv` from the `davidmegginson/ourairports-data` GitHub mirror (public domain), filtered to large/medium airports with scheduled service and an IATA code, with an IANA timezone column computed at build time by `timezonefinder`. ~3,200 rows, ~280 KB, parsed into memory at startup.
- Provide `tools/build_assets.py` that regenerates all of the above from the raw downloads.

Explicitly **do not** implement: tile downloads, historical route downloads (OpenSky), timezone polygon database, online map providers of any kind.

---

## 3. Project Layout

Single Gradle module `app` with packages:

```
org.skytrack
  Parameters.kt          all tunables
  SkyTrackApp.kt         Application; manual DI; MapLibre init; auto-resume previous flight
  MainActivity.kt        Compose host; state-based navigation; permissions
  data/                  AirportRepository (CSV), Stores (plan / last estimate / ground fix / settings)
  route/                 Geodesy (spherical), Route (sampled great circle, project(), pointAt(), bearingAt())
  sensors/               GnssSource, BaroSource, GyroSource, FlightPhaseDetector
  fusion/                Estimator (4 modes), Metrics (all displayed values)
  service/               FlightEngine (1 Hz tick, StateFlow<FlightMetrics>), TrackingService (foreground)
  map/                   MapStyle (palette, layers, aircraft bitmap), MapController (sources, camera, animation)
  ui/                    MapScreen, SetupScreen, MetricsScreen, SettingsScreen, Theme, Format
```

`route/` and `fusion/` must be pure Kotlin (no Android imports) so they compile and test on the JVM alone.

---

## 4. Sensors

4.1 **GNSS**: `LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000 ms)` on a HandlerThread; `GnssStatus.Callback` for satellites used/visible. Classify each fix: GOOD (hAcc <= 30 m and sats >= 5), DEGRADED (hAcc <= 200 m), NONE. Ignore bearing below 20 m/s.

4.2 **Barometer**: low-pass cabin pressure, 60 s rate window, output hPa and hPa/min. **Never convert to altitude** — this is cabin pressure. Document it in code and UI.

4.3 **Gyro**: yaw rate about the gravity axis (accelerometer-derived), so the phone may lie flat in any orientation. Magnetometer is not used (fuselage distortion) — document why.

4.4 **Flight phase**: GROUND → TAKEOFF → CLIMB → CRUISE → DESCENT → LANDED from baro rate and GNSS speed / vertical rate. Capture takeoff time on the GROUND→TAKEOFF edge. Persist it in the flight plan.

---

## 5. Estimator (measured-first, v4)

Decision history: v2 anchored the aircraft to the planned route and required "proven deviation" before moving it; a real flight showed weak-but-valid fixes 48 km off the plan never qualifying, so the aircraft was drawn where it was not. v4 inverts the rule: **measurement wins whenever it exists; the route is the fallback.**

- Any fix with hAcc <= 2 km is used. Position = measured; between fixes short dead reckoning with speed and track. The measured track is stored at 500 m spacing (bounded to 20,000 points) and always drawn.
- Governing route = Route(latest measured position, destination), re-anchored when the aircraft is > 1 km laterally from it (at most every 20 s). Along-track `s` and remaining distance refer to it. The planned route is kept for the PREDICTED_ONLY profile and drawn faintly.
- No fix > 10 s: propagate along the governing route from the last measured position (speed blend, gyro heading 90 s, gyro-projected progress 5 min, manoeuvring flag, 3 %/s uncertainty growth).
- No fix ever / estimate-only: time profile on the planned route.
- `sensorLevel` 0..3 (time only / inertial / weak or network fix / good fix) drives the aircraft ring colour red / orange / yellow / green.
- On the ground: position still measured; a fix > 50 km from the origin only raises a plan-mismatch warning.
- GNSS warning escalation at 30 s / 180 s / 600 s without a fix, relief notice for 20 s after re-acquisition.
- Sensor-measured takeoff time replaces manual/scheduled values (`takeoffMeasured`).

## 6. Displayed Values (three columns: Origin / Now / Destination)

- Origin: local time (+UTC offset), takeoff time, distance flown, route length
- Now: ground speed, altitude, track, vertical rate, UTC, elapsed, total flight time, progress %
- Destination: local time (+UTC offset), ETA, remaining distance, time to go
- Estimator panel: mode, phase, satellites used/visible, last fix age, 2σ along-track uncertainty, measured lateral offset, deviation evidence n/N, re-plan count, lat/lon

ETE = (remaining − descent distance) / cruise speed + 20 min descent allowance; in DESCENT phase use remaining / measured speed. Times via `java.time` with the airport IANA zone. Current-position time is shown as UTC and labelled as such.

---

## 7. Map

- Style JSON holds only background + glyph URL; all sources/layers are added in code from the bundled GeoJSON strings (read once on a background thread).
- Layers, bottom to top: land, lakes, borders, uncertainty fill, route planned (dashed), route flown (solid), places dot + label (filtered by scalerank per zoom), airport dot + IATA label, aircraft symbol (`icon-rotate` = track, `icon-rotation-alignment: map`).
- Aircraft icon drawn in code (Canvas path, nose up), two variants: solid (MEASURED/FUSED) and outlined (PREDICTED/STALE).
- Layers: original route (faint, after a re-plan), governing route (dashed remaining, solid flown), measured track (third colour, after a re-plan), along-route uncertainty band ±2σ_s with fixed 6 km half-width.
- Controls: zoom in / out buttons, pinch and double-tap, fit-route, recenter, north-up/track-up toggle, live / estimate-only toggle, optional two-finger rotate. Zoom range 1.5–10. Follow mode releases on gesture and resumes after 20 s.
- Night palette default; day palette; AUTO by destination local hour (07–19 = day).

---

## 8. UI Screens

1. **Setup**: origin (pre-filled from nearest airport to the last ground GNSS fix within 15 km), destination search (code / city / name), optional flight number, optional scheduled departure HH:MM in origin local time, Start / Update / End flight.
2. **Map**: full-screen map, status strip (GNSS sats, mode, fix age), left column (metrics / settings / setup), right column (zoom+, zoom−, fit, recenter, orientation), collapsible bottom panel with the three headline values (remaining, time to go, ETA) and a second/third row of detail.
3. **Metrics**: full page, cards for Now / Origin / Destination / Estimator, legend.
4. **Settings**: distance km/nm/mi, altitude m/ft, speed km/h/kt/mph, 12/24 h, theme, auto-follow, track-up default, rotate gesture.

Behaviour: first position on screen < 1 s after Start (predicted or restored). Foreground service of type `location` keeps sensors alive with the screen off; notification shows remaining distance and time to go, refreshed every 30 s. Estimate persisted every 10 s and restored on relaunch if < 12 h old. Permission denial → app runs in PREDICTED_ONLY and says so.

---

## 8a. Boarding-Pass Scan (zero-cost substitute for flight-number lookup)

No free offline schedule database exists, so flight-number lookup is out of scope. Instead, read the IATA BCBP barcode on the boarding pass (PDF417 on paper, Aztec/QR on mobile) with **ZXing core** (Apache-2.0, pure Java) and **CameraX**. Parse the 60-character mandatory block: from (30-33), to (33-36), carrier (36-39), flight number (39-44), day-of-year (44-47), seat (48-52). Resolve day-of-year to the nearest year. Provide both live camera scan and "from image/screenshot". Fill origin, destination and flight number in Setup; departure time is not in the barcode. `scan/Bcbp.kt` must be pure Kotlin with JVM tests (IATA reference payload, rejection of non-BCBP text, New-Year day-of-year resolution).

## 8b. Manual visual fix

Eye button on the map opens a dialog listing bundled populated places within 200 km of the current estimate (nearest / most important first), with side (left window / straight below / right window) and distance (close ~20 km, mid ~55 km, horizon ~100 km). The implied aircraft position is the landmark displaced perpendicular to the route course toward the aircraft; only the along-track component is applied as a Kalman update with sigma 15 km (8 km for "below"). A visual fix leaves PREDICTED_ONLY (aged fix -> ROUTE_CONSTRAINED) and seeds the speed with the phase-typical value if none was measured. Count exposed in the estimator panel.

## 8c. Optional aerial imagery (zero cost)

NASA Blue Marble Next Generation (public domain) reprojected to Web Mercator MBTiles, zoom 0-6, by `tools/build_bluemarble.py` running in a manual GitHub Actions workflow, published to GitHub Release `data-v1`. App: Settings section with one-time download (progress, resume-free, atomic rename), show/hide toggle, delete; MapLibre `RasterSource` via `mbtiles://<path>`; with imagery the land and country fills are omitted, everything else stays. INTERNET permission is used for this download only.

## 8d. Country below, barometer presence, actual takeoff time

Country polygons parsed once from the bundled GeoJSON; point-in-polygon every 5 s; shown as "Over: <country>" in the status strip and metrics. Barometer presence reported by the service and shown in metrics with a note when absent. Setup accepts an actual takeoff time (HH:MM, origin zone) that overrides scheduled + taxi for the predicted profile.

## 8e. Online enrichment (opportunistic, never required)

Setting `useNetwork` (default on). When `ConnectivityManager` reports a validated internet connection: (1) poll a community ADS-B aggregator (adsb.lol, `/v2/callsign/{CS}`) every 30 s for the flight's position, deriving the callsign from the flight number via a bundled IATA->ICAO airline table (`LY315` -> `ELY315`, fallback to the raw flight number); convert to a GnssSample (GOOD, hAcc 150 m) and feed the estimator; in estimate-only mode always, in live mode only when the phone's GNSS has been silent > 60 s; show "Position: ADS-B" as the source. (2) Silent update check once per launch; result shown as a tappable line in Setup and Settings. Labels on the map are English only. Time to destination on the ground = wait until expected takeoff + full profile; ETA follows. Ground speed below 1.5 m/s is shown as 0.

## 8g. Full use of inertial sensors without GNSS

Gyro: keep a dead-reckoned heading `psiDR` (re-anchored on every usable GNSS track). In ROUTE_CONSTRAINED, for GYRO_PROGRESS_WINDOW_S (300 s) after the loss, `s += v_eff * dt * cos(psiDR - routeCourse)` (signed); |deviation| > 60 deg for > 45 s sets `maneuvering` (shown in the status strip) and multiplies uncertainty growth by 3. Accelerometer: `AccelSource` emits the gravity-removed horizontal acceleration averaged over 12 s with a direction-stability flag; the phase detector turns GROUND into TAKEOFF after >= 15 s of stable >= 1.6 m/s^2 (takeoff time = start of the roll) and DESCENT into LANDED after >= 8 s of stable deceleration. State explicitly that IMU integration cannot yield ground speed or distance.

## 8h. Ground confirmation

Button "I am on the ground now" (panel while GROUND, live mode): phase reset to GROUND, captured takeoff cleared, GNSS altitude vs field elevation stored as an altitude bias applied to displayed altitude, current cabin pressure stored as reference; cabin altitude = elev + 44330 * (1 - (p/p0)^0.190263) shown in metrics. Reference expires after 18 h.

## 8f. Beta release

versionName `3.0-beta1`; `BETA.md` with tester instructions; about block marks the beta; updater reads `/releases` (not `/releases/latest`) so pre-release tags are offered; version comparison treats `x.y-betaN` below `x.y`.

## 9. Tests and CI

- JVM tests (JUnit 4): great-circle distances vs reference (TLV–JFK, TLV–LHR), route projection round-trip, lateral offset recovery, antimeridian route continuity, 40-minute GNSS gap (no jump > 2 km/s, drawn on route, drift < 40 km, reacquisition error < 3 km), proven deviation re-plans while unproven offsets stay on route, alternating-sign noise never re-plans, ground anchoring with origin-mismatch flag, estimate-only ignores fixes, predicted-only profile reaches destination.
- GitHub Actions: setup-java 17, setup-android, `gradlew test assembleDebug`, publish the single debug-signed `FlightInfo-<version>.apk` (artifact on every push, GitHub Release on `v*` tags). Do not build or publish an unsigned release APK.

---

## 10. Deliverables

1. Buildable Gradle project (`./gradlew assembleDebug`) with wrapper.
2. `README.md`: features, estimation modes table, build steps, asset regeneration, emulator test procedure, known limitations, data licences.
3. `LICENSE.txt` (BSD-3-Clause).
4. `tools/build_assets.py` with a Parameters block and validation.
5. `.github/workflows/build.yml`.

## 11. Known Limitations to State in README and in-app

- Altitude is GNSS-only; barometer = cabin pressure.
- Heading follows gyro for 90 s only after GNSS loss.
- Route-constrained mode assumes the planned great circle; real routing may deviate by hundreds of km.
- Current-position local time is UTC (no tz polygon DB).
- Middle seats may get no GNSS; predicted-only mode is then shown prominently.
- Spherical Earth model (~0.3 % distance error).
