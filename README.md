# 🛡️ Employee Monitor – Complete Project

Corporate employee monitoring system for company-issued Android devices.

## 🌐 Live Admin Panel

**🔗 [https://admin-panel-brown-five.vercel.app](https://admin-panel-brown-five.vercel.app)**

## 📁 Project Structure

```
employee-monitor/
├── android-app/          ← Java Android App (APK)
│   └── app/src/main/
│       ├── java/com/company/monitor/
│       │   ├── MainActivity.java
│       │   ├── services/MonitoringService.java
│       │   ├── receivers/BootReceiver.java
│       │   └── utils/PreferenceManager.java
│       ├── res/layout/activity_main.xml
│       └── AndroidManifest.xml
│
└── admin-panel/           ← Node.js Admin Dashboard (Deployed on Vercel)
    ├── server.js
    ├── vercel.json
    ├── package.json
    └── public/
        └── dashboard.html
```

---

## ⚙️ Features

### Android App
| Feature | Details |
|---------|---------|
| 🎙️ Voice Recording | Real-time audio streaming + 30-sec WAV saves |
| 📍 Real-time Location | GPS every 30 seconds, background enabled |
| 📱 App Usage Tracking | Aggregated every 60 seconds |
| 🔄 Boot Auto-start | Restarts automatically after device reboot |
| 🔔 Foreground Service | Persistent notification, can't be killed |
| 🛡️ Device Admin | Prevents uninstall, app hide/unhide from admin |

### Admin Panel
| Feature | Details |
|---------|---------|
| 📊 Dashboard | Live stats: online count, voice files, alerts |
| 👥 Employees | Table with status, last seen, data counts |
| 📍 Location | Full log + Google Maps link for last position |
| 📱 App Usage | Bar chart of time per app |
| 🎙️ Voice | Audio player + download per recording |
| � Live Audio | Real-time audio streaming from device mic |
| 🔔 Alerts | Online/offline notifications |
| ⚡ Live Feed | Real-time event stream |
| 👁️ Hide/Unhide | Remotely hide or show app on device |

---

## 🚀 Setup

### Step 1 – Admin Panel

**Option A – Cloud (Vercel):**
Already deployed at: **https://admin-panel-brown-five.vercel.app**

**Option B – Local:**
```bash
cd admin-panel
npm install
node server.js
```
Panel opens at: **http://localhost:3000** (no login required)

### Step 2 – Android App

1. Open `android-app/` in **Android Studio**
2. Wait for Gradle sync
3. Connect company device via USB
4. Run the app (▶️)

### Step 3 – Configure App on Device

1. Enter the **Server URL**:
   - Cloud: `https://admin-panel-brown-five.vercel.app`
   - Local: `http://192.168.1.XXX:3000`

2. Enter Employee ID and Name
3. Tap **Start Monitoring**
4. Grant **all permissions** when prompted
5. Go to Settings → Apps → Special Access → **Usage Access** → Enable for Company Monitor

---

## 🔧 Dependencies

### Android
```
implementation 'com.google.android.gms:play-services-location:21.3.0'
implementation 'io.socket:socket.io-client:2.1.0'
implementation 'androidx.cardview:cardview:1.0.0'
```

### Node.js
```
express, socket.io, cors, fs-extra
```

---

## 🔒 Security Notes

1. Run admin panel on company's internal network or use Vercel cloud
2. Use HTTPS (Vercel provides it automatically)
3. All employee data stored in-memory (serverless) or `admin-panel/data/` (local)

---

## 📡 Socket.IO Events

| Event | Direction | Data |
|-------|-----------|------|
| `employee_register` | App → Server | id, name, device |
| `location_update`   | App → Server | lat, lng, accuracy, speed |
| `app_usage`         | App → Server | packageName, appName, usageMs |
| `voice_data`        | App → Server | base64 audio WAV, filename |
| `audio_stream`       | App → Server → Admin | real-time PCM audio chunks |
| `admin_hide_app`     | Admin → Server → App | hide/unhide command |
| `command_hide_app`   | Server → App | hide/unhide instruction |
| `app_hidden_changed` | Server → Admin | hidden status update |
| `live_location`     | Server → Admin | location event |
| `live_app_usage`    | Server → Admin | usage event |
| `new_voice_recording` | Server → Admin | file info |
| `new_alert`         | Server → Admin | alert object |
| `stats_update`      | Server → Admin | counts |

---

## ❓ Troubleshooting

**App can't connect to server?**
- Make sure phone and PC are on the same WiFi
- Disable Windows Firewall or allow port 3000
- Double-check IP address

**Background service stops?**
- Disable battery optimization for the app
- Settings → Battery → App Battery Management → Company Monitor → Unrestricted

**No app usage data?**
- Enable Usage Access permission in Special App Permissions settings
