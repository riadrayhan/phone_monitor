package com.company.monitor.services;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.media.MediaRecorder;
import android.os.*;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.company.monitor.R;
import com.company.monitor.utils.PreferenceManager;
import com.google.android.gms.location.*;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.util.*;

public class MonitoringService extends Service {

    private static final String TAG = "MonitoringService";
    private static final String CHANNEL_ID = "MonitoringChannel";
    private static final int NOTIFICATION_ID = 1001;

    // Intervals
    private static final long LOCATION_INTERVAL_MS     = 30_000;  // 30 sec
    private static final long APP_USAGE_INTERVAL_MS    = 60_000;  // 1 min
    private static final long VOICE_SEGMENT_MS         = 30_000;  // 30 sec voice clips

    private Socket socket;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private MediaRecorder mediaRecorder;
    private Handler mainHandler;
    private PreferenceManager prefManager;

    private String employeeId;
    private String employeeName;
    private String serverUrl;

    private boolean isRecording = false;
    private File currentRecordingFile;

    // App usage tracking
    private final Handler usageHandler = new Handler(Looper.getMainLooper());
    private final Runnable usageRunnable = new Runnable() {
        @Override
        public void run() {
            trackAppUsage();
            usageHandler.postDelayed(this, APP_USAGE_INTERVAL_MS);
        }
    };

    // Voice cycle
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private final Runnable voiceRunnable = new Runnable() {
        @Override
        public void run() {
            cycleVoiceRecording();
            voiceHandler.postDelayed(this, VOICE_SEGMENT_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefManager = new PreferenceManager(this);
        mainHandler  = new Handler(Looper.getMainLooper());
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        employeeId   = prefManager.getEmployeeId();
        employeeName = prefManager.getEmployeeName();
        serverUrl    = prefManager.getServerUrl();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        connectSocket();
        startLocationTracking();
        startVoiceRecordingCycle();
        usageHandler.post(usageRunnable);
    }

    // ─────────────────── SOCKET.IO ───────────────────

    private void connectSocket() {
        try {
            IO.Options options = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(Integer.MAX_VALUE)
                .setReconnectionDelay(3000)
                .build();

            socket = IO.socket(URI.create(serverUrl), options);

            socket.on(Socket.EVENT_CONNECT, args -> {
                Log.d(TAG, "Socket connected");
                try {
                    JSONObject info = new JSONObject();
                    info.put("employeeId",   employeeId);
                    info.put("employeeName", employeeName);
                    info.put("device",       Build.MODEL);
                    info.put("androidVersion", Build.VERSION.RELEASE);
                    socket.emit("employee_register", info);
                } catch (Exception e) {
                    Log.e(TAG, "Register error", e);
                }
            });

            socket.on(Socket.EVENT_DISCONNECT, args ->
                Log.w(TAG, "Socket disconnected – will reconnect"));

            socket.connect();
        } catch (Exception e) {
            Log.e(TAG, "Socket init error", e);
        }
    }

    private void emitEvent(String eventName, JSONObject data) {
        if (socket != null && socket.connected()) {
            socket.emit(eventName, data);
        }
    }

    // ─────────────────── LOCATION ───────────────────

    private void startLocationTracking() {
        LocationRequest req = new LocationRequest.Builder(LOCATION_INTERVAL_MS)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(15_000)
            .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                try {
                    JSONObject data = new JSONObject();
                    data.put("employeeId",   employeeId);
                    data.put("employeeName", employeeName);
                    data.put("latitude",     loc.getLatitude());
                    data.put("longitude",    loc.getLongitude());
                    data.put("accuracy",     loc.getAccuracy());
                    data.put("speed",        loc.getSpeed());
                    data.put("timestamp",    System.currentTimeMillis());
                    emitEvent("location_update", data);
                } catch (Exception e) {
                    Log.e(TAG, "Location emit error", e);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
        }
    }

    // ─────────────────── VOICE RECORDING ───────────────────

    private void startVoiceRecordingCycle() {
        voiceHandler.post(voiceRunnable);
    }

    private void cycleVoiceRecording() {
        if (isRecording) {
            stopAndSendRecording();
        }
        startRecording();
    }

    private void startRecording() {
        try {
            currentRecordingFile = new File(getCacheDir(),
                "voice_" + employeeId + "_" + System.currentTimeMillis() + ".3gp");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            Log.d(TAG, "Recording started: " + currentRecordingFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Recording start error", e);
            isRecording = false;
        }
    }

    private void stopAndSendRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording   = false;

                if (currentRecordingFile != null && currentRecordingFile.exists()) {
                    sendVoiceFile(currentRecordingFile);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Recording stop error", e);
        }
    }

    private void sendVoiceFile(File file) {
        new Thread(() -> {
            try {
                byte[] bytes = readFileToBytes(file);
                String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);

                JSONObject data = new JSONObject();
                data.put("employeeId",   employeeId);
                data.put("employeeName", employeeName);
                data.put("audioData",    base64);
                data.put("filename",     file.getName());
                data.put("timestamp",    System.currentTimeMillis());
                data.put("durationMs",   VOICE_SEGMENT_MS);
                emitEvent("voice_data", data);

                file.delete(); // cleanup
            } catch (Exception e) {
                Log.e(TAG, "Voice send error", e);
            }
        }).start();
    }

    private byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ─────────────────── APP USAGE ───────────────────

    private void trackAppUsage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now   = System.currentTimeMillis();
            long start = now - APP_USAGE_INTERVAL_MS;

            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, start, now);

            if (stats == null || stats.isEmpty()) return;

            for (UsageStats us : stats) {
                if (us.getTotalTimeInForeground() <= 0) continue;
                try {
                    JSONObject data = new JSONObject();
                    data.put("employeeId",    employeeId);
                    data.put("employeeName",  employeeName);
                    data.put("packageName",   us.getPackageName());
                    data.put("appName",       getAppName(us.getPackageName()));
                    data.put("usageMs",       us.getTotalTimeInForeground());
                    data.put("lastUsed",      us.getLastTimeUsed());
                    data.put("timestamp",     now);
                    emitEvent("app_usage", data);
                } catch (Exception e) {
                    Log.e(TAG, "App usage emit error", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "App usage tracking error", e);
        }
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager()
                .getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0))
                .toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    // ─────────────────── NOTIFICATION ───────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Employee Monitoring",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Company device monitoring service");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Company Monitoring Active")
            .setContentText("This device is being monitored as per employment agreement.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }

    // ─────────────────── LIFECYCLE ───────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationCallback != null) fusedLocationClient.removeLocationUpdates(locationCallback);
        usageHandler.removeCallbacks(usageRunnable);
        voiceHandler.removeCallbacks(voiceRunnable);
        stopAndSendRecording();
        if (socket != null) socket.disconnect();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
