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

import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.RemoteLogger;

/**
 * Anti-tamper: registra desligamento LIMPO (pelo menu). Grava um marcador local ANTES de logar,
 * porque o RemoteLogger pode não enviar a tempo (rede cai junto). O marcador é lido pelo
 * BootReceiver no próximo boot p/ reportar a janela off e detectar boot SEM shutdown limpo
 * (forçado / bateria arrancada).
 */
public class ShutdownReceiver extends BroadcastReceiver {

    public static final String PREFS = "tamper";
    public static final String KEY_CLEAN_SHUTDOWN_AT = "last_clean_shutdown_at";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        long now = System.currentTimeMillis();
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putLong(KEY_CLEAN_SHUTDOWN_AT, now).commit(); // síncrono — processo vai morrer
        } catch (Exception e) {
            // ignore
        }
        RemoteLogger.log(context, Const.LOG_INFO, "[TAMPER] APARELHO DESLIGADO (menu)");
    }
}
