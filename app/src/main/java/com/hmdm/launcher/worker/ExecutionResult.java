/*
 * HMDM-EVOLUTION F2: Result of executing a push command handler.
 * Used by PushNotificationProcessor to report back via AckQueue.
 */

package com.hmdm.launcher.worker;

public class ExecutionResult {
    public final boolean success;
    public final String failureCode;
    public final String failureMessage;
    public final String resultPayload;

    private ExecutionResult(boolean success, String failureCode, String failureMessage, String resultPayload) {
        this.success = success;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.resultPayload = resultPayload;
    }

    public static ExecutionResult ok() {
        return new ExecutionResult(true, null, null, null);
    }

    public static ExecutionResult ok(String resultPayload) {
        return new ExecutionResult(true, null, null, resultPayload);
    }

    public static ExecutionResult fail(String code, String message) {
        return new ExecutionResult(false, code, message, null);
    }
}
