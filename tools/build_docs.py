#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - build_docs.py
# Version 2.0
# Purpose : Keep the companion documents in sync with the product:
#           - regenerate docs/HELP.md from the Hebrew help strings
#           - stamp the current versionName into README, BETA.md, TOOLS.md,
#             docs/HELP.md (any "x.y-betaN" or "x.y" version token that
#             follows the marker words used in those files)
#           Runs in CI (build.yml) and commits the result when it changed.
# Runs on : Python 3.10+, standard library only
# =============================================================
import html
import os
import re
import sys

# =============================================================
# Parameters
# =============================================================
ROOT        = sys.argv[1] if len(sys.argv) > 1 else "."
STRINGS_HE  = os.path.join(ROOT, "app/src/main/res/values-iw/strings.xml")
STRINGS_EN  = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
GRADLE      = os.path.join(ROOT, "app/build.gradle.kts")
HELP_OUT    = os.path.join(ROOT, "docs/HELP.md")        # English (default)
HELP_OUT_HE = os.path.join(ROOT, "docs/HELP.he.md")     # Hebrew
VERSION_FILES = ["README.md", "BETA.md", "TOOLS.md", "docs/HELP.md", "docs/HELP.he.md"]
HELP_ORDER  = ["what", "setup", "map", "aerial", "lines", "modes", "confidence", "deviation",
               "estimate", "gnss", "values", "terms", "logs", "limits"]
VERSION_RE  = re.compile(r"\b\d+\.\d+(?:-beta\d+)?\b")
MARKERS     = ("Status:", "\u05d2\u05e8\u05e1\u05d4", "version", "Version", "FlightInfo ")

# =============================================================
# Validation
# =============================================================
assert os.path.isfile(STRINGS_HE), STRINGS_HE
assert os.path.isfile(GRADLE), GRADLE


def version():
    m = re.search(r'versionName = "([^"]+)"', open(GRADLE, encoding="utf-8").read())
    assert m, "versionName not found"
    return m.group(1)


def decode(t):
    t = re.sub(r"\\u([0-9A-Fa-f]{4})", lambda mm: chr(int(mm.group(1), 16)), t)
    t = t.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"')
    return html.unescape(t)


def build_help(ver):
    """docs/HELP.md (English, default) and docs/HELP.he.md (Hebrew) from the string resources, with a language toggle."""
    changed = False
    for path, out, lang in ((STRINGS_EN, HELP_OUT, "en"), (STRINGS_HE, HELP_OUT_HE, "he")):
        s = open(path, encoding="utf-8").read()
        def get(name):
            m = re.search(r'<string name="%s">(.*?)</string>' % name, s, re.S)
            return decode(m.group(1)) if m else ""
        if lang == "en":
            out_lines = ["# FlightInfo \u2014 Full help", "", "**English** \u00b7 [\u05e2\u05d1\u05e8\u05d9\u05ea](HELP.he.md)", "",
                         f"Generated automatically from the app's help screens (version {ver}).", ""]
        else:
            out_lines = ["# FlightInfo \u2014 \u05e2\u05d6\u05e8\u05d4 \u05de\u05dc\u05d0\u05d4", "", '<div dir="rtl">', "",
                         "[English](HELP.md) \u00b7 **\u05e2\u05d1\u05e8\u05d9\u05ea**", "",
                         f"\u05e0\u05d5\u05e6\u05e8 \u05d0\u05d5\u05d8\u05d5\u05de\u05d8\u05d9\u05ea \u05de\u05de\u05e1\u05db\u05d9 \u05d4\u05e2\u05d6\u05e8\u05d4 \u05e9\u05dc \u05d4\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4 (\u05d2\u05e8\u05e1\u05d4 {ver}).", ""]
        for key in HELP_ORDER:
            title, body = get("help_%s_title" % key), get("help_%s_body" % key)
            if title:
                out_lines += ["## " + title, "", body.replace("\n", "  \n"), ""]
        if lang == "he":
            out_lines.append("</div>")
        text = "\n".join(out_lines) + "\n"
        old = open(out, encoding="utf-8").read() if os.path.exists(out) else ""
        if text != old:
            open(out, "w", encoding="utf-8").write(text); changed = True
    return changed


def stamp_versions(ver):
    changed = False
    for rel in VERSION_FILES:
        p = os.path.join(ROOT, rel)
        if not os.path.isfile(p):
            continue
        s = open(p, encoding="utf-8").read()
        def repl(m):
            line = m.group(0)
            if any(k in line for k in MARKERS):
                return VERSION_RE.sub(ver, line, count=1) if re.search(r"beta|Status:|\u05d2\u05e8\u05e1\u05d4", line) else line
            return line
        # Only lines that mention a beta/status version are stamped; numeric values elsewhere are left alone.
        new = "\n".join(repl(re.match(r".*", ln)) if re.search(r"\d+\.\d+-beta\d+", ln) else ln for ln in s.split("\n"))
        if new != s:
            open(p, "w", encoding="utf-8").write(new)
            changed = True
    return changed


def stamp_page(ver):
    """docs/index.html: current version and date in the 'version-line' paragraph."""
    import datetime
    today = datetime.date.today().isoformat()
    changed = False
    for name in ("index.html", "index.he.html"):
        p = os.path.join(ROOT, "docs", name)
        if not os.path.isfile(p):
            continue
        s = open(p, encoding="utf-8").read()
        new = re.sub(r'(<p class="sub" id="version-line">)(.*?)(</p>)',
                     lambda m: m.group(1) + m.group(2).split("<b>")[0] + "<b>" + ver + "</b> \u00b7 " + m.group(2).split("\u00b7")[-1].split(" ")[1] + " " + today + m.group(3)
                     if "<b>" in m.group(2) else m.group(0), s, flags=re.S)
        if new != s:
            open(p, "w", encoding="utf-8").write(new); changed = True
    return changed


def main():
    ver = version()
    stamp_page(ver)
    a = build_help(ver)
    b = stamp_versions(ver)
    print(f"version {ver}: HELP.md {'updated' if a else 'unchanged'}, version stamps {'updated' if b else 'unchanged'}")


if __name__ == "__main__":
    main()
