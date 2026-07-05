package com.notification.model;

import java.util.UUID;

public record Template(
        UUID id,
        UUID tenantId,
        String name,
        Channel channel,
        String bodyTemplate,
        boolean requiresMiranda
) {
}
