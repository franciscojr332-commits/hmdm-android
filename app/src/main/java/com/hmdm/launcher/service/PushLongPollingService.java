package com.hmdm.launcher.service;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.ack.AckQueue;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.PushMessage;
import com.hmdm.launcher.json.PushResponse;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.receiver.ShutdownReceiver;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;
import com.hmdm.launcher.worker.PushNotificationProcessor;

import org.eclipse.paho.android.service.MqttService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Response;

public class PushLongPollingService extends Service {

    private volatile boolean enabled = true;
    private volatile boolean threadActive = false;
    private Thread pollingThread;
    // C: faster retry after a network/connection error (was 60000). Cuts the window between a
    // transient failure and the next reconnect attempt.
    private final long DELAY_AFTER_EXCEPTION_MS = 20000;
    // Delay between polling requests to avoid looping if the server would respond instantly
    private final long DELAY_AFTER_REQUEST_MS = 5000;
    public static String CHANNEL_ID = MqttService.class.getName();
    // A flag preventing multiple notifications for the foreground service
    boolean started = false;
    // Notification ID for the foreground service
    private static final int NOTIFICATION_ID = 113;
    private ServerService serverService;
    private ServerService secondaryServerService;

    // A: AlarmManager keepalive — resurrects this service within ~2min if the OS kills it,
    // instead of waiting for the 15-min PushNotificationWorker. The alarm survives process death.
    private static final long KEEPALIVE_INTERVAL_MS = 120000; // 2 min
    private static final int KEEPALIVE_REQUEST_CODE = 1131;
    public static final String ACTION_KEEPALIVE = "com.hmdm.launcher.LONGPOLL_KEEPALIVE";

    // B: react to network regained immediately (interrupt the retry sleep -> instant re-poll).
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            if (intent != null && intent.getAction() != null &&
                    intent.getAction().equals(Const.ACTION_SERVICE_STOP)) {
                enabled = false;
                cancelKeepalive();          // intentional stop -> do NOT let the alarm resurrect us
                Thread t = pollingThread;
                if (t != null) t.interrupt();
                stopSelf();
            }
        }
    };

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance( this ).unregisterReceiver(receiver);
        unregisterNetworkCallback();
        Log.i(Const.LOG_TAG, "PushLongPollingService: service stopped");
        started = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        enabled = true;

        if (BuildConfig.MQTT_SERVICE_FOREGROUND && !started) {
            startAsForeground();
            started = true;
        }

        Log.i(Const.LOG_TAG, "PushLongPolling: service started. ");

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        LocalBroadcastManager.getInstance( this ).registerReceiver( receiver, intentFilter );

        registerNetworkCallback();   // B (idempotent)
        scheduleKeepalive();         // A (re-armed on every start, including alarm-triggered)
        startPollingThreadIfNeeded();// D

        return Service.START_STICKY;
    }

    // D: only (re)start the thread if it is genuinely not running. Resilient to a thread that
    // died abnormally (threadActive could otherwise stay stuck true forever).
    private synchronized void startPollingThreadIfNeeded() {
        if (threadActive && pollingThread != null && pollingThread.isAlive()) {
            return;
        }
        threadActive = true;
        pollingThread = new Thread(pollingRunnable);
        pollingThread.start();
    }

    private Runnable pollingRunnable = () -> {
        Context context = PushLongPollingService.this;
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
            if (serverService == null) {
                serverService = ServerServiceKeeper.createServerService(settingsHelper.getBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
            }
            if (secondaryServerService == null) {
                secondaryServerService = ServerServiceKeeper.createServerService(settingsHelper.getSecondaryBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
            }

            // Calculate request signature
            String encodedDeviceId = settingsHelper.getDeviceId();
            try {
                encodedDeviceId = URLEncoder.encode(encodedDeviceId, "utf8");
            } catch (UnsupportedEncodingException e) {
            }
            String path = settingsHelper.getServerProject() + "/rest/notification/polling/" + encodedDeviceId;
            String signature = null;
            try {
                signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
            } catch (Exception e) {
            }

            while (enabled) {
                // Anti-tamper: amostra estado do SIM a cada ciclo (roda mesmo offline). Persiste a
                // janela de ausência e reporta quando o chip volta — pega remoção feita com o
                // aparelho desligado/sem internet. O log é enfileirado e sobe quando reconectar.
                sampleSimAbsence(context);

                Response<PushResponse> response = null;

                RemoteLogger.log(context, Const.LOG_VERBOSE, "Push long polling inquiry");
                try {
                    // This is the long operation
                    response = serverService.
                            queryPushLongPolling(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), signature).execute();
                } catch (Exception e) {
                    RemoteLogger.log(context, Const.LOG_WARN, "Failed to query push notifications from "
                            + settingsHelper.getBaseUrl() + " : " + e.getMessage());
                    e.printStackTrace();
                }

                try {
                    if (response == null) {
                        response = secondaryServerService.
                                queryPushLongPolling(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), signature).execute();
                    }

                    if ( response.isSuccessful() ) {
                        if ( Const.STATUS_OK.equals( response.body().getStatus() ) && response.body().getData() != null ) {
                            Map<String, PushMessage> filteredMessages = new HashMap<String, PushMessage>();
                            for (PushMessage message : response.body().getData()) {
                                // Filter out multiple configuration update requests
                                if (!message.getMessageType().equals(PushMessage.TYPE_CONFIG_UPDATED) ||
                                        !filteredMessages.containsKey(PushMessage.TYPE_CONFIG_UPDATED)) {
                                    filteredMessages.put(message.getMessageType(), message);
                                }
                            }
                            // HMDM-EVOLUTION F2: send delivery ACK immediately upon receipt,
                            // before dispatching to handlers. Server flips status IN_FLIGHT → DELIVERED.
                            long receivedAt = System.currentTimeMillis();
                            for (Map.Entry<String, PushMessage> entry : filteredMessages.entrySet()) {
                                PushMessage m = entry.getValue();
                                if (m.getId() != null && m.getId() > 0) {
                                    AckQueue.getInstance(context).enqueueDelivery(m.getId(), receivedAt);
                                }
                                PushNotificationProcessor.process(m, context);
                            }
                        }
                    } else if (response.code() >= 400 && response.code() < 500) {
                        // Response code 500 is fine (Timeout), so here we log only 4xx requests (403 Forbidden in particular)
                        RemoteLogger.log(context, Const.LOG_WARN, "Wrong response while querying push notifications from "
                                + settingsHelper.getSecondaryBaseUrl() + " : HTTP status " + response.code());
                        // On error, wait to avoid looping (B: interruptible -> network regain re-polls now)
                        sleepInterruptible(DELAY_AFTER_EXCEPTION_MS);
                    }
                    // Avoid looping by adding some pause (B: interruptible)
                    sleepInterruptible(DELAY_AFTER_REQUEST_MS);

                } catch ( Exception e ) {
                    RemoteLogger.log(context, Const.LOG_WARN, "Failed to query push notifications from "
                            + settingsHelper.getSecondaryBaseUrl() + " : " + e.getMessage());
                    e.printStackTrace();
                    // On exception, we need to wait to avoid looping (B: interruptible)
                    sleepInterruptible(DELAY_AFTER_EXCEPTION_MS);
                }
            }
        } finally {
            // D: always release the flag so a future onStartCommand / keepalive can restart the thread.
            threadActive = false;
        }
    };

    // Anti-tamper: rastreia janela "sem chip" mesmo offline. getSimState() não exige permissão.
    // Marca início quando ABSENT; ao voltar READY, loga a duração total (reportado no reconnect).
    private static final String KEY_SIM_ABSENT_SINCE = "sim_absent_since";
    private static final String KEY_SIM_ABSENT_REPORTED = "sim_absent_reported";
    private void sampleSimAbsence(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            int state = tm.getSimState();
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(ShutdownReceiver.PREFS, Context.MODE_PRIVATE);
            long since = sp.getLong(KEY_SIM_ABSENT_SINCE, 0);
            long now = System.currentTimeMillis();

            if (state == TelephonyManager.SIM_STATE_ABSENT) {
                if (since == 0) {
                    sp.edit().putLong(KEY_SIM_ABSENT_SINCE, now).putLong(KEY_SIM_ABSENT_REPORTED, 0).apply();
                    since = now;
                }
                // Se já passou >5min sem chip e ainda não reportou o início, avisa (uma vez) ao reconectar.
                long reported = sp.getLong(KEY_SIM_ABSENT_REPORTED, 0);
                if (reported == 0 && now - since >= 5 * 60_000) {
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "[TAMPER] Aparelho SEM CHIP ha ~" + ((now - since) / 60000) + "min (ainda sem chip)");
                    sp.edit().putLong(KEY_SIM_ABSENT_REPORTED, now).apply();
                }
            } else if (state == TelephonyManager.SIM_STATE_READY) {
                if (since > 0) {
                    long durMin = (now - since) / 60000;
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "[TAMPER] Aparelho ficou SEM CHIP por ~" + durMin + "min (chip voltou)");
                    sp.edit().remove(KEY_SIM_ABSENT_SINCE).remove(KEY_SIM_ABSENT_REPORTED).apply();
                }
            }
            // Outros estados (UNKNOWN, PIN, NETWORK_LOCKED): não mexe na janela.
        } catch (Exception e) {
            // ignore
        }
    }

    // B: a sleep that returns early when interrupted (network regained), so the next poll fires now.
    private void sleepInterruptible(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Cleared interrupt flag; loop continues immediately with a fresh poll.
        }
    }

    // ---- A: keepalive alarm -------------------------------------------------

    private void scheduleKeepalive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = keepalivePendingIntent();
            long next = System.currentTimeMillis() + KEEPALIVE_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                }
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "PushLongPolling: failed to schedule keepalive", e);
        }
    }

    private void cancelKeepalive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(keepalivePendingIntent());
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "PushLongPolling: failed to cancel keepalive", e);
        }
    }

    private PendingIntent keepalivePendingIntent() {
        Intent i = new Intent(this, PushLongPollingService.class);
        i.setAction(ACTION_KEEPALIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, KEEPALIVE_REQUEST_CODE, i, flags);
        }
        return PendingIntent.getService(this, KEEPALIVE_REQUEST_CODE, i, flags);
    }

    // ---- B: network callback ------------------------------------------------

    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return; // registerDefaultNetworkCallback: API 24+
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Network came back: make sure we're polling and wake the retry sleep now.
                    startPollingThreadIfNeeded();
                    Thread t = pollingThread;
                    if (t != null) {
                        t.interrupt();
                    }
                    RemoteLogger.log(PushLongPollingService.this, Const.LOG_DEBUG,
                            "PushLongPolling: network available -> immediate re-poll");
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            networkCallback = null;
            Log.w(Const.LOG_TAG, "PushLongPolling: failed to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            // ignore
        } finally {
            networkCallback = null;
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText(getString(R.string.mqtt_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service).build();

        Utils.startStableForegroundService(this, NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
