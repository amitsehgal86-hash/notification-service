package com.notification.model;

import java.util.Map;
import java.util.UUID;

/**
 * Internal work item placed on the in-JVM queue (replaces the SQS payload).
 * Carries everything the orchestrator needs to process one notification.
 */
public record NotificationRequest(
        UUID notificationId,
        UUID tenantId,
        UUID consumerId,
        UUID templateId,
        Channel channel,
        Map<String, Object> variables
) {
}
