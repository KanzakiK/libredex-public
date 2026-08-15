package com.connect_screen.mirror;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class DebugDialogs {
    private DebugDialogs() {
    }

    public static void showLastMoonlightHandshakeDialog(Context context) {
        String handshakeInfo = State.lastMoonlightHandshakeInfo;
        if (handshakeInfo == null || handshakeInfo.trim().isEmpty()) {
            handshakeInfo = "尚无最近一次 Moonlight 连接握手信息";
        }
        showReadonlyDebugDialog(context, "最近握手信息", handshakeInfo, "Moonlight 握手信息", "已复制握手信息");
    }

    public static void showLastMoonlightControlInputDialog(Context context) {
        String controlInputInfo = State.lastMoonlightControlInputInfo;
        if (controlInputInfo == null || controlInputInfo.trim().isEmpty()) {
            controlInputInfo = "尚无最近一次 Moonlight 控制输入统计";
        }
        showReadonlyDebugDialog(context, "最近控制输入统计", controlInputInfo, "Moonlight 控制输入统计", "已复制控制输入统计");
    }

    public static void showReadonlyDebugDialog(Context context,
                                               String title,
                                               String content,
                                               String clipLabel,
                                               String copiedToastText) {
        final String textToCopy = content;
        new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    ClipboardManager clipboardManager =
                            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboardManager != null) {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText(clipLabel, textToCopy));
                        Toast.makeText(context, copiedToastText, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
