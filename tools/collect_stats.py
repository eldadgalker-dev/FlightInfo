#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - collect_stats.py
# Version 1.0
# Purpose : Append today's cumulative download counts of all release assets
#           (GitHub Releases API) to a CSV, so the history of downloads per
#           version is kept in the repository. Aggregate counts only: GitHub
#           does not expose who downloaded a file, to anyone.
# Runs on : Python 3.10+, standard library; needs GH_TOKEN for rate limits
# Usage   : collect_stats.py owner/repo docs/stats/downloads.csv
# =============================================================
import csv
import datetime as dt
import json
import os
import sys
import urllib.request

REPO   = sys.argv[1]
OUT    = sys.argv[2]
API    = f"https://api.github.com/repos/{REPO}/releases?per_page=100"
HEADER = ["date_utc", "tag", "published_utc", "asset", "downloads_cumulative"]

req = urllib.request.Request(API, headers={"Accept": "application/vnd.github+json", "User-Agent": "FlightInfo-stats"})
tok = os.environ.get("GH_TOKEN")
if tok:
    req.add_header("Authorization", f"Bearer {tok}")
with urllib.request.urlopen(req, timeout=60) as r:
    releases = json.load(r)

today = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
rows = []
for rel in releases:
    for a in rel.get("assets", []):
        rows.append([today, rel.get("tag_name", ""), rel.get("published_at", ""), a.get("name", ""), a.get("download_count", 0)])

os.makedirs(os.path.dirname(OUT), exist_ok=True)
new = not os.path.exists(OUT)
with open(OUT, "a", encoding="utf-8", newline="\n") as fh:
    w = csv.writer(fh, lineterminator="\n")
    if new:
        w.writerow(HEADER)
    w.writerows(rows)
print(f"{len(rows)} asset rows appended for {today}")
