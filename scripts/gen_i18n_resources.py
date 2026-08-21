#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LibreDeX i18n Phase 1: generate bilingual strings.xml + replace layout literals.

Reads docs/i18n/strings-inventory.csv:
  1. Writes res/values/strings.xml (EN, default) and res/values-zh-rCN/strings.xml (zh).
  2. Rewrites res/layout/*.xml and res/menu/*.xml replacing android:text / hint /
     contentDescription / title literal values with @string/<key> references.
"""
import csv
import io
import os
import re
import xml.sax.saxutils as sax

BASE = r"D:\dex_work\app\src\main"
INV = r"D:\dex_work\docs\i18n\strings-inventory.csv"

# fmt entries: zh source literal -> zh string with placeholder (same shape as EN)
ZH_FMT = {
    "版本 ": "版本 %1$s",
    "运行中 · 屏幕 ": "运行中 · 屏幕 %1$s",
    "其他输出运行中 · 外接屏 ": "其他输出运行中 · 外接屏 %1$s",
    "外接屏 ": "外接屏 %1$s",
    "修改屏幕 ": "修改屏幕 %1$s",
    "修改分辨率失败: ": "修改分辨率失败: %1$s",
    "修改 DPI 失败: ": "修改 DPI 失败: %1$s",
    "选择屏幕 ": "选择屏幕 %1$s",
    "设置刷新模式失败: ": "设置刷新模式失败: %1$s",
    "设置旋转失败: ": "设置旋转失败: %1$s",
    "恢复默认失败: ": "恢复默认失败: %1$s",
}

# pre-existing entries to keep in values/ (app_name referenced by manifest; the
# three buttons were unused legacy, kept for safety)
LEGACY = {
    "app_name": "LibreDeX",
    "go_dark_button": "Switch to dark mode",
    "back_button": "Back button",
    "home_button": "Home button",
}
LEGACY_ZH = {
    "app_name": "LibreDeX",
    "go_dark_button": "切换暗色模式",
    "back_button": "返回按钮",
    "home_button": "主页按钮",
}

def esc(s):
    # XML attribute/text escaping for string resource values
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace('"', '\\"').replace("'", "\\'")
    # real newlines -> literal \n escape (single-line XML, AAPT renders \n)
    s = s.replace("\n", "\\n")
    return s

def build_strings_xml(entries, legacy, is_en):
    lines = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>"]
    for k, v in sorted(legacy.items()):
        lines.append('    <string name="%s">%s</string>' % (k, esc(v)))
    for key, zh, en, note in entries:
        if is_en:
            val = en
        else:
            val = ZH_FMT.get(zh, zh)
        # literal % (not a placeholder) must be flagged non-formatted so AAPT
        # does not treat it as a format string
        if "%" in val and "%1$" not in val:
            lines.append('    <string name="%s" formatted="false">%s</string>' % (key, esc(val)))
        else:
            lines.append('    <string name="%s">%s</string>' % (key, esc(val)))
    lines.append("</resources>")
    return "\n".join(lines) + "\n"

def main():
    with open(INV, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))

    entries = [(r["key"], r["zh"], r["en"], r["note"]) for r in rows]
    zh_to_key = {r["zh"]: r["key"] for r in rows}

    # 1) write res files
    res_values = os.path.join(BASE, "res", "values")
    res_zh = os.path.join(BASE, "res", "values-zh-rCN")
    os.makedirs(res_zh, exist_ok=True)
    with open(os.path.join(res_values, "strings.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(build_strings_xml(entries, LEGACY, is_en=True))
    with open(os.path.join(res_zh, "strings.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(build_strings_xml(entries, LEGACY_ZH, is_en=False))
    print("wrote values/strings.xml (en) + values-zh-rCN/strings.xml (zh): %d keys" % len(entries))

    # 2) rewrite layouts & menus
    attr_pat = re.compile(
        r'(android:(?:text|hint|contentDescription|title))="([^"]*)"'
    )
    replaced_total = 0
    for sub in ("layout", "menu"):
        d = os.path.join(BASE, "res", sub)
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(d, fn)
            with open(path, encoding="utf-8") as f:
                src = f.read()
            def rep(m):
                nonlocal replaced_total
                attr, val = m.group(1), m.group(2).strip()
                if not val or val.startswith("@") or val.startswith("?"):
                    return m.group(0)
                key = zh_to_key.get(val)
                if key is None:
                    return m.group(0)
                replaced_total += 1
                return '%s="@string/%s"' % (attr, key)
            out = attr_pat.sub(rep, src)
            if out != src:
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(out)
                print("  patched %s/%s" % (sub, fn))
    print("replaced literals:", replaced_total)

if __name__ == "__main__":
    main()
