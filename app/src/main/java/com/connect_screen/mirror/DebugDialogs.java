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
            handshakeInfo = context.getString(R.string.debug_no_handshake);
        }
        showReadonlyDebugDialog(context, context.getString(R.string.encoder_recent_handshake), handshakeInfo, context.getString(R.string.debug_moonlight_handshake_label), context.getString(R.string.debug_copied_handshake));
    }

    public static void showLastMoonlightControlInputDialog(Context context) {
        String controlInputInfo = State.lastMoonlightControlInputInfo;
        if (controlInputInfo == null || controlInputInfo.trim().isEmpty()) {
            controlInputInfo = context.getString(R.string.debug_no_control_input);
        }
        showReadonlyDebugDialog(context, context.getString(R.string.encoder_recent_input_stats), controlInputInfo, context.getString(R.string.debug_moonlight_control_label), context.getString(R.string.debug_copied_control));
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
                .setPositiveButton(context.getString(R.string.action_close), null)
                .setNeutralButton(context.getString(R.string.action_copy), (dialog, which) -> {
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
