/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.service;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.ProUtils;

import java.util.List;

/**
 * When a "reopen app" package is configured, this foreground service monitors whether that app
 * is running and relaunches it if it was closed (e.g. force-stopped or swiped away).
 */
public class ReopenAppService extends Service {

    private static final String CHANNEL_ID = "ReopenAppChannel";
    private static final int NOTIFICATION_ID = 114;
    private static final long CHECK_INTERVAL_MS = 8000;

    private Handler handler = new Handler(Looper.getMainLooper());
    private SettingsHelper settingsHelper;
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (settingsHelper == null) {
                settingsHelper = SettingsHelper.getInstance(ReopenAppService.this);
            }
            ServerConfig config = settingsHelper.getConfig();
            String pkg = config != null ? config.getReopenAppPackage() : null;
            if (pkg == null || pkg.trim().isEmpty()) {
                stopSelf();
                return;
            }
            pkg = pkg.trim();
            if (!isPackageRunning(pkg)) {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        Log.d(Const.LOG_TAG, "ReopenAppService: relaunched " + pkg);
                    }
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "ReopenAppService: failed to launch " + pkg, e);
                }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private boolean isPackageRunning(String packageName) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (packageName.equals(info.processName)) return true;
            if (info.pkgList != null) {
                for (String p : info.pkgList) {
                    if (packageName.equals(p)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        settingsHelper = SettingsHelper.getInstance(this);
        ServerConfig config = settingsHelper.getConfig();
        String pkg = config != null ? config.getReopenAppPackage() : null;
        if (pkg == null || pkg.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundIfNeeded();
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);
        return START_STICKY;
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "MDM Reopen App", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new NotificationCompat.Builder(this, CHANNEL_ID)
                : new NotificationCompat.Builder(this);
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setContentText("Monitoring app")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkRunnable);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
