/*
 * HMDM-EVOLUTION F2: Delivery ACK request DTO (agent → server).
 */

package com.hmdm.launcher.ack;

public class AckDeliveryRequest {
    public String deviceNumber;
    public Integer messageId;
    public Long receivedAt;

    public AckDeliveryRequest() {}

    public AckDeliveryRequest(String deviceNumber, Integer messageId, Long receivedAt) {
        this.deviceNumber = deviceNumber;
        this.messageId = messageId;
        this.receivedAt = receivedAt;
    }
}
