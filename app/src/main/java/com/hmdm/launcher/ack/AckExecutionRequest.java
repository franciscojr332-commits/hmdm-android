/*
 * HMDM-EVOLUTION F2: Execution ACK request DTO (agent → server).
 */

package com.hmdm.launcher.ack;

public class AckExecutionRequest {
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    public String deviceNumber;
    public Integer messageId;
    public Long executedAt;
    public String status;
    public String failureCode;
    public String failureMessage;
    public String resultPayload;

    public AckExecutionRequest() {}

    public AckExecutionRequest(String deviceNumber, Integer messageId, Long executedAt,
                                String status, String failureCode, String failureMessage) {
        this.deviceNumber = deviceNumber;
        this.messageId = messageId;
        this.executedAt = executedAt;
        this.status = status;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }
}
