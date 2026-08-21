#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LibreDeX i18n Phase 2: fix the fmt (concatenation) call sites manually.

Each entry is (relpath, old_exact, new_exact). Replaces in order and reports
misses so nothing is silently skipped.
"""
import io
import os

BASE = r"D:\dex_work\app\src\main\java\com\connect_screen\mirror"

FIXES = [
    (r"AboutActivity.java",
     'versionText.setText("版本 " + BuildConfig.VERSION_NAME + " ("\n                + BuildConfig.COMMIT + ") / Android " + androidVersion);',
     'versionText.setText(getString(R.string.about_version_fmt,\n                BuildConfig.VERSION_NAME, BuildConfig.COMMIT, androidVersion));'),

    (r"ConnectionFragment.java",
     'entryEncoderSub.setText(codec + " · 码率 " + Pref.getEncoderBitratePercent()\n                + "% · " + Pref.getEncoderMaxFps() + "fps");',
     'entryEncoderSub.setText(getString(R.string.connection_encoder_sub_fmt,\n                codec, Pref.getEncoderBitratePercent(), Pref.getEncoderMaxFps()));'),
    (r"ConnectionFragment.java",
     'placeholderStatusText.setText("运行中 · 屏幕 " + State.externalDisplayId);',
     'placeholderStatusText.setText(getString(R.string.connection_running_screen_fmt, State.externalDisplayId));'),
    (r"ConnectionFragment.java",
     'placeholderStatusText.setText("其他输出运行中 · 外接屏 " + State.externalDisplayId);',
     'placeholderStatusText.setText(getString(R.string.connection_running_external_fmt, State.externalDisplayId));'),
    (r"ConnectionFragment.java",
     'placeholderStatusText.setText("外接屏 " + State.externalDisplayId\n                    + " · " + State.externalDisplayWidth + "x" + State.externalDisplayHeight);',
     'placeholderStatusText.setText(getString(R.string.connection_external_fmt,\n                    State.externalDisplayId, State.externalDisplayWidth,\n                    State.externalDisplayHeight));'),
    (r"ConnectionFragment.java",
     'showToast("外接屏模式应用失败：" + e.getMessage());',
     'showToast(getString(R.string.connection_external_mode_failed_fmt, e.getMessage()));'),
    (r"ConnectionFragment.java",
     'showToast("DP 会话重启失败：" + e.getMessage());',
     'showToast(getString(R.string.connection_dp_restart_failed_fmt, e.getMessage()));'),
    (r"ConnectionFragment.java",
     'showToast("会话重启失败：" + error);',
     'showToast(getString(R.string.connection_restart_failed_fmt, error));'),

    (r"DexManageFragment.java",
     'showToast("DP 会话重启失败：" + e.getMessage());',
     'showToast(getString(R.string.connection_dp_restart_failed_fmt, e.getMessage()));'),
    (r"DexManageFragment.java",
     'showToast("会话重启失败：" + error);',
     'showToast(getString(R.string.connection_restart_failed_fmt, error));'),

    (r"BlackScreenOverlayService.java",
     'State.showErrorStatus("黑色画面模拟息屏启动失败: " + e.getMessage());',
     'State.showErrorStatus(getString(R.string.overlay_start_failed_fmt, e.getMessage()));'),

    (r"ScreenSettingsActivity.java",
     '.setTitle("修改屏幕 " + display.getDisplayId() + " 分辨率")',
     '.setTitle(getString(R.string.screen_edit_screen_resolution_fmt, display.getDisplayId()))'),
    (r"ScreenSettingsActivity.java",
     'Toast.makeText(this, "修改分辨率失败: " + e.getMessage(), Toast.LENGTH_LONG).show();',
     'Toast.makeText(this, getString(R.string.screen_resolution_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();'),
    (r"ScreenSettingsActivity.java",
     '.setTitle("修改屏幕 " + display.getDisplayId() + " DPI")',
     '.setTitle(getString(R.string.screen_edit_screen_dpi_fmt, display.getDisplayId()))'),
    (r"ScreenSettingsActivity.java",
     'Toast.makeText(this, "修改 DPI 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();',
     'Toast.makeText(this, getString(R.string.screen_dpi_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();'),
    (r"ScreenSettingsActivity.java",
     '.setTitle("选择屏幕 " + display.getDisplayId() + " 刷新模式")',
     '.setTitle(getString(R.string.screen_select_screen_mode_fmt, display.getDisplayId()))'),
    (r"ScreenSettingsActivity.java",
     'Toast.makeText(this, "设置刷新模式失败: " + e.getMessage(), Toast.LENGTH_LONG).show();',
     'Toast.makeText(this, getString(R.string.screen_refresh_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();'),
    (r"ScreenSettingsActivity.java",
     '.setTitle("修改屏幕 " + displayId + " 旋转")',
     '.setTitle(getString(R.string.screen_edit_screen_rotation_fmt, displayId))'),
    (r"ScreenSettingsActivity.java",
     'Toast.makeText(this, "设置旋转失败: " + e.getMessage(), Toast.LENGTH_LONG).show();',
     'Toast.makeText(this, getString(R.string.screen_rotation_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();'),
    (r"ScreenSettingsActivity.java",
     'Toast.makeText(this, "恢复默认失败: " + e.getMessage(), Toast.LENGTH_LONG).show();',
     'Toast.makeText(this, getString(R.string.screen_restore_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();'),
    (r"ScreenSettingsActivity.java",
     'String.format(java.util.Locale.US, "保留屏幕 %d 的新分辨率 %dx%d？5 秒内未确认会恢复。", displayId, width, height),',
     'getString(R.string.screen_confirm_resolution_msg_fmt, displayId, width, height),'),
]

def main():
    cache = {}
    missed = []
    for rel, old, new in FIXES:
        if rel not in cache:
            path = os.path.join(BASE, rel)
            cache[rel] = io.open(path, encoding="utf-8").read()
        if old not in cache[rel]:
            missed.append((rel, old[:70]))
            continue
        cache[rel] = cache[rel].replace(old, new, 1)
    for rel, content in cache.items():
        path = os.path.join(BASE, rel)
        io.open(path, "w", encoding="utf-8", newline="\n").write(content)
    print("applied:", len(FIXES) - len(missed), "of", len(FIXES))
    if missed:
        print("MISSED:")
        for rel, frag in missed:
            print("  ", rel, "->", frag)

if __name__ == "__main__":
    main()
