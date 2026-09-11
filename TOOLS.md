# FlightInfo — Tools and components in the package

Status as of version 4.5-beta8 (11 Sep 2026). "Last update" is the version of the component used by this project; "Security" and "Risk" are the project author's assessment, not the vendor's.

## Scripts and workflows shipped in this repository

| Tool | Version | Last update | Licence | Purpose | Security | Risk |
|---|---|---|---|---|---|---|
| `tools/update_github.bat` + `update_github.ps1` | 1.2 | 2026-09-09 | BSD-3 (this project) | Publish a project zip to the GitHub repo from Windows (clone, mirror, commit, push) | Uses Git Credential Manager; no secrets stored by the script | Medium: `robocopy /MIR` overwrites the repo with the zip content; edits made on GitHub are lost |
| `tools/build_assets.py` | 2.0 | 2026-09-09 | BSD-3 | Regenerates the bundled map/airport data from Natural Earth and OurAirports | Reads public data only | Low |
| `tools/build_bluemarble_gibs.py` | 1.0 | 2026-09-09 | BSD-3 | Fetches Blue Marble tiles from NASA GIBS into MBTiles (runs in Actions) | Public data, no credentials | Low |
| `tools/build_bluemarble.py` | 1.0 | 2026-09-09 | BSD-3 | Alternative: reprojects an equirectangular image to MBTiles | Public data | Low |
| `.github/workflows/build.yml` | 2.1 | 2026-09-09 | BSD-3 | Tests, builds and publishes the APK to GitHub Releases on every push | Signs with the committed key unless secrets are present | Medium: committed signing key (see `keystore/README.md`) |
| `.github/workflows/bluemarble.yml` | 2.0 | 2026-09-09 | BSD-3 | Manual: builds and publishes the aerial imagery pack | Public data | Low |
| `keystore/flightinfo.jks` | — | 2026-09-09 | — | Stable APK signing key so updates install in place | **Public** private key | Medium: anyone can sign an APK Android accepts as an update; it still needs the user to install it |

## Third-party libraries compiled into the APK

| Component | Version | Licence | Purpose | Security | Risk |
|---|---|---|---|---|---|
| Kotlin / Coroutines | 2.0.20 / 1.8.1 | Apache-2.0 | Language, concurrency | Mainstream | Low |
| Jetpack Compose (BOM 2024.09.02), Material 3, AppCompat 1.7.0, Core 1.13.1, Lifecycle 2.8.5 | see BOM | Apache-2.0 | UI, per-app language | Mainstream, Google-maintained | Low |
| MapLibre Native Android | 11.5.0 | BSD-2 | Offline vector/raster map rendering | Open source, widely used | Low |
| ZXing core | 3.5.3 | Apache-2.0 | Boarding-pass barcode decoding, on device | Pure Java, no network | Low |
| CameraX | 1.3.4 | Apache-2.0 | Camera preview for barcode scan | Google-maintained | Low |
| org.json (tests only) | 20240303 | Public domain (JSON licence) | Unit tests | — | Low |

## Bundled data

| Data | Version / date | Licence | Size | Risk |
|---|---|---|---|---|
| Natural Earth land, lakes, borders, countries (1:50m), populated places (1:10m) | master, fetched 2026-09-09 | Public domain | ~5 MB | Low; label positions may be imperfect |
| OurAirports airports.csv (filtered: scheduled service, IATA code) | mirror, fetched 2026-09-08 | Public domain | 0.3 MB | Low; airports open/close occasionally |
| Timezones per airport (timezonefinder at build time) | tzdata via Python | MIT / public domain | in airports.csv | Low |
| Noto Sans glyph ranges (Latin, Latin Ext, Cyrillic, Hebrew) | openmaptiles/fonts v2.0 | SIL OFL 1.1 | 0.6 MB | Low |
| Airline IATA->ICAO table (110 carriers) | hand-compiled 2026-09-10 | BSD-3 | in code | Medium: an unknown prefix means no ADS-B match; report missing carriers |

## External services (optional, only when a network exists)

| Service | Used for | Cost | Terms | Security | Risk |
|---|---|---|---|---|---|
| GitHub Releases / API | APK distribution, in-app update check, aerial pack download | Free (public repo) | GitHub ToS | HTTPS; APK signature checked before install | Low |
| adsb.lol | Live ADS-B position by callsign | Free, no key | Community service; availability not guaranteed | HTTPS; only the flight number is sent | Medium: may disappear or rate-limit; app degrades gracefully |
| NASA GIBS | Source of the aerial imagery pack (build time only) | Free | Public domain imagery | HTTPS | Low |
| Obtainium (user's choice) | Automatic update tracking from GitHub | Free, open source (GPL-3) | — | Installs APKs the user approves | Low |

## What the app never does
No accounts, no analytics, no crash reporting, no background network. The only network traffic is: update check / APK download (user-initiated or once per launch when allowed), aerial pack download (user-initiated), ADS-B queries with the flight number (when allowed and a network exists). Flight logs stay on the phone until the user shares them.
