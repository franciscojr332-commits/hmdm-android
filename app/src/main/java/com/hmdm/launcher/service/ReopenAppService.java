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
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.SystemUtils;

import java.util.List;

/**
 * When a "reopen app" package is configured, this foreground service relaunches that app if it
 * is closed — WITHOUT stealing focus from whatever the user is currently doing.
 * <p>
 * Root cause of the old "pops over WhatsApp" bug: {@link ActivityManager#getRunningAppProcesses()},
 * {@code getRunningTasks()} and {@code getRunningServices()} all return only the CALLER'S own data
 * on API 26+, so we could never see the target alive and kept firing {@code startActivity}, which
 * always foregrounds. {@code startActivity} cannot launch an activity "in the background" on Android.
 * <p>
 * New policy: foreground is detected via {@link UsageStatsManager} (the only reliable API for
 * third-party apps). The target activity is relaunched ONLY when (a) the screen is on and (b) the
 * user is sitting on our own launcher/home — never while a third-party app (e.g. WhatsApp) is in
 * front, and never to wake the device. If usage-stats is unreadable we stay conservative and skip.
 * The target's own location foreground service is declared {@code stopWithTask="false"}, so its
 * background work survives the user swiping the app away regardless of this activity relaunch.
 */
public class ReopenAppService extends Service {

    private static final String CHANNEL_ID = "ReopenAppChannel";
    private static final int NOTIFICATION_ID = 114;
    private static final long CHECK_INTERVAL_MS = 8000;
    // Prevents a "reopen loop" that can steal focus repeatedly if the app is already visible
    // or if process-detection is returning false negatives.
    private static final long MIN_RELAUNCH_INTERVAL_MS = 30000;

    private Handler handler = new Handler(Looper.getMainLooper());
    private SettingsHelper settingsHelper;
    private long lastRelaunchElapsedMs = 0;
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            try {
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

                String foreground = getForegroundPackage();

                // 1) Target already in front -> alive, nothing to do.
                if (pkg.equals(foreground)) {
                    return;
                }
                // 2) Best-effort process/service check (unreliable on API 26+, but cheap).
                if (isPackageRunning(pkg)) {
                    return;
                }

                // 3) Target looks closed. Relaunch ONLY when it won't interrupt the user:
                //    - screen must be on (never wake the device), AND
                //    - the user must be on our own launcher / home (foreground == us),
                //      NOT inside a third-party app like WhatsApp.
                //    foreground == null means usage-stats unreadable -> stay conservative, skip
                //    (we prefer "never steal focus" over "always reopen").
                if (!isScreenInteractive()) {
                    return;
                }
                boolean onOurHome = getPackageName().equals(foreground);
                if (!onOurHome) {
                    return;
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastRelaunchElapsedMs < MIN_RELAUNCH_INTERVAL_MS) {
                    return;
                }
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        lastRelaunchElapsedMs = now;
                        Log.d(Const.LOG_TAG, "ReopenAppService: relaunched " + pkg + " from home");
                    }
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "ReopenAppService: failed to launch " + pkg, e);
                }
            } finally {
                handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS);
            }
        }
    };

    /**
     * Accurate current foreground package via UsageStatsManager (the only API that works for
     * third-party apps on API 26+). Returns the package of the most recent foreground event in
     * the last few minutes, or null if usage-stats access is missing/unreadable.
     */
    private String getForegroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long end = System.currentTimeMillis();
            long begin = end - 10 * 60 * 1000L; // look back 10 min, keep the latest fg event
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) {
                return null;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            String fg = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fg = event.getPackageName();
                }
            }
            return fg;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ReopenAppService: usage-stats foreground read failed", e);
            return null;
        }
    }

    private boolean isScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isPackageRunning(String packageName) {
        // If the target is already visible in the foreground, treat it as "alive".
        // This prevents false negatives that would cause a relaunch loop.
        if (isPackageInForeground(packageName)) {
            return true;
        }

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (processMatchesPackage(info, packageName)) {
                    return true;
                }
            }
        }
        // Process list is often incomplete (API 26+). Target may still run a foreground service
        // with no visible activity — do not treat that as "stopped".
        try {
            List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(256);
            if (services != null) {
                for (ActivityManager.RunningServiceInfo si : services) {
                    if (si.service != null && packageName.equals(si.service.getPackageName())) {
                        return true;
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(Const.LOG_TAG, "ReopenAppService: getRunningServices not allowed", e);
        }
        return false;
    }

    private boolean isPackageInForeground(String packageName) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;

            // GET_TASKS allows retrieving the top task; we use it to avoid relaunching when the app
            // is already the current foreground package.
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return false;
            }

            ActivityManager.RunningTaskInfo topTask = tasks.get(0);
            if (topTask == null || topTask.topActivity == null) {
                return false;
            }

            return packageName.equals(topTask.topActivity.getPackageName());
        } catch (SecurityException e) {
            Log.w(Const.LOG_TAG, "ReopenAppService: getRunningTasks not allowed", e);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ReopenAppService: foreground check failed", e);
        }
        return false;
    }

    /**
     * Matches main process, isolated (:remote) processes, and pkgList entries.
     */
    private static boolean processMatchesPackage(ActivityManager.RunningAppProcessInfo info,
                                                 String packageName) {
        if (info.processName != null) {
            if (packageName.equals(info.processName)) {
                return true;
            }
            if (info.processName.startsWith(packageName + ":")) {
                return true;
            }
        }
        if (info.pkgList != null) {
            for (String p : info.pkgList) {
                if (packageName.equals(p)) {
                    return true;
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
        // Foreground detection relies on UsageStatsManager; grant ourselves the appop
        // (works when we are device owner / system-privileged). Harmless if it fails.
        try {
            SystemUtils.autoSetUsageStatsPermission(this, getPackageName());
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ReopenAppService: could not auto-grant usage stats", e);
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
