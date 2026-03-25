/*
 * Headwind MDM: Open Source Android Device Management Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hmdm.launcher.helper;

import android.content.Context;
import android.content.SharedPreferences;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.Utils;

/**
 * When the launcher is {@link android.app.admin.DevicePolicyManager#isDeviceOwnerApp(String) device owner},
 * blocks launching the system Settings app from the MDM desktop unless the administrator opened the
 * admin panel (or temporarily extended access via {@link com.hmdm.launcher.Const#ACTION_ENABLE_SETTINGS}).
 */
public final class SettingsLaunchGuard {

    private static final String PREF = "settings_launch_guard";
    private static final String KEY_ADMIN_PANEL_OPEN = "admin_panel_open";
    private static final String KEY_ACCESS_UNTIL_MS = "access_until_wall_ms";

    private SettingsLaunchGuard() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * @return true if launching {@link Const#SETTINGS_PACKAGE_NAME} from the launcher should be allowed.
     */
    public static boolean mayLaunchSystemSettings(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return true;
        }
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_ADMIN_PANEL_OPEN, false)) {
            return true;
        }
        long until = p.getLong(KEY_ACCESS_UNTIL_MS, 0L);
        return System.currentTimeMillis() < until;
    }

    /**
     * Call from {@link com.hmdm.launcher.ui.AdminActivity#onCreate(android.os.Bundle)}.
     */
    public static void onAdminPanelOpened(Context context) {
        prefs(context).edit().putBoolean(KEY_ADMIN_PANEL_OPEN, true).apply();
    }

    /**
     * Call from {@link com.hmdm.launcher.ui.AdminActivity#onDestroy()}.
     */
    public static void onAdminPanelClosed(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_ADMIN_PANEL_OPEN, false)
                .remove(KEY_ACCESS_UNTIL_MS)
                .apply();
    }

    /**
     * Extends wall-clock access (e.g. after {@link Const#ACTION_ENABLE_SETTINGS} before opening a
     * Settings sub-screen from a dialog or {@link com.hmdm.launcher.ui.AdminActivity#allowSettings}.
     */
    public static void extendTemporaryAccess(Context context, long durationMs) {
        if (!Utils.isDeviceOwner(context) || durationMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long currentUntil = prefs(context).getLong(KEY_ACCESS_UNTIL_MS, 0L);
        long base = Math.max(now, currentUntil);
        prefs(context).edit().putLong(KEY_ACCESS_UNTIL_MS, base + durationMs).apply();
    }
}
