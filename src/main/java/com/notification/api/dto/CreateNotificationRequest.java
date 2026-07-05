package com.notification.api.dto;

import com.notification.model.Channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CreateNotificationRequest(
        UUID consumerId,
        UUID templateId,
        Channel channel,
        Instant scheduledAt,
        Map<String, Object> variables
) {
}
