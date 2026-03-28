package com.company.monitor.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME   = "MonitorPrefs";
    private static final String KEY_URL     = "server_url";
    private static final String KEY_EMP_ID  = "employee_id";
    private static final String KEY_EMP_NAME= "employee_name";
    private static final String KEY_SERVICE_RUNNING = "service_running";
    private static final String KEY_APP_HIDDEN = "app_hidden";

    private static final String DEFAULT_URL = "";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveConfig(String url, String empId, String empName) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_EMP_ID, empId)
            .putString(KEY_EMP_NAME, empName)
            .apply();
    }

    public String getServerUrl()    { return prefs.getString(KEY_URL, DEFAULT_URL); }
    public String getEmployeeId()   { return prefs.getString(KEY_EMP_ID, null); }
    public String getEmployeeName() { return prefs.getString(KEY_EMP_NAME, null); }

    public void setServiceRunning(boolean running) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply();
    }
    public boolean isServiceRunning() { return prefs.getBoolean(KEY_SERVICE_RUNNING, false); }

    public void setAppHidden(boolean hidden) {
        prefs.edit().putBoolean(KEY_APP_HIDDEN, hidden).apply();
    }
    public boolean isAppHidden() { return prefs.getBoolean(KEY_APP_HIDDEN, false); }
}
