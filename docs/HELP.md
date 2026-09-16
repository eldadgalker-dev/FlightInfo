# FlightInfo — Full help

**English** · [עברית](HELP.he.md)

Generated automatically from the app's help screens (version 5.3-beta34).

## What FlightInfo does

Shows your aircraft on an offline world map during a flight, with distance flown and remaining, speed, altitude, time to go and arrival time. It needs no network, no account and no server. Position comes from the phone's GPS when it can see satellites; otherwise the aircraft is moved along the planned route by time and speed.

## Entering a flight

Choose origin and destination by IATA code (TLV, LHR) or by city / airport name in English. Fastest: tap "Scan boarding pass" and point the camera at the barcode, or pick a screenshot of the pass; origin, destination and flight number are read from it. The scheduled departure time is optional and is used only when no GPS fix is available. Tap "Start flight". Return here any time with the aircraft button to correct the plan or end the flight.

## Map controls

Right column (map view):  
+ / −  zoom (also pinch or double-tap)  
arrows-out  show the whole route  
crosshair  centre on the aircraft  
compass / arrow  north-up or track-up  
  
Left column (functions):  
pencil  flight setup  
table  all values  
gear  settings  
question mark  this help  
satellite / clock  live sensors or time-based estimate  
eye  visual fix: pick a city you see out of the window, the side and a rough distance (about 15 km accuracy, useful only without GPS)  
  
Dragging the map pauses following for 20 seconds. The bottom panel expands on tap.

## Aerial imagery

In Settings you can download once (on Wi-Fi, about 60–80 MB) the NASA Blue Marble satellite mosaic (public domain) and show it under the lines and labels. Resolution is about 1 km per pixel, matching what is visible from cruise altitude: mountains, coasts, deserts, lakes. This is the only download the app ever makes; in flight everything is local.

## Lines on the map

Blue dashed line: remaining planned route.  
Orange solid line: the part already flown.  
Faint dotted line: the original plan, shown only after a re-plan.  
Turquoise line: the measured track (solid); dashed turquoise: the estimated continuation while no fix was available, so the track has no holes and the aircraft keeps moving.  
Shaded band: along-route uncertainty of the position.

## Tracking modes

Tracking: a GPS fix arrived within the last 10 seconds. Route estimate: GPS lost; the aircraft moves along the route at the last known speed, blending toward a typical cruise speed, and the uncertainty band grows about 3 % of the distance per second without a fix. Predicted only: no fix ever received; position follows a standard climb / cruise / descent profile from the takeoff time. Estimate only: you switched sensors off deliberately.

## Confidence markers

Aircraft ring colour = how much the position rests on:  
green  good GPS fix now  
orange  weak GPS fix or network (ADS-B) fix  
red  no fix: estimated (inertial sensors and route) or time only  
Silhouette: solid when the position is measured, hollow when propagated.  
  
Value markers:  
●  measured now  
◐  fused: measured recently and propagated  
○  predicted from route and time  
◌  stale: last measurement is old

## Where the aircraft is drawn

With a GPS fix, even a weak one, the aircraft is drawn where it was measured and the turquoise line records the real track at 0.5 km resolution, including turns around the airports.  
The governing route (blue dashed) is always the direct line from the latest measured position to the destination; it re-anchors as you move, so the aircraft sits on it and the remaining distance is measured along it. The original plan stays as a faint dotted line.  
Without a fix the aircraft moves along the governing route from the last measured position: speed from the last fix blending toward a typical cruise speed, heading from the gyroscope for 90 s, and progress scaled by the gyroscope-measured deviation for 5 minutes (a holding circle nets zero).  
Without any fix at all: time-based profile along the plan.

## Estimate-only mode

Turn on "Estimate only" in the setup screen, or tap the GPS button on the map. The phone's sensors are ignored and the aircraft is placed where a flight with that departure time should be (departure + 15 minutes taxi, then a standard profile). Use it to follow a flight you are not on. A scheduled time later than now is assumed to be yesterday's departure.

## Getting a GPS fix on board

Hold the phone against the window or on the tray table next to it. The first fix after takeoff can take 30–60 seconds. Aisle and middle seats often get no fix; the app then says "Predicted only" and works from time. Fewer than 4 satellites give no position; the status shows the count so you know the receiver is alive. Airplane mode does not affect GPS.  
  
If the phone has internet (gate, onboard Wi-Fi, or when following a flight from home) and the option is on in Settings, the real position of the flight is fetched from community ADS-B data (adsb.lol) using the flight number, and shown as source "ADS-B".

## The values

"I am on the ground now" (panel, before takeoff): resets the phase, calibrates GPS altitude against the field elevation and fixes the reference pressure for cabin altitude.  
Without GPS: the gyroscope scales along-route progress during turns and holds ("manoeuvring"), and the accelerometer detects the takeoff roll and the landing deceleration.  
Distance remaining and flown are measured along the route.  
Time to destination: airborne = remaining distance at cruise speed + 20 min descent; on the ground = wait until the (expected) takeoff + the full flight profile.  
Arrival is shown in the destination's local time; the panel also shows local time at origin, UTC and destination.  
Altitude comes from GPS only; the barometer measures cabin pressure and only detects climb and descent.  
Heading after GPS loss follows the gyroscope for 90 seconds, then the route course.

## Terms in the metrics screen

Along-track uncertainty: 1σ of the position along the direction of flight; it equals the GPS accuracy while there is a fix and grows about 3 % of the distance per second without one, faster while manoeuvring.  
Route re-anchors: how many times the governing route was re-drawn from a new measured position to the destination.  
Visual fixes: manual landmark fixes you applied.  
Manoeuvring: the gyroscope shows the aircraft heading more than 60 degrees away from the route for 45 s (hold, vectoring).  
Cabin altitude: pressure altitude of the cabin from the barometer, relative to the on-ground calibration; typically 1,800–2,400 m in cruise. It is not the aircraft altitude.  
Ground calibration: difference between GPS altitude and the field elevation, taken when you pressed “I am on the ground now”, and subtracted from displayed altitudes.

## Flight logs

Recording starts only when you press the REC button on the map (pulsing red while recording, red dot when idle) and stops when you press it again, 10 minutes after landing, or after 3 hours on the ground. While recording, the flight writes a CSV file on this phone (Android/data/com.galker.flightinfo/files/logs) with the estimate and the raw GPS, barometer and gyroscope readings, once per second. Nothing is uploaded. "Share latest log" sends the file through any app you choose; a real flight log is the best way to calibrate the estimator.  
  
Replaying a flight:  
1. In the app: Settings > Flight logs > tap a log > Replay. The map plays it back at 30/120/600x through the current estimator.  
2. On any computer, online: open https://eldadgalker-dev.github.io/FlightInfo/replay/ and drop the CSV (share it from the app by e-mail or Drive first).  
3. On a computer offline: download docs/replay/index.html once from the project page ("Desktop replay" > save the file) and open it by double-click; drop the CSV. Same screen as the phone; imagery needs a network, everything else works offline.

## Limitations

Without GPS the aircraft follows the direct route; real routing may differ by hundreds of kilometres for weather or airspace, and the app cannot know until GPS returns. No flight-number lookup: there is no free offline schedule database, hence the boarding-pass scan. Local time at the current position is shown as UTC. The Earth is modelled as a sphere (0.3 % distance error).

