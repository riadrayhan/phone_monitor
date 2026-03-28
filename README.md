# 🛡️ Employee Monitor – Complete Project

Corporate employee monitoring system for company-issued Android devices.

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
└── admin-panel/           ← Node.js Admin Dashboard
    ├── server.js
    ├── package.json
    └── public/
        ├── login.html
        └── dashboard.html
```

---

## ⚙️ Features

### Android App
| Feature | Details |
|---------|---------|
| 🎙️ Voice Recording | 30-second clips, sent as base64 to server |
| 📍 Real-time Location | GPS every 30 seconds, background enabled |
| 📱 App Usage Tracking | Aggregated every 60 seconds |
| 🔄 Boot Auto-start | Restarts automatically after device reboot |
| 🔔 Foreground Service | Persistent notification, can't be killed |

### Admin Panel
| Feature | Details |
|---------|---------|
| 📊 Dashboard | Live stats: online count, voice files, alerts |
| 👥 Employees | Table with status, last seen, data counts |
| 📍 Location | Full log + Google Maps link for last position |
| 📱 App Usage | Bar chart of time per app |
| 🎙️ Voice | Audio player + download per recording |
| 🔔 Alerts | Online/offline notifications |
| ⚡ Live Feed | Real-time event stream |

---

## 🚀 Setup

### Step 1 – Admin Panel (PC)

```bash
cd admin-panel
npm install
node server.js
```

Panel opens at: **http://localhost:3000**
Login: `admin` / `admin123`

> ⚠️ Change the password in `server.js` before deployment!

### Step 2 – Android App

1. Open `android-app/` in **Android Studio**
2. Wait for Gradle sync
3. Connect company device via USB
4. Run the app (▶️)

### Step 3 – Configure App on Device

1. Enter your PC's **local IP** as Server URL:
   ```
   http://192.168.1.XXX:3000
   ```
   > Find your PC IP: run `ipconfig` (Windows) or `ifconfig` (Mac/Linux)

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
express, socket.io, express-session, bcryptjs, fs-extra, moment
```

---

## 🔒 Security Notes

1. Change admin password in `server.js` line: `bcrypt.hashSync('admin123', 10)`
2. Run admin panel on company's internal network only
3. Use HTTPS + reverse proxy (nginx) in production
4. All employee data stored in `admin-panel/data/` folder

---

## 📡 Socket.IO Events

| Event | Direction | Data |
|-------|-----------|------|
| `employee_register` | App → Server | id, name, device |
| `location_update`   | App → Server | lat, lng, accuracy, speed |
| `app_usage`         | App → Server | packageName, appName, usageMs |
| `voice_data`        | App → Server | base64 audio, filename |
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
