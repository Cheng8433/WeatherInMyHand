package com.smog.weatherapp;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于与隐私页：
 *  - 关于：版本号（读 PackageManager）、天气数据来源（© 和风天气）；
 *  - 隐私政策全文 + 「撤回同意」（重置后下次冷启动会重新弹首启同意）。
 * 主题在 setContentView 前应用，与 MainActivity 一致。
 */
public class PrivacyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        TextView tvAboutVersion = findViewById(R.id.tvAboutVersion);
        tvAboutVersion.setText(versionName());

        findViewById(R.id.btnResetConsent).setOnClickListener(v -> {
            PrivacyStore.setAccepted(this, false);
            Toast.makeText(this, getString(R.string.about_reset_consent_done), Toast.LENGTH_SHORT).show();
        });
    }

    /** 读取应用版本号；异常时回退 "--"。 */
    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }
}
