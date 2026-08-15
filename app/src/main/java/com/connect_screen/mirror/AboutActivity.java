package com.connect_screen.mirror;

import static com.connect_screen.mirror.job.AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.connect_screen.mirror.job.FetchLogAndShare;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import rikka.shizuku.Shizuku;

public class AboutActivity extends AppCompatActivity {
    private static final String PROJECT_URL = "https://github.com/KanzakiK/libredex";
    private static final String RELEASES_URL = "https://github.com/KanzakiK/libredex/releases";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        UiCompat.applyStripedBackground(this);

        findViewById(R.id.aboutBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.githubButton).setOnClickListener(v -> openUrl(PROJECT_URL));
        findViewById(R.id.releasesButton).setOnClickListener(v -> openUrl(RELEASES_URL));

        TextView header = findViewById(R.id.header);
        header.setText("LibreDeX");

        TextView aboutContent = findViewById(R.id.aboutContent);
        aboutContent.setText(
                "LibreDeX 把 Galaxy Z Flip 5 变成不依赖 Miracast 的 DeX 串流主机：Moonlight 客户端配对后，通过 Sunshine 串流 fake DeX 桌面，并把鼠标、键盘、触摸等控制输入回流到设备。\n\n"
                        + "项目集成 LSPosed hooks（display flags / input / pointer / viewport / 层级修复）、Shizuku/UserService 系统接口、AudioPolicy loopback 音频采集，以及 Sunshine / Moonlight 兼容串流链路。\n\n"
                        + "本项目为 AGPL-3.0 开源项目；上游来源与修改说明见仓库 NOTICE.md。");

        TextView versionText = findViewById(R.id.versionText);
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            String androidVersion = android.os.Build.VERSION.RELEASE;
            versionText.setText("版本 " + versionName + " / Android " + androidVersion);
        } catch (Exception e) {
            versionText.setText("版本：未知");
        }

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!ShizukuUtils.hasShizukuStarted()) {
                    State.log("shizuku not started");
                    return false;
                }
                if (!ShizukuUtils.hasPermission()) {
                    State.log("ask shizuku permission");
                    Toast.makeText(AboutActivity.this, "导出故障日志需要 Shizuku 权限", Toast.LENGTH_SHORT).show();
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                    return false;
                }
                State.startNewJob(new FetchLogAndShare(AboutActivity.this));
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        header.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }
}
