package com.notification.infrastructure.sender;

/**
 * Outcome of handing a message to a (simulated) provider.
 * providerMessageId mirrors a Twilio SID / SendGrid message id and is the delivery-log dedup key.
 */
public record SendResult(String providerMessageId, boolean accepted, String error) {

    public static SendResult accepted(String providerMessageId) {
        return new SendResult(providerMessageId, true, null);
    }

}
