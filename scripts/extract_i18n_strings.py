#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LibreDeX i18n Phase 0: extract hardcoded UI strings from layouts/menus/Java code.

Output: docs/i18n/strings_raw.csv  (deduplicated, with suggested key and source locations)
"""
import csv
import os
import re
import sys
from collections import OrderedDict

BASE = r"D:\dex_work\app\src\main"
OUT_DIR = r"D:\dex_work\docs\i18n"
os.makedirs(OUT_DIR, exist_ok=True)

# ---------------------------------------------------------------- layouts / menus
ATTR_PATTERN = re.compile(
    r'android:(text|hint|contentDescription|title)="([^"]*)"'
)
NON_RES = re.compile(r'^[^@?]')          # skip @string/... and ?attr/... references

def extract_xml(root_dir, kind):
    rows = []  # (text, kind, relpath, line)
    for dirname in (os.path.join(BASE, "res", "layout"), os.path.join(BASE, "res", "menu")):
        if not os.path.isdir(dirname):
            continue
        for fn in sorted(os.listdir(dirname)):
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirname, fn)
            rel = os.path.relpath(path, BASE)
            with open(path, encoding="utf-8") as f:
                lines = f.readlines()
            for i, line in enumerate(lines, 1):
                for m in ATTR_PATTERN.finditer(line):
                    val = m.group(2).strip()
                    if val and NON_RES.match(val):
                        rows.append((val, kind, rel, i))
    return rows

# ---------------------------------------------------------------- java code
UI_PATTERNS = [
    (r'\.setText\(\s*"((?:[^"\\]|\\.)*)"', "setText"),
    (r'\.setTitle\(\s*"((?:[^"\\]|\\.)*)"', "dialog/title"),
    (r'\.setMessage\(\s*"((?:[^"\\]|\\.)*)"', "dialog/message"),
    (r'\.setHint\(\s*"((?:[^"\\]|\\.)*)"', "hint"),
    (r'\.setContentTitle\(\s*"((?:[^"\\]|\\.)*)"', "notify"),
    (r'\.setContentText\(\s*"((?:[^"\\]|\\.)*)"', "notify"),
    (r'\.setTicker\(\s*"((?:[^"\\]|\\.)*)"', "notify"),
    (r'\.setSubText\(\s*"((?:[^"\\]|\\.)*)"', "notify"),
    (r'\.setTabText\(\s*"((?:[^"\\]|\\.)*)"', "tab"),
    (r'\.setPositiveButton\(\s*"((?:[^"\\]|\\.)*)"', "dialog/btn"),
    (r'\.setNegativeButton\(\s*"((?:[^"\\]|\\.)*)"', "dialog/btn"),
    (r'\.setNeutralButton\(\s*"((?:[^"\\]|\\.)*)"', "dialog/btn"),
    (r'Toast\.makeText\([^,]+,\s*"((?:[^"\\]|\\.)*)"', "toast"),
    (r'Snackbar\.make\([^,]+,[^,]+,\s*"((?:[^"\\]|\\.)*)"', "snackbar"),
]

def extract_java():
    rows = []
    java_root = os.path.join(BASE, "java")
    for dirpath, _dirnames, filenames in os.walk(java_root):
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, BASE)
            with open(path, encoding="utf-8") as f:
                lines = f.readlines()
            for i, line in enumerate(lines, 1):
                # skip pure logging / comment lines quickly
                stripped = line.lstrip()
                if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                    continue
                for pat, kind in UI_PATTERNS:
                    for m in re.finditer(pat, line):
                        val = m.group(1).replace('\\"', '"').replace('\\\\', '\\')
                        # keep only strings with CJK chars OR short non-trivial ascii labels
                        if re.search(r'[\u4e00-\u9fff]', val):
                            rows.append((val, kind, rel, i))
    return rows

# ---------------------------------------------------------------- dedupe & key
def module_prefix(rel):
    name = os.path.basename(rel)
    low = name.lower()
    if rel.startswith("res" + os.sep + "layout"):
        if name.startswith("dialog_"):
            return "dialog"
        if name.startswith("fragment_"):
            return name[len("fragment_"):].replace(".xml", "")
        if name.startswith("activity_"):
            return name[len("activity_"):].replace(".xml", "")
        if name.startswith("item_"):
            return "item"
        return name.replace(".xml", "")
    if rel.startswith("res" + os.sep + "menu"):
        return "nav"
    # java files
    if "ConnectionFragment" in name: return "connection"
    if "SettingsFragment" in name: return "settings"
    if "DexManageFragment" in name: return "dex"
    if "AboutActivity" in name: return "about"
    if "MirrorMainActivity" in name: return "mirror"
    if "InitializationGuideDialog" in name: return "guide"
    if "ScreenSettingsActivity" in name: return "screen"
    if "DebugLogDialog" in name or "DebugDialogs" in name: return "debug"
    if "SunshineService" in name or "TransportOutputService" in name: return "notify"
    if "PureBlackActivity" in name or "BlackScreenOverlayService" in name: return "screen"
    if "LogAdapter" in name: return "log"
    return "code"

def slugify(text, maxlen=40):
    # crude pinyin-free slug: keep ascii/digits, else placeholder hash of first char
    s = re.sub(r'[^A-Za-z0-9]+', '_', text).strip('_')
    if not s:
        s = "txt_%x" % (abs(hash(text)) & 0xfffff)
    return s[:maxlen].lower()

def main():
    rows = extract_xml(None, "layout") + extract_java()
    # group by text -> OrderedDict: text -> {kinds:set, locations:[rel:line], count}
    grouped = OrderedDict()
    for text, kind, rel, line in rows:
        entry = grouped.setdefault(text, {"kinds": set(), "locs": [], "count": 0})
        entry["kinds"].add(kind)
        loc = "%s:%d" % (rel, line)
        if loc not in entry["locs"]:
            entry["locs"].append(loc)
        entry["count"] += 1

    out = []
    for idx, (text, e) in enumerate(grouped.items(), 1):
        prefix = module_prefix(e["locs"][0].split(":")[0])
        key = "%s_%s" % (prefix, slugify(text))
        out.append({
            "id": idx,
            "key": key,
            "zh": text,
            "en": "",
            "type": ",".join(sorted(e["kinds"])),
            "count": e["count"],
            "locations": " | ".join(e["locs"]),
        })

    csv_path = os.path.join(OUT_DIR, "strings_raw.csv")
    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["id", "key", "zh", "en", "type", "count", "locations"])
        w.writeheader()
        w.writerows(out)

    # stats
    cjk = sum(1 for r in out if re.search(r'[\u4e00-\u9fff]', r["zh"]))
    ascii_only = len(out) - cjk
    by_type = {}
    for r in out:
        for t in r["type"].split(","):
            by_type[t] = by_type.get(t, 0) + 1
    print("deduped total: %d (cjk: %d, ascii-only: %d)" % (len(out), cjk, ascii_only))
    print("by type:", dict(sorted(by_type.items(), key=lambda kv: -kv[1])))
    print("saved:", csv_path)

if __name__ == "__main__":
    main()
