package com.notification.api.dto;

public record OptOutRequest(
        String channel,
        String reason
) {
}
