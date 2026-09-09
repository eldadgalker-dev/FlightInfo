#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - build_bluemarble.py
# Version 1.0
# Purpose : Convert a NASA Blue Marble equirectangular image (public
#           domain) into a Web-Mercator MBTiles raster pack, zoom 0..MAX_ZOOM,
#           for the optional aerial layer. Runs in GitHub Actions (see
#           .github/workflows/bluemarble.yml); the result is attached to a
#           GitHub Release so the app can download it without any server.
# Runs on : Python 3.10+, packages: pillow, numpy
# Input   : equirectangular RGB image, lon -180..180 left to right,
#           lat 90..-90 top to bottom (Blue Marble Next Generation 21600x10800
#           gives ~1.85 km/px, enough for zoom 6; 43200x21600 for zoom 7)
# Encoding: UTF-8 without BOM, LF line endings
# =============================================================

import math
import os
import sqlite3
import sys
from io import BytesIO

import numpy as np
from PIL import Image

# =============================================================
# Parameters
# =============================================================
SRC_IMAGE     = sys.argv[1] if len(sys.argv) > 1 else "bluemarble.png"
OUT_MBTILES   = sys.argv[2] if len(sys.argv) > 2 else "bluemarble_z0-6.mbtiles"
MAX_ZOOM      = int(sys.argv[3]) if len(sys.argv) > 3 else 6
TILE_SIZE     = 256          # px
JPEG_QUALITY  = 78           # 0..100
MAX_LAT       = 85.05112878  # deg, Web Mercator limit
NAME          = "NASA Blue Marble Next Generation"
ATTRIBUTION   = "NASA Earth Observatory (public domain)"

# =============================================================
# Validation
# =============================================================
assert os.path.isfile(SRC_IMAGE), f"source image not found: {SRC_IMAGE}"
assert 0 <= MAX_ZOOM <= 8, "MAX_ZOOM out of range (0..8)"
Image.MAX_IMAGE_PIXELS = None


# =============================================================
# Helpers
# =============================================================

def tile_lat_rows(z, y, height):
    """Source image rows (equirectangular) for each of the TILE_SIZE rows of tile (z, y)."""
    n = 2 ** z
    out = np.empty(TILE_SIZE, dtype=np.int64)
    for r in range(TILE_SIZE):
        # Mercator y in [0, 1) for this pixel row centre
        my = (y + (r + 0.5) / TILE_SIZE) / n
        lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * my))))
        lat = max(-MAX_LAT, min(MAX_LAT, lat))
        row = int((90.0 - lat) / 180.0 * height)
        out[r] = min(height - 1, max(0, row))
    return out


def tile_lon_cols(z, x, width):
    """Source image columns for each of the TILE_SIZE columns of tile (z, x)."""
    n = 2 ** z
    cols = (x * TILE_SIZE + np.arange(TILE_SIZE) + 0.5) / (n * TILE_SIZE) * width
    return np.clip(cols.astype(np.int64), 0, width - 1)


def main():
    print(f"loading {SRC_IMAGE} ...")
    img = np.asarray(Image.open(SRC_IMAGE).convert("RGB"))
    height, width = img.shape[0], img.shape[1]
    print(f"source {width}x{height}, building zoom 0..{MAX_ZOOM}")

    if os.path.exists(OUT_MBTILES):
        os.remove(OUT_MBTILES)
    db = sqlite3.connect(OUT_MBTILES)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    meta = {
        "name": NAME, "format": "jpg", "type": "baselayer", "version": "1.0",
        "minzoom": "0", "maxzoom": str(MAX_ZOOM),
        "bounds": f"-180,{-MAX_LAT},180,{MAX_LAT}", "attribution": ATTRIBUTION,
        "description": "Blue Marble Next Generation, equirectangular source reprojected to Web Mercator",
    }
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())

    total = 0
    for z in range(MAX_ZOOM + 1):
        n = 2 ** z
        row_cache = {y: tile_lat_rows(z, y, height) for y in range(n)}
        batch = []
        for x in range(n):
            cols = tile_lon_cols(z, x, width)
            for y in range(n):
                rows = row_cache[y]
                tile = img[np.ix_(rows, cols)]
                buf = BytesIO()
                Image.fromarray(tile).save(buf, format="JPEG", quality=JPEG_QUALITY, optimize=True)
                # MBTiles uses TMS row numbering (origin bottom-left).
                batch.append((z, x, (n - 1 - y), buf.getvalue()))
            if len(batch) >= 512:
                db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", batch)
                total += len(batch)
                batch = []
        if batch:
            db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", batch)
            total += len(batch)
        db.commit()
        print(f"zoom {z}: {n * n} tiles")
    db.close()
    print(f"{OUT_MBTILES}: {total} tiles, {os.path.getsize(OUT_MBTILES) // (1024 * 1024)} MB")


if __name__ == "__main__":
    main()
