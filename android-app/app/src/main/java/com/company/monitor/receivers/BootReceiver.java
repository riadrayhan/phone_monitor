package com.company.monitor.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.company.monitor.services.MonitoringService;
import com.company.monitor.utils.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            PreferenceManager pref = new PreferenceManager(context);
            // Only start if previously configured
            if (pref.getServerUrl() != null && !pref.getServerUrl().isEmpty()) {
                Intent serviceIntent = new Intent(context, MonitoringService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
