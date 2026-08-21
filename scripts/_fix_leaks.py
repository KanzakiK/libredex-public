# -*- coding: utf-8 -*-
"""One-shot: fix remaining UI leaks + UserService mojibake logs."""
import io

BASE = r"D:\dex_work\app\src\main\java\com\connect_screen\mirror"


def edit(rel, old, new):
    p = BASE + "\\" + rel
    s = io.open(p, encoding="utf-8").read()
    n = s.count(old)
    assert n == 1, f"{rel}: {n} of {old[:50]!r}"
    io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(old, new))
    print(rel, "ok")


G = "getString(R.string."
G2 = "context.getString(R.string."
G3 = "State.getContext().getString(R.string."

# ScreenSettingsActivity: DPI confirm dialog + system default item
edit("ScreenSettingsActivity.java",
     'showConfirmOrRevertDialog(\n                    "确认 DPI",\n                    String.format(java.util.Locale.US, "保留屏幕 %d 的新 DPI %d？5 秒内未确认会恢复。", displayId, dpi),',
     'showConfirmOrRevertDialog(\n                    ' + G + 'screen_confirm_dpi),\n                    ' + G + 'screen_confirm_dpi_msg_fmt, displayId, dpi),')
edit("ScreenSettingsActivity.java",
     'items[0] = "使用系统默认";',
     'items[0] = ' + G + 'screen_use_system_default);')

# FetchLogAndShare: chooser title
edit("job/FetchLogAndShare.java",
     'shareIntent, "分享日志压缩包"));',
     'shareIntent, ' + G3 + 'log_share_archive)));')

# ProjectViaDp: error status text
edit("job/ProjectViaDp.java",
     'TAG + ": DP DeX 需要以 root 启动 Shizuku");',
     'TAG + ": " + ' + G3 + 'dp_dex_need_root_shizuku));')

# StartSunshineService: YieldException message (not UI, english literal)
edit("job/StartSunshineService.java",
     'throw new YieldException("等待用户授予投屏权限");',
     'throw new YieldException("Waiting for user to grant projection permission");')

# SunshineServer: auto-fallback message appended to errorMessage (UI)
edit("job/SunshineServer.java",
     'errorMessage = errorMessage\n                    + "\\n\\n已自动回退到 H.264/AVC，请重新连接 Moonlight。";',
     'errorMessage = errorMessage\n                    + ' + G2 + 'sunshine_auto_fallback);')

# AutoRotate: RuntimeException messages (english literals)
edit("job/AutoRotateAndScaleForMoonlight.java",
     'throw new RuntimeException("无法获取 EGL 显示连接");',
     'throw new RuntimeException("Cannot get EGL display connection");')
edit("job/AutoRotateAndScaleForMoonlight.java",
     'throw new RuntimeException("无法初始化 EGL");',
     'throw new RuntimeException("Cannot initialize EGL");')

# ServiceUtils: IllegalStateException message (english literal)
edit("shizuku/ServiceUtils.java",
     'throw new IllegalStateException("ServiceUtils 未初始化，请先调用 initWithShizuku()");',
     'throw new IllegalStateException("ServiceUtils not initialized, call initWithShizuku() first");')

# UserService: mojibake log literals -> english
edit("shizuku/UserService.java",
     'Ln.d("getDisplayToken: 浣跨敤 physicalDisplayIds[0]=" + physicalDisplayIds[0]);',
     'Ln.d("getDisplayToken: using physicalDisplayIds[0]=" + physicalDisplayIds[0]);')
edit("shizuku/UserService.java",
     'Ln.d("getDisplayToken: physicalDisplayIds 涓虹┖, 浣跨敤 getBuiltInDisplay");',
     'Ln.d("getDisplayToken: physicalDisplayIds empty, using getBuiltInDisplay");')
edit("shizuku/UserService.java",
     'Ln.d("createExternalMirror [API30] 鍑嗗璋冪敤 SurfaceControl.createDisplay...");',
     'Ln.d("createExternalMirror [API30] about to call SurfaceControl.createDisplay...");')
edit("shizuku/UserService.java",
     'Ln.d("createExternalMirror [API30] setDisplaySurface 瀹屾垚");',
     'Ln.d("createExternalMirror [API30] setDisplaySurface done");')
edit("shizuku/UserService.java",
     'Ln.d("createExternalMirror [API30] setDisplayProjection 瀹屾垚");',
     'Ln.d("createExternalMirror [API30] setDisplayProjection done");')
edit("shizuku/UserService.java",
     'Ln.d("createExternalMirror [API30] setDisplayLayerStack 瀹屾垚");',
     'Ln.d("createExternalMirror [API30] setDisplayLayerStack done");')
