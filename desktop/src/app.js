// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo Desktop Replay - application script
// Version 2.5
// Purpose : Load a FlightInfo CSV flight log and play it back on a MapLibre
//           GL JS map inside a phone-shaped frame that mirrors the Android
//           app (same layers, colours, panel and controls). The replay shows
//           the RECORDED estimate and the recorded GNSS track; no estimator
//           runs here, so what is drawn is what the phone drew in flight.
// Data    : window.FI_DATA (embedded by tools/build_desktop.py): Natural
//           Earth GeoJSON layers, country label points, airports table.
// =============================================================
(function () {
  "use strict";

  // ---------------- Parameters ----------------
  const APP_VERSION = "2.9";
  const PAGE_URL = "https://eldadgalker-dev.github.io/FlightInfo/";
  const P = {
    SPEEDS: [30, 120, 600],           // x real time
    UI_HZ: 10,                        // panel/map refresh per second while playing
    GLYPHS: "embedded://glyphs/{fontstack}/{range}.pbf",   // bundled Noto Sans ranges, served by the protocol below
    GIBS: "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpg",
    ROUTE_SAMPLES: 200,
    TRACK_MIN_M: 100,                 // track vertex spacing; a vertex is also added at every heading change
    TRACK_TURN_DEG: 12,
    LEVEL_COLORS: ["#C62828", "#E53935", "#F57C00", "#2E7D32"],   // time only / estimated / weak fix / good fix: red -> orange -> green
  };
  const PALETTE = {
    DAY:   { bg: "#a9c4dc", land: "#e8e2d4", lake: "#a9c4dc", border: "#8a8a8a", fills: ["#f2e6c9","#e4efd2","#f5dfd4","#e2e6f4","#f0e2ea","#e6f0ea","#f4ecd8"],
             countryText: "#5a5a5a", placeText: "#222", halo: "#fff", planned: "#5a8fd0", flown: "#2f6fb0", track: "#0f8f83", original: "#a9b4c0" },
    NIGHT: { bg: "#0b1622", land: "#1f2a36", lake: "#0b1622", border: "#4a5c70", fills: ["#233241","#2a3138","#26343a","#2c2f3d","#243a3c","#2f3239","#28363f"],
             countryText: "#8fa3b8", placeText: "#e6ecf2", halo: "#0b1622", planned: "#7aa2c4", flown: "#ffb454", track: "#5ad1c6", original: "#4a5a6c" },
  };
  const UI = {
    he: { settings: "\u05d4\u05d2\u05d3\u05e8\u05d5\u05ea", back: "\u05d7\u05d6\u05e8\u05d4", help: "\u05e2\u05d6\u05e8\u05d4", dist: "\u05de\u05e8\u05d7\u05e7", alt: "\u05d2\u05d5\u05d1\u05d4", speed: "\u05de\u05d4\u05d9\u05e8\u05d5\u05ea", theme: "\u05e2\u05e8\u05db\u05ea \u05e0\u05d5\u05e9\u05d0", aerial: "\u05ea\u05e6\u05dc\u05d5\u05dd \u05d0\u05d5\u05d5\u05d9\u05e8 (NASA GIBS, \u05d3\u05d5\u05e8\u05e9 \u05e8\u05e9\u05ea)", lang: "\u05e9\u05e4\u05d4 / Language",
          openTip: "\u05e4\u05ea\u05d7 \u05d9\u05d5\u05de\u05df", loadHint: "\u05d8\u05e2\u05df \u05e7\u05d5\u05d1\u05e5 CSV \u05e9\u05dc \u05d9\u05d5\u05de\u05df \u05d8\u05d9\u05e1\u05d4 (\u05db\u05e4\u05ea\u05d5\u05e8 \u05d4\u05ea\u05d9\u05e7\u05d9\u05d9\u05d4, \u05d0\u05d5 \u05d2\u05e8\u05d5\u05e8 \u05dc\u05db\u05d0\u05df)" },
    en: { settings: "Settings", back: "Back", help: "Help", dist: "Distance", alt: "Altitude", speed: "Speed", theme: "Theme", aerial: "Aerial imagery (NASA GIBS, needs network)", lang: "Language / \u05e9\u05e4\u05d4",
          openTip: "Open log", loadHint: "Load a FlightInfo CSV log (folder button, or drop it here)" },
  };
  // The replay panel is always English (fixed-width, unambiguous), independent of the UI language.
  const PH = { GROUND: "on ground", TAKEOFF: "takeoff", CLIMB: "climb", CRUISE: "cruise", DESCENT: "descent", LANDED: "landed" };
  const T = {
    he: { title: "\u05d4\u05d9\u05dc\u05d5\u05da \u05d7\u05d5\u05d6\u05e8", play: "\u05e0\u05d2\u05df", pause: "\u05d4\u05e9\u05d4\u05d4", close: "\u05e1\u05d2\u05d5\u05e8", remaining: "\u05de\u05e8\u05d7\u05e7 \u05e0\u05d5\u05ea\u05e8", speed: "\u05de\u05d4\u05d9\u05e8\u05d5\u05ea \u05e7\u05e8\u05e7\u05e2", alt: "\u05d2\u05d5\u05d1\u05d4",
          playing: "\u05de\u05e0\u05d2\u05df", paused: "\u05de\u05d5\u05e9\u05d4\u05d4", loadHint: "\u05d8\u05e2\u05df \u05e7\u05d5\u05d1\u05e5 CSV \u05e9\u05dc \u05d9\u05d5\u05de\u05df \u05d8\u05d9\u05e1\u05d4 (\u05db\u05e4\u05ea\u05d5\u05e8 \u05d4\u05ea\u05d9\u05e7\u05d9\u05d9\u05d4 \u05de\u05e9\u05de\u05d0\u05dc, \u05d0\u05d5 \u05d2\u05e8\u05d5\u05e8 \u05dc\u05db\u05d0\u05df)",
          noGnss: "\u05d0\u05d9\u05df GPS", gnss: (n, a) => `${n} \u05dc\u05d5\u05d5\u05d9\u05d9\u05e0\u05d9\u05dd \u00b1${a} \u05de\u05f3`, fixes: "\u05ea\u05d9\u05e7\u05d5\u05e0\u05d9\u05dd", duration: "\u05de\u05e9\u05da",
          lPhase: "\u05e9\u05dc\u05d1", lGps: "GPS", lLogTime: "\u05d6\u05de\u05df \u05d1\u05d9\u05d5\u05de\u05df", of: "\u05de\u05ea\u05d5\u05da", srcFix: "\u05de\u05d9\u05e7\u05d5\u05dd GPS", srcEst: "\u05de\u05d9\u05e7\u05d5\u05dd \u05de\u05d5\u05e2\u05e8\u05da (\u05db\u05e4\u05d9 \u05e9\u05e0\u05e8\u05e9\u05dd)",
          phases: { GROUND: "\u05e2\u05dc \u05d4\u05e7\u05e8\u05e7\u05e2", TAKEOFF: "\u05d4\u05de\u05e8\u05d0\u05d4", CLIMB: "\u05e0\u05e1\u05d9\u05e7\u05d4", CRUISE: "\u05e9\u05d9\u05d5\u05d8", DESCENT: "\u05d4\u05e0\u05de\u05db\u05d4", LANDED: "\u05e0\u05d7\u05ea" },
          badLog: "\u05d4\u05e7\u05d5\u05d1\u05e5 \u05d0\u05d9\u05e0\u05d5 \u05d9\u05d5\u05de\u05df FlightInfo \u05ea\u05e7\u05d9\u05df", unknownAirports: "\u05e9\u05d3\u05d5\u05ea \u05ea\u05e2\u05d5\u05e4\u05d4 \u05dc\u05d0 \u05de\u05d5\u05db\u05e8\u05d9\u05dd \u05d1\u05d9\u05d5\u05de\u05df",
          help: [["\u05de\u05d4 \u05d6\u05d4", "\u05d4\u05e6\u05d2\u05d4 \u05e9\u05d5\u05dc\u05d7\u05e0\u05d9\u05ea \u05e9\u05dc \u05d9\u05d5\u05de\u05df \u05d8\u05d9\u05e1\u05d4 \u05e9\u05e0\u05e8\u05e9\u05dd \u05d1\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d9\u05ea FlightInfo. \u05de\u05d5\u05e6\u05d2\u05d9\u05dd \u05d4\u05de\u05d9\u05e7\u05d5\u05dd \u05e9\u05d4\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4 \u05d7\u05d9\u05e9\u05d1\u05d4 \u05d1\u05d8\u05d9\u05e1\u05d4 (\u05e1\u05de\u05dc \u05d4\u05de\u05d8\u05d5\u05e1), \u05d4\u05de\u05e1\u05dc\u05d5\u05dc \u05e9\u05e0\u05de\u05d3\u05d3 \u05d1\u05beGPS (\u05d8\u05d5\u05e8\u05e7\u05d9\u05d6), \u05d4\u05de\u05e1\u05dc\u05d5\u05dc \u05d4\u05de\u05ea\u05d5\u05db\u05e0\u05df (\u05de\u05e7\u05d5\u05d5\u05e7\u05d5) \u05d5\u05d4\u05e2\u05e8\u05db\u05d9\u05dd \u05e9\u05d4\u05d9\u05d5 \u05e2\u05dc \u05d4\u05de\u05e1\u05da."],
                 ["\u05d8\u05e2\u05d9\u05e0\u05d4", "\u05db\u05e4\u05ea\u05d5\u05e8 \u05d4\u05ea\u05d9\u05e7\u05d9\u05d9\u05d4 \u05de\u05e9\u05de\u05d0\u05dc, \u05d0\u05d5 \u05d2\u05e8\u05d9\u05e8\u05ea \u05e7\u05d5\u05d1\u05e5 CSV \u05d0\u05dc \u05d4\u05de\u05e1\u05da. \u05d4\u05e7\u05d5\u05d1\u05e5 \u05e0\u05de\u05e6\u05d0 \u05d1\u05d8\u05dc\u05e4\u05d5\u05df \u05ea\u05d7\u05ea Android/data/com.galker.flightinfo/files/logs, \u05d0\u05d5 \u05e0\u05e9\u05dc\u05d7 \u05de\u05d4\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4 \u05d3\u05e8\u05da '\u05e9\u05ea\u05e3'."],
                 ["\u05e4\u05e7\u05d3\u05d9\u05dd", "\u05e0\u05d2\u05df/\u05d4\u05e9\u05d4\u05d4, \u05de\u05d4\u05d9\u05e8\u05d5\u05ea 30\u00d7/120\u00d7/600\u00d7, \u05e1\u05e8\u05d2\u05dc \u05d3\u05d9\u05dc\u05d5\u05d2. \u05de\u05e9\u05de\u05d0\u05dc: \u05d4\u05d2\u05d3\u05e8\u05d5\u05ea (\u05d9\u05d7\u05d9\u05d3\u05d5\u05ea, \u05e2\u05e8\u05db\u05ea \u05e0\u05d5\u05e9\u05d0, \u05ea\u05e6\u05dc\u05d5\u05dd \u05d0\u05d5\u05d5\u05d9\u05e8, \u05e9\u05e4\u05d4) \u05d5\u05e2\u05d6\u05e8\u05d4. \u05de\u05d9\u05de\u05d9\u05df: \u05d6\u05d5\u05dd, \u05db\u05dc \u05d4\u05de\u05e1\u05dc\u05d5\u05dc, \u05de\u05e8\u05db\u05d5\u05d6, \u05e6\u05e4\u05d5\u05df/\u05db\u05d9\u05d5\u05d5\u05df."],
                 ["\u05d8\u05d1\u05e2\u05ea \u05d4\u05de\u05d8\u05d5\u05e1 \u05d5\u05ea\u05d2\u05d9\u05ea \u05d4\u05de\u05d9\u05e7\u05d5\u05dd", "\u05d9\u05e8\u05d5\u05e7 \u2014 GPS \u05d8\u05d5\u05d1; \u05db\u05ea\u05d5\u05dd \u2014 GPS \u05d7\u05dc\u05e9; \u05d0\u05d3\u05d5\u05dd \u2014 \u05de\u05d5\u05e2\u05e8\u05da (\u05d0\u05d9\u05df GPS) \u05d0\u05d5 \u05d6\u05de\u05df \u05d1\u05dc\u05d1\u05d3. \u05e1\u05d1\u05d9\u05d1 \u05de\u05d8\u05d5\u05e1 \u05de\u05d5\u05e2\u05e8\u05da \u05de\u05d5\u05e6\u05d2 \u05e2\u05d9\u05d2\u05d5\u05dc \u05e9\u05d2\u05d9\u05d0\u05d4 (2\u03c3) \u05d1\u05d0\u05d5\u05ea\u05d5 \u05e6\u05d1\u05e2, \u05db\u05de\u05d5 \u05d1\u05d8\u05dc\u05e4\u05d5\u05df."],
                 ["\u05e7\u05d5 \u05d8\u05d5\u05e8\u05e7\u05d9\u05d6", "\u05e8\u05e6\u05d9\u05e3 \u2014 \u05de\u05e1\u05dc\u05d5\u05dc \u05e9\u05e0\u05de\u05d3\u05d3 \u05d1\u05beGPS; \u05de\u05e7\u05d5\u05d5\u05e7\u05d5 \u2014 \u05e7\u05d8\u05e2 \u05de\u05d5\u05e2\u05e8\u05da: \u05dc\u05dc\u05d0 GPS, \u05d0\u05d5 \u05e4\u05e2\u05e8 \u05d1\u05e8\u05d9\u05e9\u05d5\u05dd \u05e9\u05d4\u05d5\u05e9\u05dc\u05dd \u05d1\u05d0\u05d9\u05e0\u05d8\u05e8\u05e4\u05d5\u05dc\u05e6\u05d9\u05d4 \u05d1\u05de\u05d4\u05d9\u05e8\u05d5\u05ea \u05d4\u05d9\u05d3\u05d5\u05e2\u05d4 \u05dc\u05e4\u05e0\u05d9\u05d5 \u05d5\u05d0\u05d7\u05e8\u05d9\u05d5. \u05d4\u05de\u05d8\u05d5\u05e1 \u05de\u05de\u05e9\u05d9\u05da \u05dc\u05e0\u05d5\u05e2 \u05d2\u05dd \u05d1\u05e7\u05d8\u05e2\u05d9\u05dd \u05d0\u05dc\u05d4."],
                 ["\u05de\u05d2\u05d1\u05dc\u05d5\u05ea", "\u05ea\u05d5\u05d5\u05d9\u05d5\u05ea \u05d4\u05de\u05e4\u05d4 \u05d5\u05ea\u05e6\u05dc\u05d5\u05dd \u05d4\u05d0\u05d5\u05d5\u05d9\u05e8 \u05d3\u05d5\u05e8\u05e9\u05d9\u05dd \u05e8\u05e9\u05ea; \u05dc\u05dc\u05d0 \u05e8\u05e9\u05ea \u05d4\u05de\u05e4\u05d4 \u05d4\u05d5\u05e7\u05d8\u05d5\u05e8\u05d9\u05ea \u05de\u05d5\u05e6\u05d2\u05ea \u05dc\u05dc\u05d0 \u05e9\u05de\u05d5\u05ea. \u05d0\u05d9\u05df \u05db\u05d0\u05df \u05d7\u05d9\u05e9\u05d5\u05d1 \u05de\u05d7\u05d3\u05e9 \u2014 \u05de\u05d5\u05e6\u05d2 \u05de\u05d4 \u05e9\u05e0\u05e8\u05e9\u05dd."]] },
    en: { title: "Replay", play: "Play", pause: "Pause", close: "Close", remaining: "Distance remaining", speed: "Ground speed", alt: "Altitude",
          playing: "playing", paused: "paused", loadHint: "Load a FlightInfo CSV flight log (folder button on the left, or drop it here)",
          noGnss: "No GPS", gnss: (n, a) => `${n} satellites \u00b1${a} m`, fixes: "fixes", duration: "duration",
          lPhase: "Phase", lGps: "GPS", lLogTime: "Log time", of: "of", srcFix: "GPS position", srcEst: "estimated position (as recorded)",
          phases: { GROUND: "on ground", TAKEOFF: "takeoff", CLIMB: "climb", CRUISE: "cruise", DESCENT: "descent", LANDED: "landed" },
          badLog: "Not a valid FlightInfo log", unknownAirports: "Unknown airports in log",
          help: [["What this is", "Desktop playback of a flight log recorded by the FlightInfo Android app: the position the app computed in flight (aircraft), the GPS-measured track (turquoise), the planned route (dashed) and the values that were on screen."],
                 ["Loading", "Folder button on the left, or drag a CSV onto the screen. Logs live on the phone under Android/data/com.galker.flightinfo/files/logs, or are sent from the app via Share."],
                 ["Controls", "Play/Pause, 30x/120x/600x, seek bar. Left: settings (units, theme, imagery, language) and help. Right: zoom, whole route, centre, north/track-up."],
                 ["Aircraft ring and position badge", "Green: good GPS; orange: weak GPS; red: estimated (no GPS) or time only. An estimated aircraft shows a 2-sigma error disc in the same colour, as on the phone."],
                 ["Turquoise line", "Solid: GPS-measured track. Dashed: estimated section, without GPS or across a recording gap filled by interpolation at the speed known before and after it. The aircraft keeps moving through these sections."],
                 ["Limits", "Map labels and imagery need a network; offline the vector map shows without names. Nothing is recomputed here: what was recorded is shown."]] },
  };

  // ---------------- State ----------------
  const S = { pc: null, lang: "he", theme: "DAY", du: "KM", au: "M", su: "KMH", aerial: false, trackUp: false,
              rows: [], idx: 0, playing: false, speed: 120, timer: null, lastWall: 0, route: null, origin: null, dest: null, mapReady: false, follow: true, lastGesture: 0 };
  const D = window.FI_DATA;
  const $ = (id) => document.getElementById(id);
  const esc = (x) => String(x).replace(/&/g, "&amp;").replace(/</g, "&lt;");

  // ---------------- Geodesy (spherical, same as the app) ----------------
  const R = 6371008.8, rad = (d) => d * Math.PI / 180, deg = (r) => r * 180 / Math.PI;
  function dist(a, b) { const dLat = rad(b[1] - a[1]), dLon = rad(b[0] - a[0]); const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLon / 2) ** 2; return 2 * R * Math.asin(Math.min(1, Math.sqrt(h))); }
  function destPoint(a, brgDeg, dM) { const la1 = rad(a[1]), lo1 = rad(a[0]), br = rad(brgDeg), ang = dM / R;
    const la2 = Math.asin(Math.sin(la1) * Math.cos(ang) + Math.cos(la1) * Math.sin(ang) * Math.cos(br));
    const lo2 = lo1 + Math.atan2(Math.sin(br) * Math.sin(ang) * Math.cos(la1), Math.cos(ang) - Math.sin(la1) * Math.sin(la2));
    return [((deg(lo2) + 540) % 360) - 180, deg(la2)]; }
  function greatCircle(a, b, n) { // a,b = [lon,lat]
    const la1 = rad(a[1]), lo1 = rad(a[0]), la2 = rad(b[1]), lo2 = rad(b[0]); const d = dist(a, b) / R; const out = [];
    for (let i = 0; i <= n; i++) { const f = i / n; const A = Math.sin((1 - f) * d) / Math.sin(d), B = Math.sin(f * d) / Math.sin(d);
      const x = A * Math.cos(la1) * Math.cos(lo1) + B * Math.cos(la2) * Math.cos(lo2), y = A * Math.cos(la1) * Math.sin(lo1) + B * Math.cos(la2) * Math.sin(lo2), z = A * Math.sin(la1) + B * Math.sin(la2);
      out.push([deg(Math.atan2(y, x)), deg(Math.atan2(z, Math.sqrt(x * x + y * y)))]); }
    return out;
  }

  // ---------------- Formatting (same units as the app) ----------------
  const fmt = {
    dist: (m) => S.du === "NM" ? `${Math.round(m / 1852).toLocaleString("en-US")} nm` : S.du === "MI" ? `${Math.round(m / 1609.344).toLocaleString("en-US")} mi` : `${Math.round(m / 1000).toLocaleString("en-US")} km`,
    alt: (m) => S.au === "FT" ? `${Math.round(m * 3.28084).toLocaleString("en-US")} ft` : `${Math.round(m).toLocaleString("en-US")} m`,
    speed: (mps) => S.su === "KT" ? `${Math.round(mps * 1.943844)} kt` : S.su === "MPH" ? `${Math.round(mps * 2.236936)} mph` : `${Math.round(mps * 3.6)} km/h`,
    hm: (ms) => { const d = new Date(ms); return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`; },
    dur: (s) => `${String(Math.floor(s / 3600)).padStart(2, "0")}:${String(Math.floor(s % 3600 / 60)).padStart(2, "0")}.${String(s % 60).padStart(2, "0")}`,
  };

  // ---------------- CSV log ----------------
  function parseLog(text) {
    const lines = text.split(/\r?\n/); let header = null; const rows = []; const meta = {}; let lastFix = "";
    for (const ln of lines) {
      if (!ln) continue;
      if (ln[0] === "#") { for (const m of ln.matchAll(/(\w+)=(\S+)/g)) meta[m[1]] = m[2];
        if (/measured GNSS position when a fix is fresh|clean_log\.py|cleaned/i.test(ln)) meta.estTrusted = true; continue; }
      if (ln.startsWith("time_utc")) { header = ln.split(","); continue; }
      if (!header) continue;
      const c = ln.split(","); const g = (n) => { const i = header.indexOf(n); return i < 0 ? "" : (c[i] || ""); };
      const t = Number(g("epoch_ms")); if (!t) continue;
      const gt = g("gnss_time_ms"); const newFix = gt && gt !== lastFix; if (newFix) lastFix = gt;
      rows.push({
        t, phase: g("phase") || "CRUISE", mode: g("mode"), tracking: g("tracking"),
        lat: Number(g("est_lat")), lon: Number(g("est_lon")), remaining: Number(g("remaining_m")), speed: Number(g("speed_mps")), alt: Number(g("alt_m")), track: Number(g("track_deg")),
        ete: g("ete_s") === "" ? null : Number(g("ete_s")), level: g("sensor_level") === "" ? null : Number(g("sensor_level")),
        fix: newFix ? { lat: Number(g("gnss_lat")), lon: Number(g("gnss_lon")), hacc: Number(g("gnss_hacc_m")), sats: Number(g("gnss_sats_used")), q: g("gnss_quality") } : null,
        fixAge: gt ? (t - Number(gt)) / 1000 : null, sats: Number(g("gnss_sats_used")), vis: Number(g("gnss_sats_visible")), hacc: Number(g("gnss_hacc_m")), q: g("gnss_quality"),
      });
    }
    // Logs written by app 4.x or later (measured-first) and cleaned files carry a trustworthy estimate;
    // 3.x logs anchored the estimate to the plan and must be dead-reckoned instead.
    const major = parseInt((meta.app_version || "0").split(".")[0], 10);
    if (major >= 4) meta.estTrusted = true;
    return { rows, meta };
  }

  function sensorLevel(r) {
    if (r.level !== null && !Number.isNaN(r.level)) return r.level;
    if (r.tracking === "ESTIMATE" || r.mode === "PREDICTED_ONLY") return 0;                 // older logs: derive
    if (r.mode === "ROUTE_CONSTRAINED") return 1;
    return r.q === "GOOD" || (r.hacc && r.hacc <= 100 && r.sats >= 5) ? 3 : 2;
  }

  // ---------------- Map ----------------
  let map;
  function pal() { return PALETTE[S.theme]; }
  function style() {
    const p = pal();
    const src = { land: { type: "geojson", data: D.land }, countries: { type: "geojson", data: D.countries }, lakes: { type: "geojson", data: D.lakes },
      borders: { type: "geojson", data: D.borders }, places: { type: "geojson", data: D.places }, clabels: { type: "geojson", data: D.countryLabels },
      planned: { type: "geojson", data: empty() }, flown: { type: "geojson", data: empty() }, track: { type: "geojson", data: empty() }, trackest: { type: "geojson", data: empty() },
      uncert: { type: "geojson", data: empty() },
      airports: { type: "geojson", data: empty() }, aircraft: { type: "geojson", data: empty() } };
    if (S.aerial) src.aerial = { type: "raster", tiles: [P.GIBS], tileSize: 256, maxzoom: 8 };
    const layers = [{ id: "bg", type: "background", paint: { "background-color": p.bg } }];
    if (S.aerial) layers.push({ id: "aerial", type: "raster", source: "aerial", paint: { "raster-brightness-max": S.theme === "NIGHT" ? 0.75 : 1 } });
    else layers.push({ id: "land", type: "fill", source: "land", paint: { "fill-color": p.land } },
      { id: "countries", type: "fill", source: "countries", paint: { "fill-color": ["match", ["to-number", ["get", "color"]], 1, p.fills[0], 2, p.fills[1], 3, p.fills[2], 4, p.fills[3], 5, p.fills[4], 6, p.fills[5], 7, p.fills[6], p.land] } },
      { id: "lakes", type: "fill", source: "lakes", paint: { "fill-color": p.lake } });
    layers.push({ id: "borders", type: "line", source: "borders", paint: { "line-color": p.border, "line-width": 0.9 } },
      { id: "uncert", type: "fill", source: "uncert", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.18 } },
      { id: "uncert-line", type: "line", source: "uncert", paint: { "line-color": ["get", "color"], "line-width": 1.2, "line-opacity": 0.7, "line-dasharray": [2, 2] } },
      { id: "planned", type: "line", source: "planned", paint: { "line-color": p.original, "line-width": 1.2, "line-dasharray": [1, 3] } },
      { id: "governing-casing", type: "line", source: "flown", paint: { "line-color": p.halo, "line-width": 4.5, "line-opacity": 0.6 } },
      { id: "governing", type: "line", source: "flown", paint: { "line-color": p.planned, "line-width": 2.5, "line-dasharray": [2, 1.5] } },
      { id: "track-casing", type: "line", source: "track", paint: { "line-color": p.halo, "line-width": 4, "line-opacity": 0.5 } },
      { id: "track", type: "line", source: "track", paint: { "line-color": p.track, "line-width": 2.5 } },
      { id: "trackest", type: "line", source: "trackest", paint: { "line-color": p.track, "line-width": 2.5, "line-dasharray": [1.2, 1.2], "line-opacity": 0.9 } });
    const fonts = ["notosansbold"];
    layers.push({ id: "clabels", type: "symbol", source: "clabels", filter: ["<=", ["get", "labelrank"], ["step", ["zoom"], 2, 3, 4, 4.5, 6, 6, 99]],
        layout: { "text-field": ["get", "name"], "text-font": fonts, "text-size": ["interpolate", ["linear"], ["zoom"], 2, 10, 5, 13, 8, 17], "text-letter-spacing": 0.12, "text-transform": "uppercase", "text-padding": 4 },
        paint: { "text-color": p.countryText, "text-halo-color": p.halo, "text-halo-width": 1.6 } },
      { id: "places-dot", type: "circle", source: "places", filter: ["<=", ["get", "scalerank"], ["step", ["zoom"], 0, 3, 1, 4, 3, 5, 5, 6, 6, 7, 7, 8, 99]],
        paint: { "circle-color": p.placeText, "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, 1.5, 8, 3], "circle-stroke-color": p.halo, "circle-stroke-width": 0.8 } },
      { id: "places", type: "symbol", source: "places", filter: ["<=", ["get", "scalerank"], ["step", ["zoom"], 0, 3, 1, 4, 3, 5, 5, 6, 6, 7, 7, 8, 99]],
        layout: { "text-field": ["get", "name"], "text-font": ["notosans"], "text-size": ["interpolate", ["linear"], ["zoom"], 3, 10, 8, 13], "text-anchor": "top", "text-offset": [0, 0.5], "text-optional": true },
        paint: { "text-color": p.placeText, "text-halo-color": p.halo, "text-halo-width": 1.4 } },
      { id: "airports", type: "symbol", source: "airports", layout: { "text-field": ["get", "code"], "text-font": fonts, "text-size": 12, "text-anchor": "bottom", "text-offset": [0, -0.6], "text-allow-overlap": true },
        paint: { "text-color": p.placeText, "text-halo-color": p.halo, "text-halo-width": 1.5 } },
      { id: "airports-dot", type: "circle", source: "airports", paint: { "circle-color": p.placeText, "circle-radius": 4, "circle-stroke-color": p.bg, "circle-stroke-width": 1.5 } },
      { id: "aircraft", type: "symbol", source: "aircraft", layout: { "icon-image": ["get", "icon"], "icon-rotate": ["get", "bearing"], "icon-rotation-alignment": "map", "icon-allow-overlap": true, "icon-ignore-placement": true, "icon-size": 0.6 } });
    return { version: 8, glyphs: P.GLYPHS, sources: src, layers };
  }
  function empty() { return { type: "FeatureCollection", features: [] }; }
  function line(coords) { return { type: "FeatureCollection", features: coords.length > 1 ? [{ type: "Feature", geometry: { type: "LineString", coordinates: coords }, properties: {} }] : [] }; }

  // Aircraft icons: dark disc, coloured ring, white silhouette (as on the phone).
  function aircraftImage(ring, solid) {
    const size = 80, c = document.createElement("canvas"); c.width = c.height = size; const g = c.getContext("2d");
    g.fillStyle = "rgba(8,16,26,0.6)"; g.beginPath(); g.arc(size / 2, size / 2, size / 2 - 1, 0, 2 * Math.PI); g.fill();
    g.lineWidth = 4; g.strokeStyle = ring; g.beginPath(); g.arc(size / 2, size / 2, size / 2 - 3, 0, 2 * Math.PI); g.stroke();
    const s = 0.7; g.save(); g.translate((size - 64 * s) / 2, (size - 64 * s) / 2); g.scale(s, s);
    const pts = [[32, 4], [36, 10], [36, 26], [60, 40], [60, 45], [36, 38], [35, 52], [43, 57], [43, 60], [32, 58], [21, 60], [21, 57], [29, 52], [28, 38], [4, 45], [4, 40], [28, 26], [28, 10]];
    g.beginPath(); pts.forEach((pt, i) => i ? g.lineTo(pt[0], pt[1]) : g.moveTo(pt[0], pt[1])); g.closePath();
    if (solid) { g.fillStyle = "#fff"; g.fill(); g.lineWidth = 2.5; g.strokeStyle = "#08101a"; g.stroke(); } else { g.lineWidth = 3.5; g.strokeStyle = "#fff"; g.stroke(); }
    g.restore();
    return g.getImageData(0, 0, size, size);
  }

  // Serve the embedded glyph ranges; unknown ranges get an empty (valid) protobuf.
  function b64ToBuf(b64) { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u.buffer; }
  if (maplibregl.addProtocol) {
    maplibregl.addProtocol("embedded", (params) => {
      const m = /embedded:\/\/glyphs\/([^/]+)\/([^/]+)\.pbf/.exec(params.url);
      const key = m ? `${decodeURIComponent(m[1]).split(",")[0]}/${m[2]}` : "";
      const b64 = D.glyphs && D.glyphs[key];
      return Promise.resolve({ data: b64 ? b64ToBuf(b64) : new ArrayBuffer(0) });
    });
  }

  /**
   * Graduated scale bar: a "nice" total length (1, 2, 5 x 10^n) within 150 px, split into 4 or 5
   * alternating segments with tick labels at 0, the middle and the end, in the selected unit.
   */
  function updateScaleBar() {
    if (!map) return;
    const el = $("scalebar"); const lat = map.getCenter().lat;
    const mpp = 40075016.686 * Math.cos(rad(lat)) / (256 * Math.pow(2, map.getZoom()));
    const unitM = S.du === "NM" ? 1852 : S.du === "MI" ? 1609.344 : 1000, label = S.du === "NM" ? "nm" : S.du === "MI" ? "mi" : "km";
    const maxUnits = 150 * mpp / unitM;
    const pow = Math.pow(10, Math.floor(Math.log10(Math.max(maxUnits, 1e-6))));
    const nice = [5, 2, 1].map((x) => x * pow).find((x) => x <= maxUnits) || pow;
    const px = nice * unitM / mpp; const n = String(nice / pow)[0] === "5" ? 5 : 4;
    const fmtU = (u) => u >= 1 ? String(Math.round(u * 100) / 100) : String(Math.round(u * 1000)) + (S.du === "KM" ? " m" : "");
    let segs = ""; for (let i = 0; i < n; i++) segs += `<i class="${i % 2 ? "b" : "a"}" style="width:${px / n}px"></i>`;
    el.innerHTML = `<div class="bar" style="width:${px}px"><div class="ticks"><span>0</span><span>${fmtU(nice / 2)}</span><span>${fmtU(nice)}</span></div><div class="segs">${segs}</div></div><span class="unit">${nice >= 1 || S.du !== "KM" ? label : ""}</span>`;
    // Attached to the top of the data panel below it.
    const panel = $("panel"); if (panel) el.style.bottom = (panel.offsetHeight + 16) + "px";
  }

  function buildMap() {
    if (map) { map.remove(); }
    S.mapReady = false;
    map = new maplibregl.Map({ container: "map", style: style(), center: [20, 40], zoom: 3, attributionControl: false, dragRotate: false, pitchWithRotate: false });
    map.touchZoomRotate.disableRotation();
    map.on("move", updateScaleBar); map.on("zoom", updateScaleBar);
    map.on("dragstart", () => { S.lastGesture = Date.now(); });
    map.on("load", () => {
      P.LEVEL_COLORS.forEach((col, i) => map.addImage(`aircraft-${i}`, aircraftImage(col, i >= 2)));
      S.mapReady = true; pushStatic(); render(true); updateScaleBar();
    });
  }
  function pushStatic() {
    if (!S.mapReady || !S.route) return;
    map.getSource("planned").setData(line(S.route));
    map.getSource("airports").setData({ type: "FeatureCollection", features: [
      { type: "Feature", geometry: { type: "Point", coordinates: [S.origin.lon, S.origin.lat] }, properties: { code: S.origin.iata } },
      { type: "Feature", geometry: { type: "Point", coordinates: [S.dest.lon, S.dest.lat] }, properties: { code: S.dest.iata } }] });
  }

  // ---------------- Replay ----------------
  // Rows further apart than this are a recording gap: motion is interpolated between the
  // last known position before and the first known position after, at the implied speed.
  const GAP_MS = 5000;
  function posOf(r) { return r.fix && !Number.isNaN(r.fix.lat) ? [r.fix.lon, r.fix.lat] : (!Number.isNaN(r.lat) ? [r.lon, r.lat] : null); }
  function fillGaps(rows) {
    // Anchor the interpolation on MEASURED positions only (last fix before the gap, first fix after it):
    // recorded estimates can be wrong (v3 logs placed a never-fixed aircraft at the destination).
    const out = [];
    let lastFix = null;
    const nextFixFrom = (i) => { for (let k = i; k < rows.length && k < i + 600; k++) { const f = rows[k].fix; if (f && !Number.isNaN(f.lat)) return [f.lon, f.lat]; } return null; };
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      if (i > 0) {
        const prev = rows[i - 1]; const gap = r.t - prev.t;
        const a = lastFix, b = nextFixFrom(i);
        if (gap > GAP_MS && a && b) {
          const n = Math.floor(gap / 1000); const d = dist(a, b); const v = d / (gap / 1000);
          if (v <= 350) {                                            // plausible for an airliner; otherwise just jump
            const pts = greatCircle(a, b, n);
            const alt0 = Number.isNaN(prev.alt) ? r.alt : prev.alt, alt1 = Number.isNaN(r.alt) ? alt0 : r.alt;
            for (let k = 1; k < n; k++) {
              const f = k / n;
              out.push({ t: prev.t + k * 1000, phase: v > 40 ? (prev.phase === "GROUND" ? "CLIMB" : prev.phase) : prev.phase, mode: "ROUTE_CONSTRAINED", tracking: prev.tracking, synthetic: true,
                lat: pts[k][1], lon: pts[k][0], remaining: prev.remaining - (prev.remaining - r.remaining) * f, speed: v,
                alt: alt0 + (alt1 - alt0) * f, track: bearing(pts[k], pts[Math.min(n, k + 1)]), ete: null, level: 1,
                fix: null, fixAge: null, sats: NaN, hacc: NaN, q: "" });
            }
          }
        }
      }
      if (r.fix && !Number.isNaN(r.fix.lat)) lastFix = [r.fix.lon, r.fix.lat];
      out.push(r);
    }
    return out;
  }
  function bearing(a, b) { const la1 = rad(a[1]), la2 = rad(b[1]), dl = rad(b[0] - a[0]);
    const y = Math.sin(dl) * Math.cos(la2), x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl); return (deg(Math.atan2(y, x)) + 360) % 360; }

  function loadText(text, fileName) {
    const parsed = parseLog(text);
    const rows = fillGaps(parsed.rows), meta = parsed.meta;
    if (rows.length < 2) { $("line3").textContent = T[S.lang].badLog; return; }
    if ((!meta.origin || !meta.destination) && fileName) {          // older logs: yyyymmdd_hhmm_ORG_DST_FLIGHT.csv
      const parts = fileName.replace(/\.csv$/i, "").split("_");
      if (parts.length >= 4) { meta.origin = meta.origin || parts[2]; meta.destination = meta.destination || parts[3]; meta.flight = meta.flight || parts[4] || ""; }
    }
    let o = D.airports[meta.origin], d = D.airports[meta.destination];
    if (!o || !d) {
      // Free recording (no airports): stand in with the first and last fix.
      const fx = rows.filter((r) => r.fix && !Number.isNaN(r.fix.lat));
      if (!fx.length) { $("line3").textContent = T[S.lang].unknownAirports; return; }
      o = o || { lat: fx[0].fix.lat, lon: fx[0].fix.lon, elev: 0, name: "start" }; d = d || { lat: fx[fx.length - 1].fix.lat, lon: fx[fx.length - 1].fix.lon, elev: 0, name: "end" };
      meta.origin = meta.origin || "START"; meta.destination = meta.destination || "END";
    }
    S.rows = rows; S.origin = { ...o, iata: meta.origin }; S.dest = { ...d, iata: meta.destination }; S.meta = meta; S.estTrusted = !!meta.estTrusted;
    S.route = greatCircle([o.lon, o.lat], [d.lon, d.lat], P.ROUTE_SAMPLES);
    S.idx = 0; S.playT = undefined; S.follow = true; resetPositions(); pause();
    $("title").textContent = `FlightInfo ${APP_VERSION} \u00B7 Replay ${meta.origin} \u2192 ${meta.destination} ${meta.flight || ""}`;
    pushStatic(); render(true);
    if (S.mapReady) { const p0 = S.shownPos || (S.origin ? [S.origin.lon, S.origin.lat] : null); if (p0) { S.lastGesture = 0; map.jumpTo({ center: p0, zoom: 10 }); } }
  }
  // Two polylines: measured (GPS fixes, solid) and estimated (propagated / interpolated, dashed),
  // built from the position actually shown for each row so the line never has holes.
  /**
   * Per-row position cache, built incrementally: measured (fresh fix) or dead-reckoned from the last
   * measured point at the last measured speed (course held 90 s, then toward the destination).
   * Also the 1-sigma accuracy: the fix accuracy while measured, growing by DRIFT_RATE of the distance
   * flown without a fix (same rule as the phone). The recorded estimate columns are never used.
   */
  const DRIFT_RATE = 0.06, HOLD_COURSE_S = 90, FRESH_MS = 10000;
  function resetPositions() { S.pc = { pos: [], fresh: [], sig: [], v: [], n: 0, lastFix: null, lastFixT: -Infinity, lastFixV: NaN, lastFixTrack: NaN, lastFixAcc: NaN, prevFix: null }; }
  function ensurePositions(upto) {
    const c = S.pc; const rows = S.rows;
    for (let k = c.n; k <= upto && k < rows.length; k++) {
      const r = rows[k];
      if (r.fix && !Number.isNaN(r.fix.lat)) {
        if (c.prevFix) { const d = dist([c.prevFix.lon, c.prevFix.lat], [r.fix.lon, r.fix.lat]); if (d > 200) c.lastFixTrack = bearing([c.prevFix.lon, c.prevFix.lat], [r.fix.lon, r.fix.lat]); }
        c.prevFix = r.fix; c.lastFix = r.fix; c.lastFixT = r.t; c.lastFixAcc = r.fix.hacc; if (!Number.isNaN(r.speed) && r.speed > 1.5) c.lastFixV = r.speed;
      }
      const fresh = r.t - c.lastFixT < FRESH_MS;
      let pos = null, sig = NaN, v = NaN;
      if (fresh) { pos = [c.lastFix.lon, c.lastFix.lat]; sig = c.lastFixAcc; v = !Number.isNaN(r.speed) ? r.speed : c.lastFixV; }
      else if ((r.synthetic || S.estTrusted) && !Number.isNaN(r.lat) && r.mode !== "PREDICTED_ONLY") { pos = [r.lon, r.lat]; v = r.speed;
        sig = (Number.isNaN(c.lastFixAcc) ? 50 : c.lastFixAcc) + DRIFT_RATE * (c.lastFix ? dist([c.lastFix.lon, c.lastFix.lat], pos) : 0); }
      else if (c.lastFix) {
        const age = (r.t - c.lastFixT) / 1000; const a = [c.lastFix.lon, c.lastFix.lat];
        v = c.lastFixV > 1.5 ? c.lastFixV : (!Number.isNaN(r.speed) ? r.speed : 0);
        const brg = age < HOLD_COURSE_S && !Number.isNaN(c.lastFixTrack) ? c.lastFixTrack : (S.dest ? bearing(a, [S.dest.lon, S.dest.lat]) : c.lastFixTrack);
        pos = Number.isNaN(brg) ? a : destPoint(a, brg, v * age);
        sig = (Number.isNaN(c.lastFixAcc) ? 50 : c.lastFixAcc) + DRIFT_RATE * v * age;
      }
      c.pos[k] = pos; c.fresh[k] = fresh; c.sig[k] = sig; c.v[k] = v;
    }
    c.n = Math.max(c.n, Math.min(upto + 1, rows.length));
  }
  /** Solid (measured) and dashed (estimated) polylines up to row i, from the position cache. */
  function trackUpTo(i) {
    ensurePositions(i);
    const meas = [], est = []; let curMeas = [], curEst = []; let last = null;
    for (let k = 0; k <= i; k++) {
      const c = S.pc.pos[k]; if (!c) continue;
      if (last && k !== i && dist(last, c) < P.TRACK_MIN_M) {
        // keep the vertex anyway if the heading changed: the drawn path must follow the real path, not pivot
        const prev2 = S.pc.fresh[k] ? (curMeas.length > 1 ? curMeas[curMeas.length - 2] : null) : (curEst.length > 1 ? curEst[curEst.length - 2] : null);
        if (!(prev2 && dist(last, c) > 25 && Math.abs(((bearing(prev2, last) - bearing(last, c)) + 540) % 360 - 180) > P.TRACK_TURN_DEG)) continue;
      }
      if (S.pc.fresh[k]) {
        // Re-acquisition: the past is now known - the estimated stretch becomes the direct line between the two fixes.
        if (curEst.length) { est.push([curEst[0], c]); curEst = []; }
        curMeas.push(c);
      } else { if (curMeas.length) { meas.push(curMeas); curEst.push(curMeas[curMeas.length - 1]); curMeas = []; } curEst.push(c); }
      last = c;
    }
    if (curMeas.length) meas.push(curMeas); if (curEst.length) est.push(curEst);
    return { meas, est };
  }
  /**
   * Position at an arbitrary flight time between rows: linear between the two neighbouring row
   * positions when they are 1-2 s apart; across a larger hole (rows missing and not interpolable)
   * the aircraft continues from the earlier row at that row's speed and course - steady motion.
   */
  function posAtTime(playT) {
    const rows = S.rows; let i = S.idx; ensurePositions(Math.min(i + 1, rows.length - 1));
    const p0 = S.pc.pos[i]; if (!p0) return null;
    if (i >= rows.length - 1) return p0;
    const t0 = rows[i].t, t1 = rows[i + 1].t, p1 = S.pc.pos[i + 1];
    const f = Math.max(0, Math.min(1, (playT - t0) / Math.max(1, t1 - t0)));
    if (p1 && t1 - t0 <= 2500) return [p0[0] + (p1[0] - p0[0]) * f, p0[1] + (p1[1] - p0[1]) * f];
    const v = S.pc.v[i]; const brg = p1 ? bearing(p0, p1) : (S.dest ? bearing(p0, [S.dest.lon, S.dest.lat]) : NaN);
    if (Number.isNaN(brg) || Number.isNaN(v)) return p0;
    return destPoint(p0, brg, v * Math.max(0, (playT - t0) / 1000));
  }
  function circlePoly(center, radiusM, color) {
    const pts = []; for (let i = 0; i <= 48; i++) pts.push(destPoint(center, i * 7.5, radiusM));
    return { type: "FeatureCollection", features: [{ type: "Feature", geometry: { type: "Polygon", coordinates: [pts] }, properties: { color } }] };
  }
  function multi(segs) { return { type: "FeatureCollection", features: segs.filter((s) => s.length > 1).map((s) => ({ type: "Feature", geometry: { type: "LineString", coordinates: s }, properties: {} })) }; }
  let trackCache = { upto: -1, pts: { meas: [], est: [] }, at: 0 };
  function render(force) {
    const r = S.rows[S.idx]; if (!r) return;
    if (S.pc.n === 0 || force) { if (force) resetPositions(); }
    ensurePositions(S.idx);
    if (S.mapReady) {
      // The polylines are rebuilt at most 4 times per second (they cost O(n)); the aircraft moves every frame.
      const nowW = Date.now();
      if (trackCache.upto > S.idx || force) trackCache = { upto: -1, pts: { meas: [], est: [] }, at: 0 };
      if (trackCache.upto < S.idx && (force || nowW - (trackCache.at || 0) > 250)) { trackCache.pts = trackUpTo(S.idx); trackCache.upto = S.idx; trackCache.at = nowW;
        map.getSource("track").setData(multi(trackCache.pts.meas)); map.getSource("trackest").setData(multi(trackCache.pts.est)); }
    }
    const shown = posAtTime(S.playT !== undefined && S.playT >= r.t ? S.playT : r.t);
    S.shownPos = shown;
    window.FI_DEBUG = { pos: shown, t: S.playT, idx: S.idx };     // for automated tests
    const tShown = (S.playT !== undefined && S.playT >= r.t) ? S.playT : r.t;
    $("vTime").textContent = fmt.hm(tShown);
    // Remaining distance: from the shown position to the destination when we have a real position
    // (fix or interpolated); the recorded value only when nothing better exists.
    const shownPos = S.shownPos || null;
    const remaining = shownPos && S.dest ? dist(shownPos, [S.dest.lon, S.dest.lat]) : r.remaining;
    const sig = S.pc.sig[S.idx];
    $("vRemaining").textContent = Number.isNaN(remaining) ? "--" : fmt.dist(remaining);
    $("vSpeed").textContent = Number.isNaN(r.speed) ? "--" : fmt.speed(r.speed);
    $("vAlt").textContent = Number.isNaN(r.alt) ? "--" : fmt.alt(r.alt);
    const fresh = r.fixAge !== null && r.fixAge < 10;
    $("vPhase").textContent = PH[r.phase] || r.phase.toLowerCase();
    $("vSats").textContent = fresh && !Number.isNaN(r.sats) ? String(r.sats).padStart(2, " ") + "/" + (Number.isNaN(r.vis) ? "--" : String(r.vis).padStart(2, " ")) : "--";
    // Accuracy in both states: measured = fix accuracy; estimated = last accuracy + 6 % of the distance flown without a fix.
    $("vAcc").textContent = Number.isNaN(sig) ? "--" : sig < 1000 ? `\u00B1${String(Math.round(sig)).padStart(3, " ")} m` : `\u00B1${(sig / 1000).toFixed(sig < 10000 ? 1 : 0)} km`;
    const src = $("vSrc");
    const good = fresh && r.hacc <= 100 && r.sats >= 5, weak = fresh && !good;
    S.shownFresh = fresh;
    src.textContent = good ? "GPS good" : weak ? "GPS weak" : (r.tracking === "ESTIMATE" || r.mode === "PREDICTED_ONLY") ? "time only" : "estimated";
    src.className = "badge " + (good ? "good" : weak ? "weak" : "est");
    if (S.mapReady) {
      // Error area around the aircraft (2 sigma), as on the phone; only while the position is not measured.
      const pos0 = S.shownPos;
      map.getSource("uncert").setData(!fresh && pos0 && !Number.isNaN(sig) ? circlePoly(pos0, 2 * sig, good ? "#2E7D32" : weak ? "#F57C00" : "#C62828") : empty());
    }
    $("vLog").textContent = `${fmt.dur(Math.round((tShown - S.rows[0].t) / 1000))} / ${fmt.dur(Math.round((S.rows[S.rows.length - 1].t - S.rows[0].t) / 1000))}`;
    $("line3").textContent = "";
    $("state").textContent = S.playing ? `playing \u00B7 ${S.speed}\u00D7` : "paused";
    $("seek").value = Math.round(1000 * S.idx / (S.rows.length - 1));
    if (!S.mapReady) return;
    // Measured first, as in the app since v4: with a fresh fix the aircraft is where GPS put it, otherwise
    // where the phone estimated it. Older logs (v3) recorded a route-anchored estimate, which this corrects.
    const pos = S.shownPos || null;
    const lvl = sensorLevel(r);
    map.getSource("aircraft").setData({ type: "FeatureCollection", features: !pos ? [] : [{ type: "Feature", geometry: { type: "Point", coordinates: pos }, properties: { icon: `aircraft-${lvl}`, bearing: Number.isNaN(r.track) ? 0 : r.track } }] });
    // Governing route: direct line from the aircraft to the destination (the plan stays as the faint line).
    map.getSource("flown").setData(pos && S.dest ? line(greatCircle(pos, [S.dest.lon, S.dest.lat], 60)) : empty());
    if (S.follow && Date.now() - S.lastGesture > 20000 && pos) map.jumpTo({ center: pos, bearing: S.trackUp && !Number.isNaN(r.track) ? r.track : 0 });
  }
  // Flight clock advances by wall time x speed; the row index follows it and the position is
  // interpolated between rows, so motion is steady at any speed and across missing rows.
  function tick() {
    if (!S.playing) return;
    const now = Date.now(); const wallDt = now - S.lastWall; S.lastWall = now;
    S.playT = (S.playT === undefined ? S.rows[S.idx].t : S.playT) + wallDt * S.speed;
    while (S.idx < S.rows.length - 1 && S.rows[S.idx + 1].t <= S.playT) S.idx++;
    if (S.idx >= S.rows.length - 1) { S.playT = S.rows[S.idx].t; render(false); pause(); return; }
    render(false);
    S.timer = requestAnimationFrame(tick);
  }
  function play() { if (!S.rows.length || S.playing) return; if (S.idx >= S.rows.length - 1) S.idx = 0; S.playT = S.rows[S.idx].t; S.playing = true; S.lastWall = Date.now(); $("btnPlay").textContent = "Pause"; tick(); }
  function pause() { S.playing = false; cancelAnimationFrame(S.timer); clearTimeout(S.timer); $("btnPlay").textContent = "Play"; render(false); }
  function fitRoute() { if (!S.route) return; const b = new maplibregl.LngLatBounds(); S.route.forEach((c) => b.extend(c)); S.lastGesture = Date.now(); map.fitBounds(b, { padding: 60, duration: 600 }); }

  // ---------------- UI wiring ----------------
  function applyLang() {
    const u = UI[S.lang], t = T[S.lang];
    document.documentElement.dir = S.lang === "he" ? "rtl" : "ltr"; document.documentElement.lang = S.lang;
    $("sTitle").textContent = u.settings; $("sClose").textContent = u.back; $("hTitle").textContent = u.help; $("hClose").textContent = u.back;
    $("oDist").textContent = u.dist; $("oAlt").textContent = u.alt; $("oSpeed").textContent = u.speed; $("oTheme").textContent = u.theme; $("oAerial").textContent = u.aerial; $("oLang").textContent = u.lang;
    $("btnOpen").title = u.openTip;
    if (!S.rows.length) $("line3").textContent = u.loadHint;
    const appHelp = (D.help && D.help[S.lang]) || [];
    $("helpBody").innerHTML = t.help.concat(appHelp).map(([h, b]) => `<h4>${esc(h)}</h4><p>${esc(b)}</p>`).join("");
    render(false);
  }
  function applyTheme() { document.body.classList.toggle("night", S.theme === "NIGHT"); buildMap(); }
  function chipGroup(attr, onPick) {
    document.querySelectorAll(`[data-${attr}]`).forEach((b) => b.addEventListener("click", () => {
      document.querySelectorAll(`[data-${attr}]`).forEach((x) => x.classList.remove("selected")); b.classList.add("selected"); onPick(b.dataset[attr]); }));
  }
  // Settings persist across sessions (localStorage; ignored when unavailable, e.g. some file:// sandboxes).
  const SETTINGS_KEY = "flightinfo.replay.settings";
  function saveSettings() { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify({ lang: S.lang, theme: S.theme, du: S.du, au: S.au, su: S.su, aerial: S.aerial, speed: S.speed, trackUp: S.trackUp })); } catch (e) { } }
  function loadSettings() {
    try { const o = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}"); for (const k of ["lang", "theme", "du", "au", "su", "aerial", "speed", "trackUp"]) if (o[k] !== undefined) S[k] = o[k]; } catch (e) { }
    // reflect into the chip groups
    for (const [attr, val] of [["lang", S.lang], ["theme", S.theme], ["du", S.du], ["au", S.au], ["su", S.su], ["aerial", S.aerial ? "1" : "0"], ["speed", String(S.speed)]]) {
      document.querySelectorAll(`[data-${attr}]`).forEach((b) => b.classList.toggle("selected", b.dataset[attr] === val));
    }
  }

  function init() {
    document.title = `FlightInfo Desktop Replay ${APP_VERSION}`;
    $("title").textContent = `FlightInfo ${APP_VERSION} \u2014 Replay`;
    $("about").innerHTML += ` <a href="${PAGE_URL}" target="_blank" rel="noopener">${PAGE_URL}</a>`;
    resetPositions();
    loadSettings();
    document.body.classList.toggle("night", S.theme === "NIGHT");
    buildMap(); applyLang();
    $("btnPlay").onclick = () => S.playing ? pause() : play();
    $("btnClose").onclick = () => { pause(); S.rows = []; S.route = null; if (S.mapReady) { ["planned", "flown", "track", "trackest", "uncert", "aircraft", "airports"].forEach((s) => map.getSource(s).setData(empty())); }
      ["vTime", "vRemaining", "vSpeed", "vAlt", "vPhase", "vSats", "vAcc"].forEach((id) => { $(id).textContent = "--"; }); $("vSrc").textContent = "--"; $("vSrc").className = "badge none"; $("vLog").textContent = "--:--.-- / --:--.--";
      $("title").textContent = `FlightInfo ${APP_VERSION} \u2014 Replay`; applyLang(); };
    $("seek").addEventListener("input", (e) => { if (!S.rows.length) return; S.idx = Math.round(e.target.value / 1000 * (S.rows.length - 1)); S.playT = S.rows[S.idx].t; render(true); });
    chipGroup("speed", (v) => { S.speed = Number(v); render(false); saveSettings(); });
    chipGroup("du", (v) => { S.du = v; updateScaleBar(); render(false); saveSettings(); }); chipGroup("au", (v) => { S.au = v; render(false); saveSettings(); }); chipGroup("su", (v) => { S.su = v; render(false); saveSettings(); });
    chipGroup("theme", (v) => { S.theme = v; applyTheme(); saveSettings(); }); chipGroup("aerial", (v) => { S.aerial = v === "1"; buildMap(); saveSettings(); }); chipGroup("lang", (v) => { S.lang = v; applyLang(); saveSettings(); });
    $("btnZoomIn").onclick = () => map.zoomIn(); $("btnZoomOut").onclick = () => map.zoomOut();
    $("btnFit").onclick = fitRoute; $("btnCenter").onclick = () => { S.lastGesture = 0; S.follow = true; render(true); };
    $("btnOrient").onclick = () => { S.trackUp = !S.trackUp; render(true); saveSettings(); };
    $("btnSettings").onclick = () => $("settings").classList.remove("hidden"); $("sClose").onclick = () => $("settings").classList.add("hidden");
    $("btnHelp").onclick = () => $("help").classList.remove("hidden"); $("hClose").onclick = () => $("help").classList.add("hidden");
    $("btnOpen").onclick = () => $("file").click();
    $("file").addEventListener("change", (e) => { const f = e.target.files[0]; if (f) f.text().then((t) => loadText(t, f.name)); e.target.value = ""; });
    const ph = $("phone");
    ph.addEventListener("dragover", (e) => { e.preventDefault(); ph.classList.add("dragover"); });
    ph.addEventListener("dragleave", () => ph.classList.remove("dragover"));
    ph.addEventListener("drop", (e) => { e.preventDefault(); ph.classList.remove("dragover"); const f = e.dataTransfer.files[0]; if (f) f.text().then((t) => loadText(t, f.name)); });
    document.addEventListener("keydown", (e) => { if (e.code === "Space" && S.rows.length) { e.preventDefault(); S.playing ? pause() : play(); } });
  }
  init();
})();
