#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - build_desktop.py
# Version 1.2
# Purpose : Assemble the single-file desktop replay app (docs/replay/index.html)
#           from desktop/src/*, embedding MapLibre GL JS (BSD-3), the app's
#           Natural Earth GeoJSON layers and the airports table, so the file
#           runs by double-click in any browser without a server.
# Runs on : Python 3.10+, standard library only. MapLibre dist files are taken
#           from a local directory (default: node_modules/maplibre-gl/dist or
#           a path given as argument 1).
# Encoding: UTF-8 without BOM
# =============================================================
import base64
import csv
import json
import os
import sys

# =============================================================
# Parameters
# =============================================================
ROOT            = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPLIBRE_DIR    = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "node_modules", "maplibre-gl", "dist")
SRC_DIR         = os.path.join(ROOT, "desktop", "src")
ASSETS          = os.path.join(ROOT, "app", "src", "main", "assets")
OUT             = os.path.join(ROOT, "docs", "replay", "index.html")
PLACES_MAX_RANK = 6          # scalerank kept for the desktop map (phone keeps 8)

# =============================================================
# Validation
# =============================================================
for f in ("maplibre-gl.js", "maplibre-gl.css"):
    assert os.path.isfile(os.path.join(MAPLIBRE_DIR, f)), f"missing {f} in {MAPLIBRE_DIR}"
for f in ("index.html", "app.js", "style.css"):
    assert os.path.isfile(os.path.join(SRC_DIR, f)), f"missing desktop/src/{f}"


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def geojson(name, prop_filter=None, keep=None):
    data = json.load(open(os.path.join(ASSETS, name), encoding="utf-8"))
    feats = []
    for ft in data["features"]:
        if prop_filter and not prop_filter(ft["properties"]):
            continue
        if keep:
            ft = {"type": "Feature", "geometry": ft["geometry"], "properties": {k: ft["properties"].get(k) for k in keep if k in ft["properties"]}}
        feats.append(ft)
    return {"type": "FeatureCollection", "features": feats}


def glyphs():
    # Bundled Noto Sans PBF ranges (OFL) -> base64, served to MapLibre through a custom protocol so labels work offline.
    out = {}
    root = os.path.join(ASSETS, "glyphs")
    for stack in os.listdir(root):
        for f in os.listdir(os.path.join(root, stack)):
            if f.endswith(".pbf"):
                with open(os.path.join(root, stack, f), "rb") as fh:
                    out[f"{stack}/{f[:-4]}"] = base64.b64encode(fh.read()).decode("ascii")
    return out


HELP_ORDER = ["what", "setup", "map", "aerial", "lines", "modes", "confidence", "deviation", "estimate", "gnss", "values", "terms", "logs", "limits"]


def app_help():
    """The app's help sections in both languages, decoded from the string resources."""
    import html as html_mod
    import re
    out = {}
    for lang, path in (("en", "values/strings.xml"), ("he", "values-iw/strings.xml")):
        s = open(os.path.join(ROOT, "app", "src", "main", "res", path), encoding="utf-8").read()
        def get(name):
            m = re.search(r'<string name="%s">(.*?)</string>' % name, s, re.S)
            if not m:
                return ""
            t = re.sub(r"\\u([0-9A-Fa-f]{4})", lambda mm: chr(int(mm.group(1), 16)), m.group(1))
            t = t.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"')
            return html_mod.unescape(t)
        out[lang] = [[get("help_%s_title" % k), get("help_%s_body" % k)] for k in HELP_ORDER if get("help_%s_title" % k)]
    return out


def airports():
    out = {}
    with open(os.path.join(ASSETS, "airports.csv"), encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            out[row["iata"]] = {"lat": float(row["lat"]), "lon": float(row["lon"]), "elev": int(row["elev_m"]), "name": row["name"], "tz": row["tz"]}
    return out


def main():
    data = {
        "land": geojson("ne_land.geojson"),
        "lakes": geojson("ne_lakes.geojson"),
        "borders": geojson("ne_borders.geojson"),
        "countries": geojson("ne_countries.geojson", keep=("color",)),
        "countryLabels": geojson("ne_country_labels.geojson", keep=("name", "labelrank")),
        "places": geojson("ne_places.geojson", prop_filter=lambda p: p.get("scalerank", 99) <= PLACES_MAX_RANK, keep=("name", "scalerank")),
        "airports": airports(),
        "glyphs": glyphs(),
        "help": app_help(),
    }
    html = read(os.path.join(SRC_DIR, "index.html"))
    html = html.replace("/*__MAPLIBRE_CSS__*/", read(os.path.join(MAPLIBRE_DIR, "maplibre-gl.css")))
    html = html.replace("/*__APP_CSS__*/", read(os.path.join(SRC_DIR, "style.css")))
    # "</script>" inside embedded scripts would terminate the tag: escape defensively.
    html = html.replace("/*__MAPLIBRE_JS__*/", read(os.path.join(MAPLIBRE_DIR, "maplibre-gl.js")).replace("</script>", "<\\/script>"))
    html = html.replace("/*__DATA__*/", json.dumps(data, separators=(",", ":"), ensure_ascii=False).replace("</script>", "<\\/script>"))
    html = html.replace("/*__APP_JS__*/", read(os.path.join(SRC_DIR, "app.js")).replace("</script>", "<\\/script>"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(html)
    print(f"{OUT}: {os.path.getsize(OUT) // 1024} KB; places={len(data['places']['features'])} airports={len(data['airports'])}")


if __name__ == "__main__":
    main()
