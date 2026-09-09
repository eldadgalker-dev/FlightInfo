#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - build_bluemarble_gibs.py
# Version 1.0
# Purpose : Build the aerial MBTiles pack by fetching ready-made Web Mercator
#           tiles of NASA Blue Marble (public domain) from NASA GIBS, the
#           agency's WMTS tile service, instead of reprojecting a source
#           image. Zoom 0..MAX_ZOOM, JPEG, TMS row order in the MBTiles.
# Runs on : Python 3.10+, standard library only
# Encoding: UTF-8 without BOM, LF line endings
# =============================================================

import os
import sqlite3
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

# =============================================================
# Parameters
# =============================================================
OUT_MBTILES   = sys.argv[1] if len(sys.argv) > 1 else "bluemarble_z0-6.mbtiles"
MAX_ZOOM      = int(sys.argv[2]) if len(sys.argv) > 2 else 6
LAYER         = sys.argv[3] if len(sys.argv) > 3 else "BlueMarble_ShadedRelief_Bathymetry"
BASE_URL      = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best"
MATRIX_SET    = "GoogleMapsCompatible_Level8"
EXTENSIONS    = ("jpg", "jpeg", "png")     # probed on the first tile, first success wins
WORKERS       = 8                          # parallel connections (GIBS tolerates this politely)
RETRIES       = 4
TIMEOUT_S     = 30
USER_AGENT    = "FlightInfo-pack-builder (github actions)"
MAX_LAT       = 85.05112878

# =============================================================
# Validation
# =============================================================
assert 0 <= MAX_ZOOM <= 8, "MAX_ZOOM out of range (0..8)"
assert 1 <= WORKERS <= 16


def tile_url(z, y, x, ext):
    return f"{BASE_URL}/{LAYER}/default/{MATRIX_SET}/{z}/{y}/{x}.{ext}"


def fetch(url):
    last = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=TIMEOUT_S) as r:
                data = r.read()
                if len(data) < 100:
                    raise IOError(f"suspiciously small response ({len(data)} bytes)")
                return data
        except Exception as e:  # noqa: BLE001 - retry any transport error
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise IOError(f"{url}: {last}")


def probe_extension():
    for ext in EXTENSIONS:
        try:
            fetch(tile_url(0, 0, 0, ext))
            return ext
        except Exception as e:  # noqa: BLE001
            print(f"probe .{ext} failed: {e}")
    raise SystemExit("GIBS unreachable or layer/matrix set name wrong; see README")


def main():
    ext = probe_extension()
    fmt = "jpg" if ext in ("jpg", "jpeg") else "png"
    print(f"layer {LAYER}, extension .{ext}, zoom 0..{MAX_ZOOM}")

    if os.path.exists(OUT_MBTILES):
        os.remove(OUT_MBTILES)
    db = sqlite3.connect(OUT_MBTILES)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    db.executemany("INSERT INTO metadata VALUES (?, ?)", {
        "name": "NASA Blue Marble (GIBS)", "format": fmt, "type": "baselayer", "version": "1.0",
        "minzoom": "0", "maxzoom": str(MAX_ZOOM),
        "bounds": f"-180,{-MAX_LAT},180,{MAX_LAT}",
        "attribution": "NASA Earth Observatory / GIBS (public domain)",
        "description": f"GIBS layer {LAYER}, {MATRIX_SET}",
    }.items())

    total = 0
    for z in range(MAX_ZOOM + 1):
        n = 2 ** z
        jobs = [(z, y, x) for y in range(n) for x in range(n)]
        rows = []
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = {pool.submit(fetch, tile_url(z, y, x, ext)): (z, y, x) for (z, y, x) in jobs}
            for fut in as_completed(futures):
                z_, y_, x_ = futures[fut]
                data = fut.result()                       # raises -> job fails loudly
                rows.append((z_, x_, (n - 1 - y_), data))  # TMS row numbering
        db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", rows)
        db.commit()
        total += len(rows)
        print(f"zoom {z}: {len(rows)} tiles")
    db.close()
    print(f"{OUT_MBTILES}: {total} tiles, {os.path.getsize(OUT_MBTILES) // (1024 * 1024)} MB")


if __name__ == "__main__":
    main()
