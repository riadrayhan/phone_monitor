package com.company.monitor.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME   = "MonitorPrefs";
    private static final String KEY_URL     = "server_url";
    private static final String KEY_EMP_ID  = "employee_id";
    private static final String KEY_EMP_NAME= "employee_name";

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

    public String getServerUrl()    { return prefs.getString(KEY_URL, null); }
    public String getEmployeeId()   { return prefs.getString(KEY_EMP_ID, null); }
    public String getEmployeeName() { return prefs.getString(KEY_EMP_NAME, null); }
}
