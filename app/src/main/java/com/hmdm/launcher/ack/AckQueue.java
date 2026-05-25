/*
 * HMDM-EVOLUTION F2: Single-threaded queue for sending delivery + execution ACKs.
 *
 * Architecture:
 *   - BlockingQueue (in-memory) — fast path
 *   - SQLite AckOutboxTable — persistent fallback when network fails
 *   - Worker thread tries network; on failure, persists; periodically drains outbox
 *
 * Lifecycle:
 *   - Singleton initialized lazily on first enqueue
 *   - resumeUnsent() — call from app init (MainActivity) to drain outbox
 *
 * Idempotent server-side: re-sending same ACK is a no-op on server.
 */

package com.hmdm.launcher.ack;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.AckOutboxTable;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import retrofit2.Response;

public class AckQueue {

    private static final String TAG = "AckQueue";
    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_BACKOFF_MS = 10_000L;     // 10s
    private static final long MAX_BACKOFF_MS = 3600_000L;    // 1h
    private static final long DRAIN_INTERVAL_MS = 60_000L;   // 60s background drain

    private static AckQueue instance;

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MDM-AckQueue");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService drainScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MDM-AckDrain");
        t.setDaemon(true);
        return t;
    });

    private AckQueue(Context ctx) {
        this.appContext = ctx;
        // Background drain loop (initial 30s, then every 60s)
        drainScheduler.scheduleWithFixedDelay(this::drainOutbox, 30, 60, TimeUnit.SECONDS);
    }

    public static synchronized AckQueue getInstance(Context context) {
        if (instance == null) {
            instance = new AckQueue(context.getApplicationContext());
        }
        return instance;
    }

    public void enqueueDelivery(int messageId, long receivedAt) {
        if (messageId <= 0) return;
        AckDeliveryRequest req = new AckDeliveryRequest(
                SettingsHelper.getInstance(appContext).getDeviceId(),
                messageId, receivedAt);
        worker.submit(() -> sendDelivery(req, true));
    }

    public void enqueueExecution(int messageId, long executedAt, String status,
                                  String failureCode, String failureMessage) {
        if (messageId <= 0) return;
        AckExecutionRequest req = new AckExecutionRequest(
                SettingsHelper.getInstance(appContext).getDeviceId(),
                messageId, executedAt, status, failureCode, failureMessage);
        worker.submit(() -> sendExecution(req, true));
    }

    /**
     * Drain outbox on app start.
     */
    public void resumeUnsent() {
        worker.submit(this::drainOutbox);
    }

    private void sendDelivery(AckDeliveryRequest req, boolean persistOnFailure) {
        try {
            ServerService svc = ServerServiceKeeper.getServerServiceInstance(appContext);
            String project = SettingsHelper.getInstance(appContext).getServerProject();
            String path = project + "/rest/notification/ack/delivery";
            String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
            Response<okhttp3.ResponseBody> resp = svc.ackDelivery(project, signature, req).execute();
            if (!resp.isSuccessful()) {
                if (persistOnFailure) {
                    persistDelivery(req);
                }
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "ACK delivery failed (code=" + resp.code() + ") msgId=" + req.messageId);
            }
        } catch (Exception e) {
            if (persistOnFailure) {
                persistDelivery(req);
            }
            RemoteLogger.log(appContext, Const.LOG_WARN,
                    "ACK delivery exception msgId=" + req.messageId + ": " + e.getMessage());
        }
    }

    private void sendExecution(AckExecutionRequest req, boolean persistOnFailure) {
        try {
            ServerService svc = ServerServiceKeeper.getServerServiceInstance(appContext);
            String project = SettingsHelper.getInstance(appContext).getServerProject();
            String path = project + "/rest/notification/ack/execution";
            String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
            Response<okhttp3.ResponseBody> resp = svc.ackExecution(project, signature, req).execute();
            if (!resp.isSuccessful()) {
                if (persistOnFailure) {
                    persistExecution(req);
                }
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "ACK execution failed (code=" + resp.code() + ") msgId=" + req.messageId);
            }
        } catch (Exception e) {
            if (persistOnFailure) {
                persistExecution(req);
            }
            RemoteLogger.log(appContext, Const.LOG_WARN,
                    "ACK execution exception msgId=" + req.messageId + ": " + e.getMessage());
        }
    }

    private void persistDelivery(AckDeliveryRequest req) {
        try {
            SQLiteDatabase db = DatabaseHelper.instance(appContext).getWritableDatabase();
            AckOutboxTable.insertDelivery(db, req);
        } catch (Exception e) {
            // Best-effort
        }
    }

    private void persistExecution(AckExecutionRequest req) {
        try {
            SQLiteDatabase db = DatabaseHelper.instance(appContext).getWritableDatabase();
            AckOutboxTable.insertExecution(db, req);
        } catch (Exception e) {
            // Best-effort
        }
    }

    /**
     * Drain outbox entries ready for retry.
     */
    private void drainOutbox() {
        try {
            SQLiteDatabase db = DatabaseHelper.instance(appContext).getWritableDatabase();
            long now = System.currentTimeMillis();
            List<AckOutboxTable.OutboxEntry> ready = AckOutboxTable.selectReady(db, now, 50);
            if (ready.isEmpty()) return;

            String deviceNumber = SettingsHelper.getInstance(appContext).getDeviceId();
            for (AckOutboxTable.OutboxEntry e : ready) {
                boolean sent;
                if (AckOutboxTable.TYPE_DELIVERY.equals(e.type)) {
                    AckDeliveryRequest req = new AckDeliveryRequest(deviceNumber, e.messageId, e.timestamp);
                    sent = trySendDelivery(req);
                } else {
                    AckExecutionRequest req = new AckExecutionRequest(deviceNumber, e.messageId,
                            e.timestamp, e.status, e.failureCode, e.failureMessage);
                    sent = trySendExecution(req);
                }

                if (sent) {
                    AckOutboxTable.delete(db, e.id);
                } else {
                    int newAttempts = e.attempts + 1;
                    if (newAttempts >= MAX_ATTEMPTS) {
                        // Permanent failure — drop. Server reconciliation will catch via stale_in_flight.
                        AckOutboxTable.delete(db, e.id);
                        RemoteLogger.log(appContext, Const.LOG_WARN,
                                "ACK outbox: dropped after " + newAttempts + " attempts msgId=" + e.messageId);
                    } else {
                        long delay = Math.min(BASE_BACKOFF_MS * (1L << newAttempts), MAX_BACKOFF_MS);
                        AckOutboxTable.scheduleRetry(db, e.id, newAttempts, now + delay);
                    }
                }
            }
        } catch (Exception e) {
            RemoteLogger.log(appContext, Const.LOG_WARN, "AckQueue drain exception: " + e.getMessage());
        }
    }

    private boolean trySendDelivery(AckDeliveryRequest req) {
        try {
            ServerService svc = ServerServiceKeeper.getServerServiceInstance(appContext);
            String project = SettingsHelper.getInstance(appContext).getServerProject();
            String path = project + "/rest/notification/ack/delivery";
            String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
            Response<okhttp3.ResponseBody> resp = svc.ackDelivery(project, signature, req).execute();
            return resp.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean trySendExecution(AckExecutionRequest req) {
        try {
            ServerService svc = ServerServiceKeeper.getServerServiceInstance(appContext);
            String project = SettingsHelper.getInstance(appContext).getServerProject();
            String path = project + "/rest/notification/ack/execution";
            String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
            Response<okhttp3.ResponseBody> resp = svc.ackExecution(project, signature, req).execute();
            return resp.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
