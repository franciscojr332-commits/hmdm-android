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

package com.hmdm.launcher.worker;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.ack.AckExecutionRequest;
import com.hmdm.launcher.ack.AckQueue;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.DownloadTable;
import com.hmdm.launcher.helper.ConfigUpdater;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.Download;
import com.hmdm.launcher.json.PushMessage;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.InstallUtils;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.SystemUtils;
import com.hmdm.launcher.util.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

public class PushNotificationProcessor {

    /**
     * Entry point: dispatcher + ACK pipeline.
     * F2 changes:
     *  - Each handler returns ExecutionResult (ok / fail with code+message).
     *  - AckQueue receives execution ACK after handler completes.
     *  - Delivery ACK is sent by caller (PushLongPollingService) right after receipt.
     */
    public static void process(PushMessage message, Context context) {
        final int messageId = message.getId() != null ? message.getId() : -1;
        RemoteLogger.log(context, Const.LOG_INFO,
                "Got Push Message id=" + messageId + " type=" + message.getMessageType());

        String type = message.getMessageType();

        // Synchronous-ish handlers (Android API itself queues startActivity/broadcast).
        // We treat them as success immediately after dispatch.
        if (PushMessage.TYPE_CONFIG_UPDATED.equals(type)) {
            ConfigUpdater.notifyConfigUpdate(context);
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_RUN_APP.equals(type)) {
            runApplication(context, message.getPayloadJSON());
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_BROADCAST.equals(type)) {
            sendBroadcast(context, message.getPayloadJSON());
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_PERMISSIVE_MODE.equals(type)) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(Const.ACTION_PERMISSIVE_MODE));
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_EXIT_KIOSK.equals(type)) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(Const.ACTION_EXIT_KIOSK));
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_ADMIN_PANEL.equals(type)) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(Const.ACTION_ADMIN_PANEL));
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        } else if (PushMessage.TYPE_NOTIFICATION.equals(type)) {
            showNotificationToUser(context, message.getPayloadJSON());
            sendExecAck(context, messageId, ExecutionResult.ok());
            return;
        }

        // Background handlers — async via AsyncTask (F3 will move to SingleThreadExecutor + outbox).
        // Each handler returns ExecutionResult; AckQueue receives it.
        if (PushMessage.TYPE_UNINSTALL_APP.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, uninstallApplication(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_DELETE_FILE.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, deleteFile(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_DELETE_DIR.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, deleteDir(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_PURGE_DIR.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, purgeDir(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_RUN_COMMAND.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, runCommand(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_REBOOT.equals(type)) {
            // ACK BEFORE reboot — once reboot triggers, process dies and ACK won't make it
            sendExecAck(context, messageId, ExecutionResult.ok());
            AsyncTask.execute(() -> reboot(context));
            return;
        } else if (PushMessage.TYPE_CLEAR_DOWNLOADS.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, clearDownloads(context)));
            return;
        } else if (PushMessage.TYPE_INTENT.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, callIntent(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_GRANT_PERMISSIONS.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, grantPermissions(context, message.getPayloadJSON())));
            return;
        } else if (PushMessage.TYPE_CLEAR_APP_DATA.equals(type)) {
            AsyncTask.execute(() -> sendExecAck(context, messageId, clearAppData(context, message.getPayloadJSON())));
            return;
        }

        // Unknown type — broadcast to plugins (best-effort, ack as ok)
        Intent intent = new Intent(Const.INTENT_PUSH_NOTIFICATION_PREFIX + type);
        JSONObject jsonObject = message.getPayloadJSON();
        if (jsonObject != null) {
            intent.putExtra(Const.INTENT_PUSH_NOTIFICATION_EXTRA, jsonObject.toString());
        }
        context.sendBroadcast(intent);
        sendExecAck(context, messageId, ExecutionResult.ok());
    }

    private static void sendExecAck(Context context, int messageId, ExecutionResult result) {
        if (messageId <= 0) return;
        long executedAt = System.currentTimeMillis();
        AckQueue.getInstance(context).enqueueExecution(
                messageId, executedAt,
                result.success ? AckExecutionRequest.STATUS_OK : AckExecutionRequest.STATUS_FAILED,
                result.failureCode, result.failureMessage);
    }

    private static void runApplication(Context context, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            String pkg = payload.optString("pkg", null);
            if (pkg == null || pkg.trim().isEmpty()) {
                RemoteLogger.log(context, Const.LOG_WARN, "runApp: missing pkg");
                return;
            }
            pkg = pkg.trim();

            boolean background = payload.optBoolean("background", false);
            String component = payload.optString("component", null);
            if (component != null) {
                component = component.trim();
            }

            if (background && component != null && !component.isEmpty()) {
                // Run app in background: start the specified Service
                Intent serviceIntent = new Intent();
                serviceIntent.setComponent(new ComponentName(pkg, component));
                String action = payload.optString("action", null);
                if (action != null) {
                    serviceIntent.setAction(action);
                }
                String data = payload.optString("data", null);
                if (data != null) {
                    try {
                        serviceIntent.setData(Uri.parse(data));
                    } catch (Exception e) {
                        Log.w(Const.LOG_TAG, "runApp background: invalid data URI", e);
                    }
                }
                JSONObject extras = payload.optJSONObject("extra");
                if (extras != null) {
                    Iterator<String> keys = extras.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = extras.get(key);
                        if (value instanceof String) {
                            serviceIntent.putExtra(key, (String) value);
                        } else if (value instanceof Integer) {
                            serviceIntent.putExtra(key, ((Integer) value).intValue());
                        } else if (value instanceof Float) {
                            serviceIntent.putExtra(key, ((Float) value).floatValue());
                        } else if (value instanceof Boolean) {
                            serviceIntent.putExtra(key, ((Boolean) value).booleanValue());
                        }
                    }
                }
                try {
                    context.startService(serviceIntent);
                    RemoteLogger.log(context, Const.LOG_INFO, "runApp: started service " + pkg + "/" + component);
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "runApp: failed to start service " + pkg + "/" + component, e);
                    RemoteLogger.log(context, Const.LOG_WARN, "runApp background failed: " + e.getMessage());
                }
                return;
            }

            // Foreground: start launcher activity
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                String action = payload.optString("action", null);
                if (action != null) {
                    launchIntent.setAction(action);
                }
                String data = payload.optString("data", null);
                if (data != null) {
                    try {
                        launchIntent.setData(Uri.parse(data));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                JSONObject extras = payload.optJSONObject("extra");
                if (extras != null) {
                    Iterator<String> keys = extras.keys();
                    String key;
                    while (keys.hasNext()) {
                        key = keys.next();
                        Object value = extras.get(key);
                        if (value instanceof String) {
                            launchIntent.putExtra(key, (String) value);
                        } else if (value instanceof Integer) {
                            launchIntent.putExtra(key, ((Integer) value).intValue());
                        } else if (value instanceof Float) {
                            launchIntent.putExtra(key, ((Float) value).floatValue());
                        } else if (value instanceof Boolean) {
                            launchIntent.putExtra(key, ((Boolean) value).booleanValue());
                        }
                    }
                }

                // These magic flags are found in the source code of the default Android launcher
                // These flags preserve the app activity stack (otherwise a launch activity appears at the top which is not correct)
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launchIntent);
            } else {
                RemoteLogger.log(context, Const.LOG_WARN, "runApp: no launcher activity for " + pkg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendBroadcast(Context context, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            String pkg = payload.optString("pkg", null);
            String action = payload.optString("action", null);
            JSONObject extras = payload.optJSONObject("extra");
            String data = payload.optString("data", null);
            Intent intent = new Intent();
            if (pkg != null) {
                intent.setPackage(pkg);
            }
            if (action != null) {
                intent.setAction(action);
            }
            if (data != null) {
                try {
                    intent.setData(Uri.parse(data));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                String key;
                while (keys.hasNext()) {
                    key = keys.next();
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        intent.putExtra(key, (String) value);
                    } else if (value instanceof Integer) {
                        intent.putExtra(key, ((Integer) value).intValue());
                    } else if (value instanceof Float) {
                        intent.putExtra(key, ((Float) value).floatValue());
                    } else if (value instanceof Boolean) {
                        intent.putExtra(key, ((Boolean) value).booleanValue());
                    }
                }
            }
            context.sendBroadcast(intent);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ExecutionResult uninstallApplication(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: no package specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no package specified");
        }
        if (!Utils.isDeviceOwner(context)) {
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: no device owner");
            return ExecutionResult.fail("NOT_DEVICE_OWNER", "device is not owner");
        }
        try {
            String pkg = payload.getString("pkg");
            InstallUtils.silentUninstallApplication(context, pkg);
            RemoteLogger.log(context, Const.LOG_INFO, "Uninstalled application: " + pkg);
            return ExecutionResult.ok();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: " + e.getMessage());
            return ExecutionResult.fail("UNINSTALL_EXCEPTION", e.getMessage());
        }
    }

    private static ExecutionResult deleteFile(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "File delete failed: no path specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no path specified");
        }
        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            file.delete();
            RemoteLogger.log(context, Const.LOG_INFO, "Deleted file: " + path);
            return ExecutionResult.ok();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "File delete failed: " + e.getMessage());
            return ExecutionResult.fail("DELETE_EXCEPTION", e.getMessage());
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] childFiles = fileOrDirectory.listFiles();
            for (File child : childFiles) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private static ExecutionResult deleteDir(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory delete failed: no path specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no path specified");
        }
        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            deleteRecursive(file);
            RemoteLogger.log(context, Const.LOG_INFO, "Deleted directory: " + path);
            return ExecutionResult.ok();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory delete failed: " + e.getMessage());
            return ExecutionResult.fail("DELETE_DIR_EXCEPTION", e.getMessage());
        }
    }

    private static ExecutionResult purgeDir(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: no path specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no path specified");
        }
        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            if (!file.isDirectory()) {
                RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: not a directory: " + path);
                return ExecutionResult.fail("NOT_A_DIRECTORY", "path is not a directory");
            }
            String recursive = payload.optString("recursive");
            File[] childFiles = file.listFiles();
            if (childFiles != null) {
                for (File child : childFiles) {
                    if (recursive == null || !recursive.equals("1")) {
                        if (!child.isDirectory()) {
                            child.delete();
                        }
                    } else {
                        deleteRecursive(child);
                    }
                }
            }
            RemoteLogger.log(context, Const.LOG_INFO, "Purged directory: " + path);
            return ExecutionResult.ok();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: " + e.getMessage());
            return ExecutionResult.fail("PURGE_EXCEPTION", e.getMessage());
        }
    }

    private static ExecutionResult runCommand(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Command failed: no command specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no command specified");
        }
        try {
            String command = payload.getString("command");
            Log.d(Const.LOG_TAG, "Executing a command: " + command);
            String result = SystemUtils.executeShellCommand(command, true);
            String msg = "Executed a command: " + command;
            if (!result.equals("")) {
                String displayResult = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                msg += " Result: " + displayResult;
            }
            RemoteLogger.log(context, Const.LOG_DEBUG, msg);
            return ExecutionResult.ok(result);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Command failed: " + e.getMessage());
            return ExecutionResult.fail("COMMAND_EXCEPTION", e.getMessage());
        }
    }

    private static void reboot(Context context) {
        RemoteLogger.log(context, Const.LOG_WARN, "Rebooting by a Push message");
        if (Utils.checkAdminMode(context)) {
            if (!Utils.reboot(context)) {
                RemoteLogger.log(context, Const.LOG_WARN, "Reboot failed");
            }
        } else {
            RemoteLogger.log(context, Const.LOG_WARN, "Reboot failed: no permissions");
        }
    }

    private static ExecutionResult clearDownloads(Context context) {
        RemoteLogger.log(context, Const.LOG_WARN, "Clear download history by a Push message");
        try {
            DatabaseHelper dbHelper = DatabaseHelper.instance(context);
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            List<Download> downloads = DownloadTable.selectAll(db);
            for (Download d : downloads) {
                File file = new File(d.getPath());
                try {
                    file.delete();
                } catch (Exception e) {
                    // continue with others
                }
            }
            DownloadTable.deleteAll(db);
            return ExecutionResult.ok();
        } catch (Exception e) {
            return ExecutionResult.fail("CLEAR_DOWNLOADS_EXCEPTION", e.getMessage());
        }
    }

    private static ExecutionResult callIntent(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Calling intent failed: no parameters specified");
            return ExecutionResult.fail("MISSING_PAYLOAD", "no parameters specified");
        }
        try {
            String action = payload.getString("action");
            Log.d(Const.LOG_TAG, "Calling intent: " + action);
            JSONObject extras = payload.optJSONObject("extra");
            String data = payload.optString("data", null);
            Intent i = new Intent(action);
            if (data != null) {
                try {
                    i.setData(Uri.parse(data));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                String key;
                while (keys.hasNext()) {
                    key = keys.next();
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        i.putExtra(key, (String) value);
                    } else if (value instanceof Integer) {
                        i.putExtra(key, ((Integer) value).intValue());
                    } else if (value instanceof Float) {
                        i.putExtra(key, ((Float) value).floatValue());
                    } else if (value instanceof Boolean) {
                        i.putExtra(key, ((Boolean) value).booleanValue());
                    }
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return ExecutionResult.ok();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Calling intent failed: " + e.getMessage());
            return ExecutionResult.fail("INTENT_EXCEPTION", e.getMessage());
        }
    }

    private static ExecutionResult grantPermissions(Context context, JSONObject payload) {
        if (!Utils.isDeviceOwner(context) && !BuildConfig.SYSTEM_PRIVILEGES) {
            RemoteLogger.log(context, Const.LOG_WARN, "Can't auto grant permissions: no device owner");
        }

        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        List<String> apps = null;

        if (payload != null) {
            apps = new LinkedList<>();
            String pkg;
            JSONArray pkgs = payload.optJSONArray("pkg");
            if (pkgs != null) {
                for (int i = 0; i < pkgs.length(); i++) {
                    pkg = pkgs.optString(i);
                    if (pkg != null) {
                        apps.add(pkg);
                    }
                }
            } else {
                pkg = payload.optString("pkg");
                if (pkg != null) {
                    apps.add(pkg);
                }
            }
        } else {
            // By default, grant permissions to all packagee having an URL
            apps = new LinkedList<>();
            List<Application> configApps = config.getApplications();
            for (Application app: configApps) {
                if (Application.TYPE_APP.equals(app.getType()) &&
                    app.getUrl() != null && app.getPkg() != null) {
                    apps.add(app.getPkg());
                }
            }
        }

        try {
            for (String app : apps) {
                Utils.autoGrantRequestedPermissions(context, app,
                        config.getAppPermissions(), false);
            }
            return ExecutionResult.ok();
        } catch (Exception e) {
            return ExecutionResult.fail("GRANT_PERMISSIONS_EXCEPTION", e.getMessage());
        }
    }

    private static void showNotificationToUser(Context context, JSONObject payload) {
        String text = null;
        if (payload != null) {
            text = payload.optString("text", null);
            if (text != null) {
                text = text.trim();
            }
        }
        if (text == null || text.isEmpty()) {
            text = "MDM";
        }
        Intent intent = new Intent(context, com.hmdm.launcher.ui.NotificationDialogActivity.class);
        intent.putExtra(com.hmdm.launcher.ui.NotificationDialogActivity.EXTRA_MESSAGE, text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        RemoteLogger.log(context, Const.LOG_INFO, "Notification dialog shown to user: " + text);
    }

    private static ExecutionResult clearAppData(Context context, JSONObject payload) {
        if (payload == null) {
            return ExecutionResult.fail("MISSING_PAYLOAD", "no package specified");
        }
        try {
            String pkg = payload.getString("pkg");
            RemoteLogger.log(context, Const.LOG_INFO, "Clearing app data for " + pkg);
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.clearApplicationUserData(
                        adminComponentName,
                        pkg,
                        Executors.newSingleThreadExecutor(),
                        (packageName, succeeded) -> {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                    "App data for " + packageName + (succeeded ? " " : " not ") + "cleared");
                        }
                );
                return ExecutionResult.ok();
            } else {
                return ExecutionResult.fail("UNSUPPORTED_SDK", "Unsupported in SDK " + Build.VERSION.SDK_INT);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_ERROR, "Failed to clear app data: " + e.getMessage());
            return ExecutionResult.fail("CLEAR_APP_DATA_EXCEPTION", e.getMessage());
        }
    }

}
