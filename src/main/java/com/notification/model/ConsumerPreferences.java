package com.notification.model;

import java.time.Instant;
import java.util.UUID;

public record ConsumerPreferences(
        UUID consumerId,
        UUID tenantId,
        boolean optedOut,
        Instant optedOutAt,
        String optedOutVia,
        String timezone,
        boolean smsEnabled,
        boolean emailEnabled
) {
}
