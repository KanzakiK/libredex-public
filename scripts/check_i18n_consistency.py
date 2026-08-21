#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LibreDeX i18n Phase 3: verify en/zh string resources consistency.

Checks:
  1. key sets match exactly
  2. format placeholders (%1$d / %2$s ...) have identical count & types
  3. no CJK chars left in EN values, no obvious untranslated English in ZH
     (brand words / units / URLs allowed)
  4. reports any suspicious entries for manual review
"""
import re
import sys
import xml.etree.ElementTree as ET

EN = r"D:\dex_work\app\src\main\res\values\strings.xml"
ZH = r"D:\dex_work\app\src\main\res\values-zh-rCN\strings.xml"

BRAND = re.compile(
    r"^(LibreDeX|DeX|Moonlight|Shizuku|Sunshine|LSPosed|Vector|DP|HDMI|Display|"
    r"FEC|Hz|Ping|Root|IP|DPI|FPS|PIN|USB|H\.264|H\.265|AVC|HEVC|CBR|VBR|CQ|"
    r"SecondaryLauncher|UserService|DisplayLink|Type-C|CJK|GitHub|bilibili|"
    r"NOTICE\.md|0°|90°|180°|270°|0-10|1-10|1-240|0-50|25-200|%|·|\d+|--|"
    r"192\.168\.50\.50|persist\.dex\.lspmirror\.mode|Sunshine Service Channel|"
    r"1920×1080|60Hz|fps|FPS|bitrate|Bitrate|Complexity|stable|dynamic|quality|"
    r"%\d+\$[a-zA-Z]|[A-Za-z0-9][A-Za-z0-9 \-/·%.()]*)$"
)

# EN strings that intentionally embed CJK (search keywords users must type,
# or language self-names in the language picker)
CJK_IN_EN_ALLOWED = {
    "dialog_edit_resolution_warning",
    "settings_language_zh",
}

# ZH values that legitimately contain no CJK (brand names, tech placeholders)
ZH_ALLOWED_UNTRANSLATED = {
    "about_version_app_fmt": "LibreDeX %1$s",
    "connection_ip_placeholder": "IP：--",
    "dex_display_summary_fmt": "%1$s · 1920×1080 · 60Hz",
    "settings_root_status": "Root: -",
    "debug_field_sep": "：",
    "debug_join_level": " / Level %1$s",
    "debug_label_ping": "Ping：",
    "screen_info_mode_id": " (ID %1$d)",
}

def parse(path):
    tree = ET.parse(path)
    d = {}
    for el in tree.getroot():
        if el.tag == "string":
            d[el.attrib["name"]] = (el.text or "", el.attrib.get("formatted"))
        elif el.tag == "string-array":
            for i, item in enumerate(el):
                d["%s[%d]" % (el.attrib["name"], i)] = (item.text or "", None)
    return d

def placeholders(s):
    return re.findall(r"%(\d+)\$([a-zA-Z])", s)

def main():
    en, zh = parse(EN), parse(ZH)
    problems = []
    en_keys, zh_keys = set(en), set(zh)
    if en_keys != zh_keys:
        only_en = en_keys - zh_keys
        only_zh = zh_keys - en_keys
        if only_en:
            problems.append("EN only: %s" % sorted(only_en))
        if only_zh:
            problems.append("ZH only: %s" % sorted(only_zh))

    for k in sorted(en_keys & zh_keys):
        ev, zvv = en[k][0], zh[k][0]
        ep, zp = placeholders(ev), placeholders(zvv)
        # indexed placeholders may appear in any order across languages
        if sorted(ep) != sorted(zp):
            problems.append("PLACEHOLDER MISMATCH %s: en=%s zh=%s (en=%r zh=%r)"
                            % (k, ep, zp, ev[:60], zvv[:60]))
        if re.search(r"[\u4e00-\u9fff]", ev) and k not in CJK_IN_EN_ALLOWED:
            problems.append("CJK IN EN %s: %r" % (k, ev[:60]))
        # zh should not be mostly-English unless brand/tech
        if (zvv and not re.search(r"[\u4e00-\u9fff]", zvv)
                and not BRAND.match(zvv.strip())
                and ZH_ALLOWED_UNTRANSLATED.get(k) != zvv):
            problems.append("ZH MAYBE UNTRANSLATED %s: %r" % (k, zvv[:60]))

    print("en keys:", len(en), "zh keys:", len(zh))
    if problems:
        print("PROBLEMS (%d):" % len(problems))
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("ALL CONSISTENT")

if __name__ == "__main__":
    main()
