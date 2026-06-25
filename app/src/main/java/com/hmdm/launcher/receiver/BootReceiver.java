package com.hmdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.Initializer;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.ui.MainActivity;
import com.hmdm.launcher.util.RemoteLogger;

public class BootReceiver extends BroadcastReceiver {

    private static void startMdmLauncherActivity(Context context) {
        Intent launcherIntent = new Intent(context, MainActivity.class);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(launcherIntent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() != null ? intent.getAction() : "";
        Log.i(Const.LOG_TAG, "BootReceiver action=" + action); // só local; tirado do RemoteLogger (spam)

        Context appContext = context.getApplicationContext();
        SettingsHelper settingsHelper = SettingsHelper.getInstance(appContext);

        // Anti-tamper: loga LIGADO uma vez por boot + detecta boot SEM shutdown limpo
        // (forçado / bateria arrancada — sinal de quem desliga pra não ser rastreado).
        if (settingsHelper.isBaseUrlSet()) {
            try {
                long nowMs = System.currentTimeMillis();
                long bootId = nowMs - SystemClock.elapsedRealtime();
                SharedPreferences tp = appContext.getSharedPreferences(
                        ShutdownReceiver.PREFS, Context.MODE_PRIVATE);
                if (tp.getLong("last_boot_logged_bootid", 0) != bootId) {
                    long cleanShutdown = tp.getLong(ShutdownReceiver.KEY_CLEAN_SHUTDOWN_AT, 0);
                    if (cleanShutdown > 0 && bootId - cleanShutdown < 24L * 3600 * 1000) {
                        long offMin = (bootId - cleanShutdown) / 60000;
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "[TAMPER] APARELHO LIGADO (estava desligado ~" + offMin + "min)");
                    } else {
                        RemoteLogger.log(context, Const.LOG_WARN,
                                "[TAMPER] APARELHO LIGADO sem desligamento limpo (forcado/bateria/crash)");
                    }
                    tp.edit().putLong("last_boot_logged_bootid", bootId)
                            .remove(ShutdownReceiver.KEY_CLEAN_SHUTDOWN_AT).apply();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Intent.ACTION_USER_UNLOCKED.equals(action)) {
            if (!settingsHelper.isBaseUrlSet()) {
                return;
            }
            if (!ProUtils.shouldOpenLauncherImmediatelyAtBoot(context)) {
                return;
            }
            long lastAppStartTime = settingsHelper.getAppStartTime();
            long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            if (lastAppStartTime >= bootTime) {
                Log.d(Const.LOG_TAG, "USER_UNLOCKED: MDM already started this boot, skip");
                return;
            }
            Log.i(Const.LOG_TAG, "USER_UNLOCKED: starting MDM launcher");
            startMdmLauncherActivity(context);
            return;
        }

        if (!settingsHelper.isBaseUrlSet()) {
            return;
        }

        long lastAppStartTime = settingsHelper.getAppStartTime();
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Log.d(Const.LOG_TAG, "appStartTime=" + lastAppStartTime + ", bootTime=" + bootTime);
        if (lastAppStartTime < bootTime) {
            Log.i(Const.LOG_TAG, "Headwind MDM wasn't started since boot, start initializing services");
        } else {
            Log.i(Const.LOG_TAG, "Headwind MDM is already started, ignoring BootReceiver");
            return;
        }

        if (ProUtils.shouldOpenLauncherImmediatelyAtBoot(context)) {
            Log.i(Const.LOG_TAG, "Starting MDM launcher at boot (enrolled, not run-default-launcher)");
            startMdmLauncherActivity(context);
            return;
        }

        Initializer.init(context, () -> {
            Initializer.startServicesAndLoadConfig(context);
            SettingsHelper.getInstance(context).setMainActivityRunning(false);
        });
    }
}
