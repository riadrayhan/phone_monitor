# ── Default Android rules ────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# ── Socket.IO client ────────────────────────────────────────────
-keep class io.socket.** { *; }
-keep class io.socket.client.** { *; }
-keep class io.socket.engineio.** { *; }
-keep class io.socket.parser.** { *; }
-dontwarn io.socket.**

# ── OkHttp / Okio (used by socket.io internally) ─────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Google Play Services Location ────────────────────────────────
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ── Keep our app classes ─────────────────────────────────────────
-keep class com.company.monitor.** { *; }
-keep class com.company.monitor.services.** { *; }
-keep class com.company.monitor.receivers.** { *; }
-keep class com.company.monitor.models.** { *; }

# ── JSON ─────────────────────────────────────────────────────────
-keep class org.json.** { *; }
-dontwarn org.json.**

# ── Suppress common warnings ─────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**
