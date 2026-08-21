#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LibreDeX i18n Phase 2: replace Java UI string literals with getString(R.string.x).

Reads docs/i18n/strings-inventory.csv + the EXTRA keys from gen_i18n_resources.py,
then rewrites Java sources: every UI literal (in the zh map) becomes
<contextExpr>.getString(R.string.<key>). fmt entries are SKIPPED (handled
manually). Reports unmatched UI-ish call sites for manual review.
"""
import csv
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_i18n_resources import EXTRA, OVERRIDES, ZH_FMT

BASE = r"D:\dex_work\app\src\main\java\com\connect_screen\mirror"
INV = r"D:\dex_work\docs\i18n\strings-inventory.csv"

# file -> context expression used to resolve resources
CTX = {
    "AboutActivity.java": "getString",
    "ConnectionFragment.java": "getString",
    "DexManageFragment.java": "getString",
    "MirrorMainActivity.java": "getString",
    "SettingsFragment.java": "getString",
    "ScreenSettingsActivity.java": "getString",
    "SunshineService.java": "getString",
    "InitializationGuideDialog.java": "activity.getString",
    "DebugDialogs.java": "context.getString",
    "DebugLogDialog.java": "context.getString",
    "DexTouchpadLauncher.java": "context.getString",
    "BlackScreenOverlayService.java": "getString",
    "job/FetchLogAndShare.java": "State.getContext().getString",
    "job/SunshineServer.java": "context.getString",
    "job/InputRouting.java": "context.getString",
    "job/ProjectViaDp.java": "State.getContext().getString",
    "job/ProjectViaMoonlight.java": "State.getContext().getString",
    "job/AutoRotateAndScaleForMoonlight.java": "State.getContext().getString",
    "job/StartSunshineService.java": "State.getContext().getString",
}

def load_map():
    with open(INV, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    m = {}
    for r in rows:
        zh = r["zh"]
        if r["note"] == "fmt" or zh in ZH_FMT or r["key"] in OVERRIDES:
            continue  # fmt entries handled manually
        m[zh] = r["key"]
    for key, en, zh in EXTRA:
        if "%1$" in zh:
            continue
        m[zh] = key
        if en != zh:
            m[en] = key  # some sources already hardcode the EN literal
    return m

def main():
    zh_to_key = load_map()
    print("map size (skipping fmt):", len(zh_to_key))
    # sort by length desc so longer strings replace first (no overlap anyway)
    pairs = sorted(zh_to_key.items(), key=lambda kv: -len(kv[0]))

    unmatched = []
    total = 0
    for rel, ctx in CTX.items():
        path = os.path.join(BASE, rel)
        if not os.path.exists(path):
            print("MISSING FILE:", path)
            continue
        with open(path, encoding="utf-8") as f:
            src = f.read()

        file_hits = 0
        def rep(m):
            nonlocal file_hits
            zh = m.group(1)
            key = zh_to_key.get(zh)
            if key is None:
                return m.group(0)
            file_hits += 1
            # ctx == "getString" -> class inherits Context, call directly.
            # ctx ends with ".getString" (e.g. context.getString,
            # State.getContext().getString) -> use as-is.
            if ctx == "getString":
                return 'getString(R.string.%s)' % key
            if ctx.endswith(".getString"):
                return '%s(R.string.%s)' % (ctx, key)
            return '%s.getString(R.string.%s)' % (ctx, key)

        # match quoted string literals that appear in the zh map
        out = re.sub(r'"((?:[^"\\]|\\.)*)"', rep, src)
        if out != src:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(out)
        total += file_hits
        print("  %-32s replaced %d" % (rel, file_hits))

        # report remaining UI-ish literals (CJK or button-ish) for manual work
        for i, line in enumerate(out.splitlines(), 1):
            if re.search(r'[\u4e00-\u9fff]', line) and '"' in line and "R.string" not in line:
                if re.search(r'setText|setTitle|setMessage|setHint|makeText|setPositive|setNegative|setNeutral|setItems|setButton|new String\[\]|NotificationChannel|setContent', line):
                    unmatched.append("%s:%d: %s" % (rel, i, line.strip()[:110]))
    print("total replaced:", total)
    print("=== manual review needed (%d) ===" % len(unmatched))
    for u in unmatched:
        print(" ", u)

if __name__ == "__main__":
    main()
