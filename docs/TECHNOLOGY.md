# FlightInfo — The technology: how it works and why

**English** · [עברית](TECHNOLOGY.he.md)

## The problem
A passenger wants to know where the aircraft is. There is no network, the seat screen (if any) does not always show a map, and a phone gets GPS only near a window and only part of the time. Existing solutions need internet (Flightradar24) or pre-downloaded maps with an account and servers.

## The principle: measurement when available, route when not
1. **With a GPS fix** (even a weak one, up to 2 km accuracy) the aircraft is drawn where it was measured and the real track is recorded and drawn (turquoise line).
2. **The governing route** is the direct line (great circle) from the last measured position to the destination. It re-anchors as the aircraft moves, so "distance remaining" is measured along it. The original plan stays faint for reference.
3. **When GPS disappears** the aircraft continues along the governing route from the last point at the last measured speed (blending toward a typical cruise speed), with heading from the gyroscope for 90 s and progress scaled by the heading deviation the gyroscope measures (a holding circle = zero progress). Uncertainty grows about 6 % of the distance and is drawn as a band. The track continues dashed; when GPS returns, the estimated stretch becomes the direct line between the two fixes — the past is known, only the future is estimated.
4. **Never any GPS** (middle seat): position from time since takeoff and a standard profile, with an escalating warning asking to hold the phone to the window.

## What each sensor contributes
| Sensor | Contributes | Cannot contribute |
|---|---|---|
| GPS/GNSS | position, speed, track, altitude | nothing without line of sight (fewer than 4 satellites = no position) |
| Barometer | **cabin pressure** → climb/descent detection, cabin altitude | aircraft altitude (the cabin is pressurised) |
| Gyroscope | heading changes, turns and holds | absolute heading; drifts after minutes |
| Accelerometer | takeoff roll (takeoff time to the second), landing braking, gravity vector | speed or distance (a 0.02 m/s² bias = 70 m/s error after an hour) |
| Magnetometer | — | unusable inside the fuselage |
| Network (when present) | live ADS-B position of the flight, updates | never required |

## Why an embedded vector map
The whole map (Natural Earth, public domain, ~5 MB) is inside the app and rendered on the device with MapLibre. No tile server, no API key, no dependency on anyone's availability. Satellite imagery (NASA Blue Marble, public domain) is an optional one-time download from GitHub Releases — zero cost.

## Why open source and zero budget are requirements
BSD 3-Clause lets anyone build, test, change and distribute. Zero budget means the product keeps working even if the author stops maintaining it: no server to switch off, no subscription to expire.

## Accuracy measured on a real flight (Munich–Tel Aviv, 10 Sep 2026)
- With GPS: median 1 m from the next fix.
- After 24 minutes without GPS: 34 km drift (≈10 % of the distance flown without a fix).
- ETA in cruise: −3 to −6 minutes; the descent model was corrected after the flight.
- GPS was available 94 % of the time near a window, median accuracy 57 m.

## Advantages
- Works anywhere in the world without preparation or an account.
- Every value states what it rests on (measured / fused / predicted) — uncertainty is never hidden.
- Full CSV log per flight → the estimator improves from real data; desktop and phone replay.

## Fundamental limits
- Without GPS there is no kilometre-level accuracy — physics, not a bug.
- No free schedule database → boarding-pass scan instead of flight-number lookup.
- Imagery resolution is limited to public-domain sources (~1 km/pixel).
