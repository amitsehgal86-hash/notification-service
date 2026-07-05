package com.notification;

import com.notification.domain.DomainExceptions.MiniMirandaMissingException;
import com.notification.domain.NotificationOrchestrator;
import com.notification.infrastructure.repo.ConsumerPreferenceRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.TemplateRepository;
import com.notification.model.Channel;
import com.notification.model.ConsumerPreferences;
import com.notification.model.Notification;
import com.notification.model.NotificationRequest;
import com.notification.model.NotificationStatus;
import com.notification.model.ProcessOutcome;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorIT extends AbstractIntegrationTest {

    private static final String COMPLIANT_BODY =
            "This is an attempt to collect a debt and any information obtained will be used for that "
            + "purpose. Balance {{amount}}.";

    @Autowired NotificationOrchestrator orchestrator;
    @Autowired ConsumerPreferenceRepository prefs;
    @Autowired TemplateRepository templates;
    @Autowired NotificationRepository notifications;
    @Autowired MutableClock clock;

    private UUID tenant;
    private UUID consumer;
    private UUID templateId;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        consumer = UUID.randomUUID();
        clock.setInstant(Instant.parse("2026-06-15T15:00:00Z")); // 10:00 America/Chicago — inside window
        prefs.upsert(new ConsumerPreferences(consumer, tenant, false, null, null,
                "America/Chicago", true, true));
        templateId = templates.insertIfAbsent(tenant, "tpl-" + UUID.randomUUID(), Channel.SMS, COMPLIANT_BODY, true);
    }

    private UUID newNotification(UUID template) {
        return notifications.insertIfAbsent(tenant, consumer, template, Channel.SMS,
                UUID.randomUUID().toString(), null).id();
    }

    private NotificationRequest request(UUID id, UUID template) {
        return new NotificationRequest(id, tenant, consumer, template, Channel.SMS, Map.of("amount", "$100.00"));
    }

    @Test
    void happyPath_sends() {
        UUID id = newNotification(templateId);
        assertEquals(ProcessOutcome.SENT, orchestrator.process(request(id, templateId)));
        Notification n = notifications.findById(tenant, id).orElseThrow();
        assertTrue(n.status() == NotificationStatus.SENT || n.status() == NotificationStatus.DELIVERED);
        assertNotNull(n.sentAt());
    }

    @Test
    void suppressed_whenContactedWithinWindow() {
        // Prior send 1 day ago (inside the 3-day window)
        UUID prior = newNotification(templateId);
        notifications.markSent(prior, clock.instant().minus(Duration.ofDays(1)));

        UUID id = newNotification(templateId);
        assertEquals(ProcessOutcome.SUPPRESSED, orchestrator.process(request(id, templateId)));
        assertEquals(NotificationStatus.SUPPRESSED, notifications.findById(tenant, id).orElseThrow().status());
    }

    @Test
    void sends_whenLastContactOlderThanWindow() {
        // Prior send 4 days ago (outside the 3-day window)
        UUID prior = newNotification(templateId);
        notifications.markSent(prior, clock.instant().minus(Duration.ofDays(4)));

        UUID id = newNotification(templateId);
        assertEquals(ProcessOutcome.SENT, orchestrator.process(request(id, templateId)));
    }

    @Test
    void optedOut_dropped() {
        prefs.optOut(tenant, consumer, "PORTAL", clock.instant());
        UUID id = newNotification(templateId);
        assertEquals(ProcessOutcome.OPTED_OUT, orchestrator.process(request(id, templateId)));
    }

    @Test
    void outsideFdcpaWindow_held() {
        clock.setInstant(Instant.parse("2026-06-15T05:00:00Z")); // 00:00 America/Chicago — outside window
        UUID id = newNotification(templateId);
        assertEquals(ProcessOutcome.HELD, orchestrator.process(request(id, templateId)));
        Notification n = notifications.findById(tenant, id).orElseThrow();
        assertEquals(NotificationStatus.HELD, n.status());
        assertNotNull(n.scheduledAt());
    }

    @Test
    void missingMiniMiranda_throws() {
        UUID badTemplate = templates.insertIfAbsent(tenant, "bad-" + UUID.randomUUID(), Channel.SMS,
                "You owe {{amount}}. Pay now.", true);
        UUID id = newNotification(badTemplate);
        assertThrows(MiniMirandaMissingException.class,
                () -> orchestrator.process(request(id, badTemplate)));
    }
}
