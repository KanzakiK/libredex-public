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
    private static final String PROJECT_URL = "https://github.com/KanzakiK/libredex-public";
    private static final String RELEASES_URL = "https://github.com/KanzakiK/libredex-public/releases";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        UiCompat.applyStripedBackground(this);

        findViewById(R.id.aboutBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.githubButton).setOnClickListener(v -> openUrl(PROJECT_URL));
        findViewById(R.id.releasesButton).setOnClickListener(v -> openUrl(RELEASES_URL));

        TextView header = findViewById(R.id.header);
        header.setText(getString(R.string.notify_sunshine_title));

        TextView aboutContent = findViewById(R.id.aboutContent);
        aboutContent.setText(getString(R.string.about_description));

        TextView versionText = findViewById(R.id.versionText);
        String androidVersion = android.os.Build.VERSION.RELEASE;
        versionText.setText(getString(R.string.about_version_fmt,
                BuildConfig.VERSION_NAME, BuildConfig.COMMIT, androidVersion));

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!ShizukuUtils.hasShizukuStarted()) {
                    State.log("shizuku not started");
                    return false;
                }
                if (!ShizukuUtils.hasPermission()) {
                    State.log("ask shizuku permission");
                    Toast.makeText(AboutActivity.this, getString(R.string.about_export_log_needs_shizuku), Toast.LENGTH_SHORT).show();
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
