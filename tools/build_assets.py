#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# SkyTrack - bundled asset builder
# Version 1.1
# Purpose : Generate the offline data files shipped inside the APK
#           (airports table with timezones, slimmed Natural Earth
#           GeoJSON layers) from their upstream open-data sources.
# Runs on : Python 3.10+, requires package "timezonefinder"
# Encoding: UTF-8 without BOM, LF line endings
# =============================================================

# =============================================================
# Conventions
#   Units      : degrees (WGS84 lat/lon), feet for airport elevation
#                as delivered upstream (converted to metres on output)
#   Coordinates: GeoJSON order [lon, lat]
# =============================================================

import csv
import json
import os
import sys

# =============================================================
# Parameters
# =============================================================

# -- Paths --
SRC_DIR            = sys.argv[1] if len(sys.argv) > 1 else "data"
OUT_DIR            = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets"

# -- Airport filter --
AIRPORT_TYPES      = {"large_airport", "medium_airport"}   # OurAirports type codes
REQUIRE_SCHEDULED  = True   # keep only airports with scheduled service
REQUIRE_IATA       = True   # keep only airports with an IATA code

# -- Populated places filter --
MAX_SCALERANK      = 6      # Natural Earth scalerank <= this value is kept (0 = largest)
PLACE_PROPS        = ("name", "scalerank", "adm0cap")   # properties kept per place

# -- Geometry precision --
COORD_DECIMALS     = 3      # ~110 m at the equator; sufficient for zoom <= 8

# -- Precision --
FT_TO_M            = 0.3048

# =============================================================
# Validation
# =============================================================
assert os.path.isdir(SRC_DIR), f"source dir not found: {SRC_DIR}"
assert 0 <= MAX_SCALERANK <= 10, "MAX_SCALERANK out of range"
assert 0 <= COORD_DECIMALS <= 6, "COORD_DECIMALS out of range"
os.makedirs(OUT_DIR, exist_ok=True)


# =============================================================
# Helpers
# =============================================================

def round_coords(coords):
    """Recursively round a GeoJSON coordinate array to COORD_DECIMALS."""
    if isinstance(coords[0], (int, float)):
        return [round(coords[0], COORD_DECIMALS), round(coords[1], COORD_DECIMALS)]
    return [round_coords(c) for c in coords]


def slim_geojson(src_name, dst_name, keep_props=(), feature_filter=None):
    """Copy a GeoJSON file keeping only selected properties and rounded coordinates."""
    with open(os.path.join(SRC_DIR, src_name), encoding="utf-8") as f:
        data = json.load(f)
    out_features = []
    for feat in data["features"]:
        if feature_filter and not feature_filter(feat):
            continue
        props = {k: feat["properties"].get(k) for k in keep_props}
        geom = feat["geometry"]
        if geom is None:
            continue
        geom = {"type": geom["type"], "coordinates": round_coords(geom["coordinates"])}
        out_features.append({"type": "Feature", "properties": props, "geometry": geom})
    with open(os.path.join(OUT_DIR, dst_name), "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": out_features}, f,
                  separators=(",", ":"), ensure_ascii=False)
    print(f"{dst_name}: {len(out_features)} features, "
          f"{os.path.getsize(os.path.join(OUT_DIR, dst_name)) // 1024} KB")


def build_airports():
    """Write airports.csv with columns: iata,icao,name,city,country,lat,lon,elev_m,tz."""
    from timezonefinder import TimezoneFinder
    tf = TimezoneFinder()
    rows_out = []
    with open(os.path.join(SRC_DIR, "airports.csv"), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["type"] not in AIRPORT_TYPES:
                continue
            if REQUIRE_SCHEDULED and row["scheduled_service"] != "yes":
                continue
            iata = row["iata_code"].strip()
            if REQUIRE_IATA and not iata:
                continue
            lat = float(row["latitude_deg"])
            lon = float(row["longitude_deg"])
            elev_ft = row["elevation_ft"].strip()
            elev_m = round(float(elev_ft) * FT_TO_M) if elev_ft else 0
            tz = tf.timezone_at(lat=lat, lng=lon) or "UTC"
            rows_out.append([
                iata,
                row["icao_code"].strip() or row["ident"].strip(),
                row["name"].strip(),
                row["municipality"].strip(),
                row["iso_country"].strip(),
                f"{lat:.5f}",
                f"{lon:.5f}",
                str(elev_m),
                tz,
            ])
    rows_out.sort(key=lambda r: r[0])
    path = os.path.join(OUT_DIR, "airports.csv")
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["iata", "icao", "name", "city", "country", "lat", "lon", "elev_m", "tz"])
        w.writerows(rows_out)
    print(f"airports.csv: {len(rows_out)} airports, {os.path.getsize(path) // 1024} KB")


# =============================================================
# Main
# =============================================================

def main():
    build_airports()
    slim_geojson("ne_50m_land.geojson", "ne_land.geojson")
    slim_geojson("ne_50m_lakes.geojson", "ne_lakes.geojson")
    slim_geojson("ne_50m_admin_0_boundary_lines_land.geojson", "ne_borders.geojson")
    slim_geojson("ne_50m_populated_places_simple.geojson", "ne_places.geojson",
                 keep_props=PLACE_PROPS,
                 feature_filter=lambda ft: ft["properties"].get("scalerank", 99) <= MAX_SCALERANK)


if __name__ == "__main__":
    main()
