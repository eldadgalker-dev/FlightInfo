#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - clean_log.py
# Version 1.2
# Purpose : Turn one or more raw FlightInfo CSV logs of the same flight into a
#           single continuous 1 Hz file for replay:
#             - keep only the segments that belong to the flight (from the last
#               stationary period at the origin to the landing at the destination)
#             - blank stale GNSS columns (a fix repeated long after it was taken)
#             - fill every hole (recording gap or GNSS outage) by interpolating
#               between the real fixes before and after it on the great circle
#             - est_lat/lon = measured position when fresh, interpolated otherwise
#             - recompute remaining, total_flown, sigma, speed, ete; relabel phases
#               from GNSS altitude and speed; resample to exactly one row per second
#           Every edit is written as a comment in the output header.
# Runs on : Python 3.10+, standard library only
# Usage   : clean_log.py OUT.csv IN1.csv [IN2.csv ...]  [--origin MUC --dest TLV --flight LY254]
#           Airport coordinates come from app/src/main/assets/airports.csv.
# Encoding: UTF-8 without BOM
# =============================================================
import argparse
import csv
import datetime as dt
import math
import os

# =============================================================
# Parameters
# =============================================================
ROOT             = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AIRPORTS_CSV     = os.path.join(ROOT, "app", "src", "main", "assets", "airports.csv")
STALE_FIX_S      = 30        # a fix older than this relative to the row is treated as absent
GAP_INTERP_S     = 10        # holes longer than this are interpolated between real fixes
MAX_PLAUSIBLE_V  = 350.0     # m/s, implied speed above this means the two fixes are not the same flight
GROUND_SPEED_MPS = 40.0      # below this near the field elevation = on the ground
FIELD_MARGIN_M   = 300.0     # altitude above field elevation counted as on the ground
CLIMB_RATE_MPS   = 2.0       # sustained vertical rate for CLIMB / DESCENT labelling (60 s window)
DRIFT_RATE       = 0.06      # sigma growth per metre flown without a fix
DESCENT_DIST_M   = 170_000.0
DESCENT_ALLOW_S  = 1_500.0
APPROACH_S       = 300.0
TOUCHDOWN_MPS    = 70.0
R                = 6371008.8

# =============================================================
# Geodesy
# =============================================================
def hav(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0]); dl = math.radians(b[1] - a[1])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(min(1.0, h)))


def bearing(a, b):
    la1, la2 = math.radians(a[0]), math.radians(b[0]); dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(la2); x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def gc(a, b, f):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1])); d = hav(a, b) / R
    if d < 1e-9:
        return a
    A = math.sin((1 - f) * d) / math.sin(d); B = math.sin(f * d) / math.sin(d)
    x = A * math.cos(la1) * math.cos(lo1) + B * math.cos(la2) * math.cos(lo2)
    y = A * math.cos(la1) * math.sin(lo1) + B * math.cos(la2) * math.sin(lo2)
    z = A * math.sin(la1) + B * math.sin(la2)
    return (math.degrees(math.atan2(z, math.sqrt(x * x + y * y))), math.degrees(math.atan2(y, x)))


def iso(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# =============================================================
# Reading
# =============================================================
def read_logs(paths):
    header, rows, meta = None, [], {}
    for p in paths:
        h = None
        with open(p, encoding="utf-8") as fh:
            for ln in fh:
                ln = ln.rstrip("\n")
                if not ln:
                    continue
                if ln.startswith("#"):
                    for kv in ln.split():
                        if "=" in kv:
                            k, v = kv.split("=", 1); meta.setdefault(k, v)
                    continue
                if ln.startswith("time_utc"):
                    h = ln.split(","); header = header or h; continue
                if h is None:
                    continue
                c = ln.split(",")
                if len(c) < len(h):
                    continue
                r = dict(zip(h, c))
                if not r.get("epoch_ms", "").isdigit():
                    continue
                rows.append(r)
        base = os.path.basename(p).split("_")
        if len(base) >= 4:
            meta.setdefault("origin", base[2]); meta.setdefault("destination", base[3]); meta.setdefault("flight", base[4].split(".")[0] if len(base) > 4 else "")
    rows.sort(key=lambda r: int(r["epoch_ms"]))
    # one row per second: keep the last row of each second
    by_sec = {}
    for r in rows:
        by_sec[int(r["epoch_ms"]) // 1000] = r
    rows = [by_sec[k] for k in sorted(by_sec)]
    return header, rows, meta


def load_airport(iata):
    with open(AIRPORTS_CSV, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["iata"] == iata:
                return (float(r["lat"]), float(r["lon"])), int(r["elev_m"])
    raise SystemExit(f"unknown airport {iata}")


# =============================================================
# Cleaning
# =============================================================
def fixes_of(rows):
    """Distinct, non-stale fixes: (row_ms, lat, lon, alt, speed, hacc, sats, quality)."""
    out, last = [], None
    for r in rows:
        gt = r.get("gnss_time_ms", "")
        if not gt or not r.get("gnss_lat"):
            continue
        t = int(r["epoch_ms"])
        if t - int(gt) > STALE_FIX_S * 1000 or gt == last:
            continue
        last = gt
        out.append(dict(t=int(gt), row_t=t, lat=float(r["gnss_lat"]), lon=float(r["gnss_lon"]),
                        alt=float(r["gnss_alt_m"]) if r.get("gnss_alt_m") else None,
                        v=float(r["gnss_speed_mps"]) if r.get("gnss_speed_mps") else None,
                        hacc=float(r["gnss_hacc_m"]) if r.get("gnss_hacc_m") else None,
                        sats=r.get("gnss_sats_used", ""), vis=r.get("gnss_sats_visible", ""), q=r.get("gnss_quality", ""),
                        brg=float(r["gnss_bearing_deg"]) if r.get("gnss_bearing_deg") else None))
    return out


def select_flight(fixes, origin, dest, o_elev, d_elev):
    """Indexes of the flight: from the last stationary fix at the origin before the first airborne fix,
    to the first stationary fix at the destination after the last airborne fix."""
    def on_ground_at(fx, ap, elev):
        return hav((fx["lat"], fx["lon"]), ap) < 20_000 and (fx["v"] or 0) < GROUND_SPEED_MPS and (fx["alt"] is None or fx["alt"] < elev + FIELD_MARGIN_M)
    air = [i for i, f in enumerate(fixes) if (f["v"] or 0) > GROUND_SPEED_MPS * 2 and f["alt"] is not None and f["alt"] > max(o_elev, d_elev) + 1000]
    if not air:
        raise SystemExit("no airborne fixes found")
    first_air, last_air = air[0], air[-1]
    start = 0
    for i in range(first_air - 1, -1, -1):
        if on_ground_at(fixes[i], origin, o_elev):
            start = i
            # extend back through the contiguous ground period (taxi), stop at a gap > 20 min
            while start > 0 and on_ground_at(fixes[start - 1], origin, o_elev) and fixes[start]["t"] - fixes[start - 1]["t"] < 20 * 60_000:
                start -= 1
            break
    end = len(fixes) - 1
    for i in range(last_air + 1, len(fixes)):
        if on_ground_at(fixes[i], dest, d_elev):
            end = i
            # keep up to 5 minutes of taxi after touchdown
            while end + 1 < len(fixes) and on_ground_at(fixes[end + 1], dest, d_elev) and fixes[end + 1]["t"] - fixes[i]["t"] < 5 * 60_000:
                end += 1
            break
    return start, end


def hole_profile(u, span, d, v0, v1):
    """Position fraction and speed factor (speed = factor * d / span) at normalised time u across a hole.
    Speeds at both ends match the fixes: a ground start ramps linearly up to the end speed and then
    holds it; a ground end holds the start speed and ramps down; two airborne ends give constant speed;
    two ground ends (taxi) give a smooth stop-to-stop profile. Falls back to constant speed when the
    ramp cannot fit the distance."""
    v_avg = d / span
    ground0, ground1 = v0 < GROUND_SPEED_MPS, v1 < GROUND_SPEED_MPS
    if ground0 and ground1:
        return 3 * u * u - 2 * u ** 3, 6 * u - 6 * u * u
    if ground0 and v1 > 0:
        ta = 2 * (v1 * span - d) / v1                       # ramp length that makes the distance come out right
        if 0 < ta <= span:
            tu = ta / span
            if u < tu:
                frac = (v1 * span / d) * (u * u) / (2 * tu)
                return frac, (v1 / v_avg) * (u / tu)
            frac = (v1 * span / d) * (tu / 2 + (u - tu))
            return frac, v1 / v_avg
    if ground1 and v0 > 0:
        td = 2 * (v0 * span - d) / v0
        if 0 < td <= span:
            tu = 1 - td / span
            if u < tu:
                return (v0 * span / d) * u, v0 / v_avg
            w = (u - tu) / (1 - tu)
            frac = (v0 * span / d) * (tu + (1 - tu) * (w - w * w / 2))
            return frac, (v0 / v_avg) * (1 - w)
    return u, 1.0


def build(fixes, origin, dest, o_elev, d_elev, header):
    """One row per second from the first to the last selected fix, interpolated across holes."""
    t0, t1 = fixes[0]["t"], fixes[-1]["t"]
    total = (t1 - t0) // 1000
    rows = []
    fi = 0
    n_interp = 0
    flown = 0.0
    prev_pos = (fixes[0]["lat"], fixes[0]["lon"])
    notes = []
    for k in range(total + 1):
        t = t0 + k * 1000
        while fi + 1 < len(fixes) and fixes[fi + 1]["t"] <= t:
            fi += 1
        f0 = fixes[fi]
        f1 = fixes[fi + 1] if fi + 1 < len(fixes) else None
        age = (t - f0["t"]) / 1000
        if f1 is None or f1["t"] == f0["t"]:
            lat, lon, alt, v, brg = f0["lat"], f0["lon"], f0["alt"], f0["v"], f0["brg"]
            sigma = f0["hacc"] or 50.0; mode = "GNSS_TRACKING"; gnss = f0
        else:
            # Always move between consecutive fixes on the great circle: no frozen seconds, no jumps.
            span = (f1["t"] - f0["t"]) / 1000
            u = min(1.0, age / span)
            d = hav((f0["lat"], f0["lon"]), (f1["lat"], f1["lon"]))
            v_imp = d / span
            if v_imp > MAX_PLAUSIBLE_V:
                raise SystemExit(f"implausible speed {v_imp:.0f} m/s between {iso(f0['t'])} and {iso(f1['t'])}")
            long_hole = span > GAP_INTERP_S
            frac, dfrac = hole_profile(u, span, d, f0["v"] or 0.0, f1["v"] or 0.0) if long_hole and span > 60 else (u, 1.0)
            lat, lon = gc((f0["lat"], f0["lon"]), (f1["lat"], f1["lon"]), frac)
            a0 = f0["alt"] if f0["alt"] is not None else (f1["alt"] or 0.0); a1 = f1["alt"] if f1["alt"] is not None else a0
            alt = a0 + (a1 - a0) * frac
            if long_hole:
                v = v_imp * dfrac
                brg = bearing((lat, lon), (f1["lat"], f1["lon"]))
                sigma = (f0["hacc"] or 50.0) + DRIFT_RATE * d * frac
                mode = "ROUTE_CONSTRAINED"; gnss = f0 if age < STALE_FIX_S else None
                if k == 0 or rows[-1]["mode"] != "ROUTE_CONSTRAINED":
                    notes.append((iso(f0["t"]), iso(f1["t"]), int(span), int(d / 1000), int(v_imp)))
                n_interp += 1
            else:
                v = f0["v"] if f0["v"] is not None else v_imp
                brg = f0["brg"] if f0["brg"] is not None else bearing((lat, lon), (f1["lat"], f1["lon"]))
                sigma = f0["hacc"] or 50.0
                mode = "GNSS_TRACKING"; gnss = f0
        pos = (lat, lon)
        step = hav(prev_pos, pos)
        if step < 50_000:
            flown += step
        prev_pos = pos
        rows.append(dict(t=t, lat=lat, lon=lon, alt=alt if alt is not None else 0.0, v=v if v is not None else 0.0, brg=brg if brg is not None else bearing(pos, dest),
                         sigma=sigma, mode=mode, gnss=gnss, flown=flown, remaining=hav(pos, dest)))
    return rows, n_interp, notes


def label_phases(rows, o_elev, d_elev):
    """GROUND / TAKEOFF / CLIMB / CRUISE / DESCENT / LANDED from altitude trend and speed."""
    n = len(rows)
    alts = [r["alt"] for r in rows]
    def vrate(i):
        j = max(0, i - 60)
        return (alts[i] - alts[j]) / max(1, i - j)
    phase = "GROUND"; took_off = False
    top = max(alts)
    for i, r in enumerate(rows):
        v = r["v"]
        if phase == "GROUND":
            if v > GROUND_SPEED_MPS * 1.5 or r["alt"] > o_elev + FIELD_MARGIN_M:
                phase = "TAKEOFF"; took_off = True
        elif phase == "TAKEOFF":
            phase = "CLIMB"
        elif phase == "CLIMB":
            if vrate(i) < 0.5 and r["alt"] > top * 0.75:
                phase = "CRUISE"
        elif phase == "CRUISE":
            if vrate(i) < -CLIMB_RATE_MPS and r["remaining"] < 400_000:
                phase = "DESCENT"
        elif phase == "DESCENT":
            if v < GROUND_SPEED_MPS and r["alt"] < d_elev + FIELD_MARGIN_M:
                phase = "LANDED"
        r["phase"] = phase
    return took_off


def ete(r):
    rem, v = r["remaining"], r["v"]
    if r["phase"] == "LANDED" or rem < 1000:
        return 0
    if r["phase"] == "DESCENT":
        return int(rem / max(60.0, (max(v, 140.0) + TOUCHDOWN_MPS) / 2) + APPROACH_S)
    if r["phase"] == "GROUND":
        return int(rem / 235.0 + DESCENT_ALLOW_S)
    return int(max(0.0, rem - DESCENT_DIST_M) / max(v, 100.0) + DESCENT_ALLOW_S)


def write(out_path, header, rows, meta, notes, n_interp, raw_rows, src_names):
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# FlightInfo flight log (cleaned by tools/clean_log.py 1.0 on %s)\n" % dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d"))
        fh.write("# app_version=%s(recorded) device=%s\n" % (meta.get("app_version", "unknown"), meta.get("device", "unknown")))
        fh.write("# flight=%s origin=%s destination=%s started_utc=%s\n" % (meta.get("flight", ""), meta["origin"], meta["destination"], iso(rows[0]["t"])))
        fh.write("# source files: %s (%d raw rows); kept %s..%s; resampled to 1 Hz (%d rows)\n" % (" ".join(src_names), raw_rows, iso(rows[0]["t"])[11:19], iso(rows[-1]["t"])[11:19], len(rows)))
        fh.write("# est_lat/est_lon = measured GNSS position when a fix is fresh, else interpolated on the great circle between the real fixes before and after the hole\n")
        for a, b, span, dkm, v in notes:
            fh.write("# interpolated %s .. %s (%d s, %d km, %d m/s); gnss columns empty there\n" % (a[11:19], b[11:19], span, dkm, v))
        fh.write("# %d interpolated rows in total; phases relabelled from altitude/speed; remaining/total_flown/sigma/ete recomputed\n" % n_interp)
        fh.write(",".join(header) + "\n")
        for r in rows:
            g = r["gnss"]
            c = {k: "" for k in header}
            c.update(time_utc=iso(r["t"]), epoch_ms=str(r["t"]), tracking="LIVE", mode=r["mode"], phase=r["phase"],
                     est_lat="%.6f" % r["lat"], est_lon="%.6f" % r["lon"], along_m="%.0f" % r["flown"], total_flown_m="%.0f" % r["flown"],
                     remaining_m="%.0f" % r["remaining"], sigma_s_m="%.0f" % r["sigma"], speed_mps="%.1f" % (0.0 if r["v"] < 1.5 else r["v"]),
                     track_deg="%.1f" % r["brg"], alt_m="%.0f" % r["alt"], ete_s=str(ete(r)), replans="0", deviation_evidence="0")
            if g is not None:
                c.update(gnss_time_ms=str(g["t"]), gnss_lat="%.6f" % g["lat"], gnss_lon="%.6f" % g["lon"],
                         gnss_alt_m="" if g["alt"] is None else "%.0f" % g["alt"], gnss_speed_mps="" if g["v"] is None else "%.1f" % g["v"],
                         gnss_bearing_deg="" if g["brg"] is None else "%.1f" % g["brg"], gnss_hacc_m="" if g["hacc"] is None else "%.0f" % g["hacc"],
                         gnss_sats_used=g["sats"], gnss_sats_visible=g["vis"], gnss_quality=g["q"])
            fh.write(",".join(c.get(k, "") for k in header) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out"); ap.add_argument("inputs", nargs="+")
    ap.add_argument("--origin"); ap.add_argument("--dest"); ap.add_argument("--flight")
    a = ap.parse_args()
    header, raw, meta = read_logs(a.inputs)
    if a.origin: meta["origin"] = a.origin
    if a.dest: meta["destination"] = a.dest
    if a.flight: meta["flight"] = a.flight
    origin, o_elev = load_airport(meta["origin"]); dest, d_elev = load_airport(meta["destination"])
    fixes = fixes_of(raw)
    s, e = select_flight(fixes, origin, dest, o_elev, d_elev)
    fixes = fixes[s:e + 1]
    rows, n_interp, notes = build(fixes, origin, dest, o_elev, d_elev, header)
    label_phases(rows, o_elev, d_elev)
    write(a.out, header, rows, meta, notes, n_interp, len(raw), [os.path.basename(p) for p in a.inputs])
    print(f"{a.out}: {len(rows)} rows, {len(fixes)} fixes, {n_interp} interpolated; {iso(rows[0]['t'])} .. {iso(rows[-1]['t'])}")
    seq = []
    for r in rows:
        if not seq or seq[-1][1] != r["phase"]:
            seq.append((iso(r["t"])[11:19], r["phase"]))
    print("phases:", seq)


if __name__ == "__main__":
    main()
