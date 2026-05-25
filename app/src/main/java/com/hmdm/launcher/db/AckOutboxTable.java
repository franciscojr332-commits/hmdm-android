/*
 * HMDM-EVOLUTION F2: SQLite outbox for pending ACKs (delivery/execution).
 * Persists ACKs that failed to send (e.g., server offline) for retry on next opportunity.
 */

package com.hmdm.launcher.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.hmdm.launcher.ack.AckDeliveryRequest;
import com.hmdm.launcher.ack.AckExecutionRequest;

import java.util.ArrayList;
import java.util.List;

public class AckOutboxTable {
    public static final String TABLE = "ack_outbox";

    public static final String C_ID = "id";
    public static final String C_TYPE = "ack_type";              // 'delivery' | 'execution'
    public static final String C_MESSAGE_ID = "message_id";
    public static final String C_TIMESTAMP = "timestamp";        // received_at or executed_at
    public static final String C_STATUS = "status";              // for execution: 'OK' | 'FAILED'
    public static final String C_FAILURE_CODE = "failure_code";
    public static final String C_FAILURE_MESSAGE = "failure_message";
    public static final String C_ATTEMPTS = "attempts";
    public static final String C_NEXT_RETRY_AT = "next_retry_at";
    public static final String C_CREATED_AT = "created_at";

    public static final String TYPE_DELIVERY = "delivery";
    public static final String TYPE_EXECUTION = "execution";

    public static String getCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                C_TYPE + " TEXT NOT NULL," +
                C_MESSAGE_ID + " INTEGER NOT NULL," +
                C_TIMESTAMP + " INTEGER NOT NULL," +
                C_STATUS + " TEXT," +
                C_FAILURE_CODE + " TEXT," +
                C_FAILURE_MESSAGE + " TEXT," +
                C_ATTEMPTS + " INTEGER NOT NULL DEFAULT 0," +
                C_NEXT_RETRY_AT + " INTEGER NOT NULL DEFAULT 0," +
                C_CREATED_AT + " INTEGER NOT NULL" +
                ");";
    }

    public static String getCreateIndexSql() {
        return "CREATE INDEX IF NOT EXISTS idx_ack_next_retry ON " + TABLE + "(" + C_NEXT_RETRY_AT + ");";
    }

    public static long insertDelivery(SQLiteDatabase db, AckDeliveryRequest req) {
        ContentValues v = new ContentValues();
        v.put(C_TYPE, TYPE_DELIVERY);
        v.put(C_MESSAGE_ID, req.messageId);
        v.put(C_TIMESTAMP, req.receivedAt);
        v.put(C_ATTEMPTS, 0);
        v.put(C_NEXT_RETRY_AT, System.currentTimeMillis());
        v.put(C_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE, null, v);
    }

    public static long insertExecution(SQLiteDatabase db, AckExecutionRequest req) {
        ContentValues v = new ContentValues();
        v.put(C_TYPE, TYPE_EXECUTION);
        v.put(C_MESSAGE_ID, req.messageId);
        v.put(C_TIMESTAMP, req.executedAt);
        v.put(C_STATUS, req.status);
        v.put(C_FAILURE_CODE, req.failureCode);
        v.put(C_FAILURE_MESSAGE, req.failureMessage);
        v.put(C_ATTEMPTS, 0);
        v.put(C_NEXT_RETRY_AT, System.currentTimeMillis());
        v.put(C_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE, null, v);
    }

    public static List<OutboxEntry> selectReady(SQLiteDatabase db, long now, int limit) {
        List<OutboxEntry> out = new ArrayList<>();
        Cursor c = db.query(TABLE, null,
                C_NEXT_RETRY_AT + " <= ?",
                new String[]{String.valueOf(now)},
                null, null, C_CREATED_AT + " ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                OutboxEntry e = new OutboxEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow(C_ID));
                e.type = c.getString(c.getColumnIndexOrThrow(C_TYPE));
                e.messageId = c.getInt(c.getColumnIndexOrThrow(C_MESSAGE_ID));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow(C_TIMESTAMP));
                e.status = c.getString(c.getColumnIndexOrThrow(C_STATUS));
                e.failureCode = c.getString(c.getColumnIndexOrThrow(C_FAILURE_CODE));
                e.failureMessage = c.getString(c.getColumnIndexOrThrow(C_FAILURE_MESSAGE));
                e.attempts = c.getInt(c.getColumnIndexOrThrow(C_ATTEMPTS));
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public static void delete(SQLiteDatabase db, long id) {
        db.delete(TABLE, C_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public static void scheduleRetry(SQLiteDatabase db, long id, int attempts, long nextRetryAt) {
        ContentValues v = new ContentValues();
        v.put(C_ATTEMPTS, attempts);
        v.put(C_NEXT_RETRY_AT, nextRetryAt);
        db.update(TABLE, v, C_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public static int count(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally { c.close(); }
        return 0;
    }

    public static class OutboxEntry {
        public long id;
        public String type;
        public int messageId;
        public long timestamp;
        public String status;
        public String failureCode;
        public String failureMessage;
        public int attempts;
    }
}
