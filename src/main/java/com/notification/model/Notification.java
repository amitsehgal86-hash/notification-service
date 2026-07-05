package com.notification.model;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID tenantId,
        UUID consumerId,
        UUID templateId,
        Channel channel,
        NotificationStatus status,
        String idempotencyKey,
        Instant scheduledAt,
        Instant sentAt,
        Instant createdAt
) {
}
