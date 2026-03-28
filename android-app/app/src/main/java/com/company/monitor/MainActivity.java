package com.company.monitor;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.company.monitor.services.MonitoringService;
import com.company.monitor.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private EditText etServerUrl, etEmployeeId, etEmployeeName;
    private Button btnStartMonitoring;
    private TextView tvStatus;
    private PreferenceManager prefManager;

    private String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefManager = new PreferenceManager(this);
        initViews();
        showConsentDialog();
    }

    private void initViews() {
        etServerUrl    = findViewById(R.id.etServerUrl);
        etEmployeeId   = findViewById(R.id.etEmployeeId);
        etEmployeeName = findViewById(R.id.etEmployeeName);
        btnStartMonitoring = findViewById(R.id.btnStartMonitoring);
        tvStatus       = findViewById(R.id.tvStatus);

        // Load saved prefs
        if (prefManager.getServerUrl() != null) {
            etServerUrl.setText(prefManager.getServerUrl());
            etEmployeeId.setText(prefManager.getEmployeeId());
            etEmployeeName.setText(prefManager.getEmployeeName());
        }

        btnStartMonitoring.setOnClickListener(v -> {
            String url  = etServerUrl.getText().toString().trim();
            String id   = etEmployeeId.getText().toString().trim();
            String name = etEmployeeName.getText().toString().trim();

            if (url.isEmpty() || id.isEmpty() || name.isEmpty()) {
                Toast.makeText(this, "Shob field fill koroon", Toast.LENGTH_SHORT).show();
                return;
            }

            prefManager.saveConfig(url, id, name);
            checkAndRequestPermissions();
        });
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Employee Monitoring Notice")
            .setMessage(
                "⚠️ IMPORTANT NOTICE\n\n" +
                "This is a company-issued device enrolled in the Employee Monitoring Program.\n\n" +
                "The following data will be collected:\n" +
                "• Voice/audio recordings during work hours\n" +
                "• Real-time GPS location\n" +
                "• App usage statistics\n\n" +
                "All data is stored securely on company servers and accessible only to authorized administrators.\n\n" +
                "By tapping 'I Agree', you acknowledge and consent to this monitoring as per your employment agreement."
            )
            .setPositiveButton("I Agree", (d, w) -> d.dismiss())
            .setNegativeButton("Exit", (d, w) -> finish())
            .setCancelable(false)
            .show();
    }

    private void checkAndRequestPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                // Background location asked separately on Android 10+
                if (perm.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                    continue;
                missing.add(perm);
            }
        }

        if (missing.isEmpty()) {
            requestUsageStatsPermission();
        } else {
            ActivityCompat.requestPermissions(this,
                missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void requestUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            new AlertDialog.Builder(this)
                .setTitle("Usage Access Permission Required")
                .setMessage("App usage tracking er jonno 'Usage Access' permission dorkar. Settings e giye enable koroon.")
                .setPositiveButton("Settings e jao", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                })
                .setNegativeButton("Skip", (d, w) -> startMonitoringService())
                .show();
        } else {
            startMonitoringService();
        }
    }

    private boolean hasUsageStatsPermission() {
        try {
            android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()
            );
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, MonitoringService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        tvStatus.setText("✅ Monitoring Active – Service running in background");
        btnStartMonitoring.setEnabled(false);
        Toast.makeText(this, "Monitoring shuru hoyeche!", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            requestUsageStatsPermission();
        }
    }
}
