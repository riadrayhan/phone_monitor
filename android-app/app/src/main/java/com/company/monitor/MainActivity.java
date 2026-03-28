package com.company.monitor;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.company.monitor.receivers.AdminReceiver;
import com.company.monitor.services.MonitoringService;
import com.company.monitor.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int DEVICE_ADMIN_REQUEST_CODE = 200;
    private EditText etServerUrl, etEmployeeName;
    private Button btnStartMonitoring;
    private TextView tvStatus;
    private PreferenceManager prefManager;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

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
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        initViews();

        // Block everything until all permissions granted
        disableUI();
        showConsentDialog();
    }

    private void disableUI() {
        btnStartMonitoring.setEnabled(false);
        etServerUrl.setEnabled(false);
        etEmployeeName.setEnabled(false);
        tvStatus.setText("⛔ Shob permission dite hobe!");
    }

    private void enableUI() {
        btnStartMonitoring.setEnabled(true);
        etServerUrl.setEnabled(true);
        etEmployeeName.setEnabled(true);
        tvStatus.setText("✅ All permissions granted! Ready to start.");
    }

    private void initViews() {
        etServerUrl    = findViewById(R.id.etServerUrl);
        etEmployeeName = findViewById(R.id.etEmployeeName);
        btnStartMonitoring = findViewById(R.id.btnStartMonitoring);
        tvStatus       = findViewById(R.id.tvStatus);

        // Load saved prefs
        String savedUrl = prefManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }
        String savedName = prefManager.getEmployeeName();
        if (savedName != null && !savedName.isEmpty()) {
            etEmployeeName.setText(savedName);
        } else {
            etEmployeeName.setText(Build.MODEL);
        }

        btnStartMonitoring.setOnClickListener(v -> {
            String url  = etServerUrl.getText().toString().trim();
            String name = etEmployeeName.getText().toString().trim();

            if (url.isEmpty()) {
                Toast.makeText(this, "Server URL dite hobe!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.isEmpty()) {
                name = Build.MODEL;
            }

            // Auto-generate device ID from Android ID
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

            prefManager.saveConfig(url, deviceId, name);
            startMonitoringService();
        });
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Monitoring Notice")
            .setMessage(
                "⚠️ IMPORTANT NOTICE\n\n" +
                "This device will be monitored.\n\n" +
                "Data collected:\n" +
                "• Voice/audio recordings\n" +
                "• Real-time GPS location\n" +
                "• App usage statistics\n\n" +
                "You MUST grant ALL permissions to use this app.\n\n" +
                "By tapping 'I Agree', you consent to this monitoring."
            )
            .setPositiveButton("I Agree", (d, w) -> {
                d.dismiss();
                startPermissionChain();
            })
            .setNegativeButton("Exit", (d, w) -> finish())
            .setCancelable(false)
            .show();
    }

    // ─── STRICT permission chain: step by step, no skip ───

    private void startPermissionChain() {
        checkAndRequestPermissions();
    }

    private boolean allRuntimePermissionsGranted() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (perm.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                continue;
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void checkAndRequestPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (perm.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                continue;
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        if (missing.isEmpty()) {
            // Step 2: Usage Stats
            checkUsageStatsPermission();
        } else {
            ActivityCompat.requestPermissions(this,
                missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void checkUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Usage Access Required")
                .setMessage("Ei permission MUST dite hobe! Settings e giye enable korun.\n\nNa dile app use korte parben na.")
                .setPositiveButton("Settings e jao", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                })
                .setCancelable(false)
                .show();
        } else {
            // Step 3: Device Admin
            checkDeviceAdmin();
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

    private void checkDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Device Admin Required")
                .setMessage("Device Admin permission MUST dite hobe!\n\nEta chara app kaj korbe na.")
                .setPositiveButton("Enable korun", (d, w) -> {
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Device Admin enable korte hobe. Na korle app use korte parben na.");
                    startActivityForResult(intent, DEVICE_ADMIN_REQUEST_CODE);
                })
                .setCancelable(false)
                .show();
        } else {
            // Step 4: Battery
            checkBatteryOptimization();
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("⚠️ Battery Optimization Off Required")
                    .setMessage("Background e 100% kaj korar jonno battery optimization off korte MUST hobe!")
                    .setPositiveButton("OK", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setCancelable(false)
                    .show();
            } else {
                allPermissionsDone();
            }
        } else {
            allPermissionsDone();
        }
    }

    private void allPermissionsDone() {
        enableUI();
        Toast.makeText(this, "✅ Shob permission granted! Now Start Monitoring e click korun.", Toast.LENGTH_LONG).show();
    }

    private void startMonitoringService() {
        // Verify critical permissions before starting
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mic permission dorkar! Permission diye abar try korun.", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission dorkar! Permission diye abar try korun.", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE);
            return;
        }

        Intent serviceIntent = new Intent(this, MonitoringService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        prefManager.setServiceRunning(true);
        tvStatus.setText("✅ Monitoring Active – Service running in background");
        btnStartMonitoring.setEnabled(false);
        Toast.makeText(this, "Monitoring shuru hoyeche!", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if any were denied
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                // Check if permanently denied - send to App Settings
                boolean anyPermanentlyDenied = false;
                for (String perm : permissions) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                        anyPermanentlyDenied = true;
                        break;
                    }
                }

                new AlertDialog.Builder(this)
                    .setTitle("⛔ Permission Denied!")
                    .setMessage("SHOB permission dite MUST hobe!\n\nNa dile app use korte parben na.\n\nAbar try korun.")
                    .setPositiveButton("Abar try kori", (d, w) -> checkAndRequestPermissions())
                    .setNeutralButton("App Settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setCancelable(false)
                    .show();
            } else {
                // All runtime permissions granted, continue chain
                checkUsageStatsPermission();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DEVICE_ADMIN_REQUEST_CODE) {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Toast.makeText(this, "✅ Device Admin activated!", Toast.LENGTH_SHORT).show();
                checkBatteryOptimization();
            } else {
                // Not activated - force again
                new AlertDialog.Builder(this)
                    .setTitle("⛔ Device Admin Required!")
                    .setMessage("Device Admin MUST enable korte hobe!\n\nNa korle app use korte parben na.")
                    .setPositiveButton("Abar try kori", (d, w) -> checkDeviceAdmin())
                    .setCancelable(false)
                    .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check entire permission chain when returning from Settings
        if (prefManager.isServiceRunning()) {
            tvStatus.setText("✅ Monitoring Active – Service running in background");
            btnStartMonitoring.setEnabled(false);
            etServerUrl.setEnabled(false);
            etEmployeeName.setEnabled(false);
            return;
        }

        // Check if all permissions are now granted
        if (allRuntimePermissionsGranted() && hasUsageStatsPermission()
                && devicePolicyManager.isAdminActive(adminComponent)) {
            // Check battery too
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    allPermissionsDone();
                    return;
                }
            } else {
                allPermissionsDone();
                return;
            }
        }
    }
}
