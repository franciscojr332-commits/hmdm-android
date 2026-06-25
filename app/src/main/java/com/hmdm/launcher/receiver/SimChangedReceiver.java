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

package com.hmdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.DeviceInfoProvider;
import com.hmdm.launcher.util.RemoteLogger;

/**
 * Anti-tamper: loga SOMENTE remoção FÍSICA real do SIM (e a reinserção que a segue).
 * Filtra o ruído que poluía o log: re-leitura do SIM em todo boot/reboot (ABSENT→LOADED) e
 * broadcasts duplicados (sticky + por slot). Sem isso, cada reboot gerava 1-3 "SIM removed" falsos.
 */
public class SimChangedReceiver extends BroadcastReceiver {

    private static final long BOOT_WINDOW_MS = 90_000;  // ignora SIM event ~90s ao redor do boot
    private static final long DEDUP_MS       = 60_000;  // ignora ABSENT duplicado dentro de 60s
    private static final long REINSERT_MS    = 30 * 60_000; // LOADED conta como reinserção até 30min após remoção real
    private static final String KEY_SIM_REMOVED_AT = "last_sim_removed_at";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        String simState = intent.getExtras().getString("ss");
        if (simState == null) return;

        long now = System.currentTimeMillis();
        long bootTime = now - SystemClock.elapsedRealtime();
        // Re-leitura do SIM no boot NÃO é remoção física -> ignora janela de boot.
        if (now - bootTime < BOOT_WINDOW_MS) return;

        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(ShutdownReceiver.PREFS, Context.MODE_PRIVATE);

        if ("ABSENT".equals(simState)) {
            long lastRem = sp.getLong(KEY_SIM_REMOVED_AT, 0);
            if (now - lastRem < DEDUP_MS) return;          // dedup broadcast duplicado
            sp.edit().putLong(KEY_SIM_REMOVED_AT, now).apply();
            RemoteLogger.log(context, Const.LOG_WARN, "[TAMPER] SIM REMOVIDO em operacao");
        } else if ("LOADED".equals(simState)) {
            long lastRem = sp.getLong(KEY_SIM_REMOVED_AT, 0);
            // Só loga reinserção se seguir uma remoção REAL recente (não loga re-leitura solta).
            if (lastRem > 0 && now - lastRem < REINSERT_MS) {
                String phone = null;
                try { phone = DeviceInfoProvider.getPhoneNumber(context); } catch (Exception e) {}
                String msg = "[TAMPER] SIM reinserido";
                if (phone != null && phone.length() > 0) msg += " (" + phone + ")";
                RemoteLogger.log(context, Const.LOG_WARN, msg);
                sp.edit().remove(KEY_SIM_REMOVED_AT).apply();
            }
        }
    }
}
