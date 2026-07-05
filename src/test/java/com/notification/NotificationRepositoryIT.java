package com.notification;

import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.Channel;
import com.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRepositoryIT extends AbstractIntegrationTest {

    @Autowired NotificationRepository notifications;

    @Test
    void insertIfAbsent_isIdempotentPerTenantAndKey() {
        UUID tenant = UUID.randomUUID();
        UUID consumer = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        String idemKey = UUID.randomUUID().toString();

        var first = notifications.insertIfAbsent(tenant, consumer, template, Channel.SMS, idemKey, null);
        var second = notifications.insertIfAbsent(tenant, consumer, template, Channel.SMS, idemKey, null);

        assertTrue(first.created(), "first insert should create");
        assertFalse(second.created(), "second insert with same key should not create");
        assertEquals(first.id(), second.id(), "duplicate key must resolve to the same row");
    }
}
