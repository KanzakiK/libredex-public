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
    "版本 ": "版本 %1$s (%2$s)",
    "运行中 · 屏幕 ": "运行中 · 屏幕 %1$d",
    "其他输出运行中 · 外接屏 ": "其他输出运行中 · 外接屏 %1$d",
    "外接屏 ": "外接屏 %1$d · %2$dx%3$d",
    "修改屏幕 ": "修改屏幕 %1$d",
    "修改分辨率失败: ": "修改分辨率失败: %1$s",
    "修改 DPI 失败: ": "修改 DPI 失败: %1$s",
    "选择屏幕 ": "选择屏幕 %1$d",
    "设置刷新模式失败: ": "设置刷新模式失败: %1$s",
    "设置旋转失败: ": "设置旋转失败: %1$s",
    "恢复默认失败: ": "恢复默认失败: %1$s",
}

# en/zh value overrides for existing keys discovered during Phase 2
OVERRIDES = {
    "connection_running_screen_fmt": ("Running · Screen %1$d", "运行中 · 屏幕 %1$d"),
    "connection_running_external_fmt": ("Other output running · External %1$d", "其他输出运行中 · 外接屏 %1$d"),
    "about_version_fmt": ("Version %1$s (%2$s) / Android %3$s", "版本 %1$s (%2$s) / Android %3$s"),
    "screen_edit_screen_fmt": ("Modify screen %1$d", "修改屏幕 %1$d"),
    "screen_select_screen_fmt": ("Select screen %1$d", "选择屏幕 %1$d"),
    "connection_external_fmt": ("External %1$d · %2$dx%3$d", "外接屏 %1$d · %2$dx%3$d"),
}

# extra keys added during Phase 2 (not in the inventory CSV)
EXTRA = [
    # (key, en, zh)
    ("connection_running", "Running", "运行中"),
    ("dex_running", "Running", "已运行"),
    ("connection_stop_dp", "Stop DP output", "停止 DP 输出"),
    ("connection_stop_service", "Stop service", "停止服务"),
    ("notify_channel_name", "Sunshine Service Channel", "Sunshine Service Channel"),
    ("notify_sunshine_title", "LibreDeX", "LibreDeX"),
    ("notify_sunshine_text", "Sunshine Host is running", "Sunshine Host is running"),
    ("about_version_app_fmt", "LibreDeX %1$s", "LibreDeX %1$s"),
    ("screen_edit_screen_resolution_fmt", "Modify screen %1$d resolution", "修改屏幕 %1$d 分辨率"),
    ("screen_edit_screen_dpi_fmt", "Modify screen %1$d DPI", "修改屏幕 %1$d DPI"),
    ("screen_select_screen_mode_fmt", "Select screen %1$d refresh mode", "选择屏幕 %1$d 刷新模式"),
    ("screen_edit_screen_rotation_fmt", "Modify screen %1$d rotation", "修改屏幕 %1$d 旋转"),
    ("screen_rotation_none", "No force", "不强制"),
    ("screen_rotation_0", "0°", "0°"),
    ("screen_rotation_90", "90°", "90°"),
    ("screen_rotation_180", "180°", "180°"),
    ("screen_rotation_270", "270°", "270°"),
    ("connection_bitrate_mode_cbr", "CBR - stable bitrate", "CBR - 稳定带宽"),
    ("connection_bitrate_mode_vbr", "VBR - dynamic bitrate", "VBR - 动态码率"),
    ("connection_bitrate_mode_cq", "CQ - constant quality", "CQ - 恒定质量"),
    ("connection_encoder_sub_fmt", "%1$s · bitrate %2$d%% · %3$dfps", "%1$s · 码率 %2$d%% · %3$dfps"),
    # Phase 2 showToast / showErrorStatus / misc
    ("connection_encoder_saved", "Encoder settings saved", "编码设置已保存"),
    ("connection_encoder_restored", "Encoder settings restored to defaults", "编码设置已恢复默认"),
    ("connection_stopped_all", "All outputs stopped", "已停止全部输出"),
    ("connection_stopped_dp", "DP output stopped", "已停止 DP 输出"),
    ("connection_starting_dp", "Starting DP output…", "正在启动 DP 输出"),
    ("connection_no_modes", "No modes available for this screen", "此屏幕没有可选模式"),
    ("connection_external_mode_applied", "External display mode applied", "已应用外接屏模式"),
    ("connection_external_mode_failed", "Failed to apply external display mode", "外接屏模式应用失败"),
    ("connection_external_mode_failed_fmt", "Failed to apply external display mode: %1$s", "外接屏模式应用失败：%1$s"),
    ("connection_invalid_whr", "Enter valid width, height and refresh rate", "请输入有效的宽、高、刷新率"),
    ("connection_dp_source_switched", "DP output source switched", "已切换 DP 输出源"),
    ("connection_need_dex", "DeX session required", "需要 DeX 会话"),
    ("connection_connect_dex_first", "Connect DeX first", "请先连接 DeX"),
    ("connection_restarting_session", "Restarting session…", "正在重启会话…"),
    ("connection_dp_restart_failed_fmt", "DP session restart failed: %1$s", "DP 会话重启失败：%1$s"),
    ("connection_restart_failed_fmt", "Session restart failed: %1$s", "会话重启失败：%1$s"),
    ("connection_session_restarted", "Session restarted", "会话已重启"),
    ("connection_no_dex_session", "No DeX session to restart", "没有可重启的 DeX 会话"),
    ("connection_recent_client", "192.168.50.50 · Moonlight", "192.168.50.50 · Moonlight"),
    ("about_description",
     "LibreDeX turns the Galaxy Z Flip 5 into a DeX streaming host that does not rely on Miracast: after pairing the Moonlight client, the fake DeX desktop is streamed through Sunshine, and control input (mouse, keyboard, touch) flows back to the device.\n\nIt integrates LSPosed hooks (display flags / input / pointer / viewport / layer fixes), Shizuku/UserService system interfaces, AudioPolicy loopback audio capture, and a Sunshine / Moonlight-compatible streaming pipeline.\n\nThis project is open source under GPL-3.0; upstream sources and modifications are described in NOTICE.md.",
     "LibreDeX 把 Galaxy Z Flip 5 变成不依赖 Miracast 的 DeX 串流主机：Moonlight 客户端配对后，通过 Sunshine 串流 fake DeX 桌面，并把鼠标、键盘、触摸等控制输入回流到设备。\n\n项目集成 LSPosed hooks（display flags / input / pointer / viewport / 层级修复）、Shizuku/UserService 系统接口、AudioPolicy loopback 音频采集，以及 Sunshine / Moonlight 兼容串流链路。\n\n本项目为 GPL-3.0 开源项目；上游来源与修改说明见仓库 NOTICE.md。"),
    ("screen_confirm_resolution", "Confirm resolution", "确认分辨率"),
    ("screen_confirm_resolution_msg_fmt",
     "Keep the new resolution %2$dx%3$d for screen %1$d? It reverts if not confirmed within 5 s.",
     "保留屏幕 %1$d 的新分辨率 %2$dx%3$d？5 秒内未确认会恢复。"),
    ("overlay_need_permission", "Overlay permission is required to simulate screen-off with a black image", "需要悬浮窗权限才能使用黑色画面模拟息屏"),
    ("overlay_start_failed_fmt", "Black screen-off failed to start: %1$s", "黑色画面模拟息屏启动失败: %1$s"),
    ("dp_need_shizuku", "DP output needs Shizuku permission", "DP 输出需要 Shizuku 权限"),
    ("dp_no_external", "No external display detected. Connect a DP/HDMI cable first.", "未检测到外接屏，请先连接 DP/HDMI 线"),
    ("dp_read_dimensions_failed", "Cannot read external display dimensions", "无法读取外接屏尺寸"),
    ("mirror_lost_shizuku", "Mirror mode lost the Shizuku user service while updating auto-rotate mirror", "更新自动旋转镜像时丢失了 Shizuku user service"),
    ("mirror_need_shizuku", "Mirror mode needs Shizuku permission to capture display 0", "镜像模式需要 Shizuku 权限采集 Display 0"),
    ("moonlight_mirror_need_shizuku", "Moonlight mirror needs Shizuku permission", "Moonlight 镜像需要 Shizuku 权限"),
    ("moonlight_mirror_wait_failed", "Moonlight mirror failed to wait for the Shizuku user service. Confirm Shizuku is running, then retry.", "Moonlight 镜像等待 Shizuku user service 失败。请确认 Shizuku 已运行后重试。"),
    ("sunshine_start_failed", "Cannot start SunshineService without an active UI", "没有活跃 UI 时无法启动 SunshineService"),
    ("dex_display_summary_fmt", "%1$s · 1920×1080 · 60Hz", "%1$s · 1920×1080 · 60Hz"),
    ("settings_language", "Language", "语言"),
    ("settings_language_follow_system", "Follow system", "跟随系统"),
    ("settings_language_zh", "简体中文", "简体中文"),
    ("settings_language_en", "English", "English"),
    ("settings_online", "Online", "在线"),
    ("settings_offline", "Offline", "离线"),
    ("settings_authorized_userservice", "Authorized · UserService online", "已授权 · UserService 在线"),
    ("settings_authorized", "Authorized", "已授权"),
    ("settings_granted", "Granted", "已授予"),
    ("guide_root_running", "Running as root", "已以 root 运行"),
    ("guide_root_not_running", "Not running as root", "未以 root 运行"),
    ("guide_shizuku_pending", "Shizuku authorization pending", "待授权 Shizuku"),
    ("guide_framework_active", "Framework active", "已检测到框架活跃"),
    ("guide_framework_not_detected", "Framework not detected (enable the module and reboot)", "未检测到框架（需启用模块并重启）"),
    ("guide_shizuku_root_restarted", "Shizuku restarted as root", "Shizuku 已以 root 重启"),
    ("guide_root_restart_failed", "Failed to restart as root", "以 root 重启失败"),
    ("screen_shizuku_granted_note", "Shizuku permission granted. External displays can be modified; the built-in screen is view-only.", "已获得 Shizuku 权限。外接屏可修改参数，内置屏幕仅允许查看。"),
    ("screen_shizuku_not_granted_note", "Shizuku permission not granted; only basic screen info is visible.", "未获得 Shizuku 权限，只能查看基础屏幕信息。"),
    ("settings_root_restart_failed", "Failed to restart Shizuku as root", "以 root 重启 Shizuku 失败"),
    ("connection_processing", "Processing", "处理中"),
    ("connection_waiting_connect", "Waiting for connection", "等待连接"),
    ("mirror_sunshine_waiting", "Sunshine service started, waiting for connection", "Sunshine 服务已启动，等待连接中"),
    ("moonlight_cursor_hint", "Press Ctrl+Alt+Shift+C to show the cursor\nIf it is unresponsive, switch the control mode in Moonlight", "按 Ctrl+Alt+Shift+C 打开光标\n如果不可操控，请在 Moonlight 切换一下控制模式"),
    ("debug_summary_placeholder", "Encoder: not started\nEncoder: --\nResolution: --\nFPS: in -- / out --\nBitrate: --\nPing: --", "编码状态：未启动\n编码器：--\n分辨率：--\n帧率：输入 -- / 输出 --\n码率：--\nPing：--"),
    ("debug_label_status", "Encoder status: ", "编码状态："),
    ("debug_label_encoder", "Encoder: ", "编码器："),
    ("debug_label_resolution", "Resolution: ", "分辨率："),
    ("debug_label_fps_req", "Requested/encoded FPS: ", "请求/编码帧率："),
    ("debug_label_fps_io", "Input/output FPS: ", "输入/输出帧率："),
    ("debug_label_bitrate", "Bitrate: ", "码率："),
    ("debug_label_ping", "Ping: ", "Ping："),
    ("debug_join_level", " / Level %1$s", " / Level %1$s"),
    ("debug_join_target", " / target %1$s", " / 目标 %1$s"),
    ("debug_field_priority", "Priority", "优先级"),
    ("debug_field_audio", "Audio", "音频"),
    ("debug_field_color", "Color", "色彩"),
    ("debug_field_output_gap", "Output gap", "输出间隔"),
    ("debug_field_queue", "Queue", "队列"),
    ("debug_field_native", "Native", "Native"),
    ("debug_field_frame_pacer", "Frame pacer", "Frame pacer"),
    ("debug_field_sep", ": ", "："),
    ("guide_page_title_lsposed", "LSPosed Hook", "LSPosed Hook"),
    ("guide_page_title_environment", "Shizuku / Root / overlay", "Shizuku / Root / 悬浮窗"),
    ("guide_page_title_recording", "Recording", "录音"),
    ("guide_page_title_files", "File access · Done", "文件访问 · 完成"),
    ("guide_label_result", "Result", "检测结果"),
    ("guide_lsposed_note", "LibreDeX core display/input hooks depend on LSPosed (stock or Vector).\nEnable this module there, scope it to android / Samsung Settings / launcher, then reboot for the hooks to take effect.\nNote: Vector variants have no standalone app icon; the entry is the LSPosed shortcut on the home screen or in the notification shade.", "LibreDeX 的核心显示/输入钩子依赖 LSPosed（原版或 Vector 均可）。\n请在其中启用本模块，作用域勾选 android / 三星设置 / 桌面，然后重启手机使 Hook 生效。\n提示：Vector 变种没有独立 App，入口在桌面的“LSPosed”快捷方式或通知栏里。"),
    ("guide_lsposed_manager_not_open", "Could not detect that the LSPosed / Vector manager is open", "未检测到 LSPosed / Vector 管理器打开"),
    ("guide_launch_failed_fmt", "Failed to launch: %1$s", "拉起失败：%1$s"),
    ("guide_open_lsposed_manual", "Please open it manually:\n· the LSPosed (Vector) shortcut on the home screen, or\n· the LSPosed / Vector entry in the notification shade.\n\nIf you already opened and enabled this module, scope it to android / Samsung Settings / launcher, then reboot for the hooks to take effect.", "请手动打开：\n· 桌面上的“LSPosed（Vector）”快捷方式，或\n· 下拉通知栏里的 LSPosed / Vector 入口。\n\n若已打开并启用了本模块，作用域勾选 android / 三星设置 / 桌面，然后重启手机使 Hook 生效。"),
    ("guide_label_root_userservice", "Root / UserService", "Root / UserService"),
    ("guide_root_note", "Root is the basis for one-tap silent authorization of overlay/projection; the app tries to request it automatically on launch.", "Root 是悬浮窗/投屏“一键静默授权”的基础；应用打开时会自动尝试拉起授权。"),
    ("guide_label_recording_permission", "Recording permission", "录音权限"),
    ("guide_recording_note", "Used to capture system playback audio; without it, video still streams but there is no sound. Can be skipped.", "用于采集系统播放音频；未授权仍可串流画面，只是没有声音。可跳过。"),
    ("guide_file_note", "Used to write logs / archives to the Downloads folder.", "用于把日志/压缩包写入下载目录。"),
    ("guide_label_screen_capture", "Screen capture (projection)", "屏幕采集（投屏）"),
    ("guide_screen_capture_note", "The system prompts for projection authorization when a connection starts; just confirm it there. No need to grant it in advance here.", "开始连接时系统会自动弹出投屏授权，确认即可；无需在此提前授权。"),
    ("debug_no_handshake", "No recent Moonlight connection handshake info", "尚无最近一次 Moonlight 连接握手信息"),
]

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
        if key in OVERRIDES:
            val = OVERRIDES[key][0 if is_en else 1]
        # literal % (not a placeholder) must be flagged non-formatted so AAPT
        # does not treat it as a format string
        if "%" in val and "%1$" not in val:
            lines.append('    <string name="%s" formatted="false">%s</string>' % (key, esc(val)))
        else:
            lines.append('    <string name="%s">%s</string>' % (key, esc(val)))
    for key, en, zh in EXTRA:
        val = en if is_en else zh
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
