package com.notification.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.config.NotificationProperties;
import com.notification.domain.DomainExceptions.MiniMirandaMissingException;
import com.notification.domain.DomainExceptions.TemplateNotFoundException;
import com.notification.infrastructure.repo.ConsumerPreferenceRepository;
import com.notification.infrastructure.repo.DeliveryLogRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.OutboxRepository;
import com.notification.infrastructure.repo.TemplateRepository;
import com.notification.infrastructure.sender.MessageSender;
import com.notification.infrastructure.sender.SendResult;
import com.notification.infrastructure.sender.SenderRegistry;
import com.notification.model.ConsumerPreferences;
import com.notification.model.NotificationRequest;
import com.notification.model.NotificationStatus;
import com.notification.model.ProcessOutcome;
import com.notification.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a single notification through the compliance/business gates, in order:
 *   opt-out -> 3-day suppression -> FDCPA window (HELD) -> render -> mini-Miranda -> send.
 * The whole method is one transaction so the state change, delivery-log write, and outbox write
 * commit atomically (transactional outbox).
 */
@Service
public class NotificationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(NotificationOrchestrator.class);

    private final ConsumerPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final TemplateRepository templateRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final OutboxRepository outboxRepository;
    private final SuppressionService suppressionService;
    private final MiniMirandaValidator mirandaValidator;
    private final TemplateEngine templateEngine;
    private final SenderRegistry senderRegistry;
    private final NotificationProperties props;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationOrchestrator(ConsumerPreferenceRepository preferenceRepository,
                                    NotificationRepository notificationRepository,
                                    TemplateRepository templateRepository,
                                    DeliveryLogRepository deliveryLogRepository,
                                    OutboxRepository outboxRepository,
                                    SuppressionService suppressionService,
                                    MiniMirandaValidator mirandaValidator,
                                    TemplateEngine templateEngine,
                                    SenderRegistry senderRegistry,
                                    NotificationProperties props,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.preferenceRepository = preferenceRepository;
        this.notificationRepository = notificationRepository;
        this.templateRepository = templateRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.outboxRepository = outboxRepository;
        this.suppressionService = suppressionService;
        this.mirandaValidator = mirandaValidator;
        this.templateEngine = templateEngine;
        this.senderRegistry = senderRegistry;
        this.props = props;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ProcessOutcome process(NotificationRequest req) {
        UUID tenantId = req.tenantId();
        UUID consumerId = req.consumerId();
        Instant now = clock.instant();

        // Step 1: exclusions — strong read from the primary DB (the only DB here).
        Optional<ConsumerPreferences> prefsOpt = preferenceRepository.find(tenantId, consumerId);
        if (prefsOpt.isEmpty()) {
            notificationRepository.updateStatus(req.notificationId(), NotificationStatus.FAILED);
            log.warn("No consumer preferences for {}, failing notification {}", consumerId, req.notificationId());
            return ProcessOutcome.FAILED;
        }
        ConsumerPreferences prefs = prefsOpt.get();

        if (prefs.optedOut()) {
            notificationRepository.updateStatus(req.notificationId(), NotificationStatus.FAILED);
            return ProcessOutcome.OPTED_OUT;
        }

        // Step 1.5: 3-day contact-suppression rule (core requirement).
        if (suppressionService.isSuppressed(tenantId, consumerId, now)) {
            notificationRepository.updateStatus(req.notificationId(), NotificationStatus.SUPPRESSED);
            return ProcessOutcome.SUPPRESSED;
        }

        // Step 2: FDCPA contact window, evaluated in the consumer's local timezone.
        ZoneId zone = ZoneId.of(prefs.timezone());
        LocalTime localNow = LocalTime.ofInstant(now, zone);
        LocalTime windowStart = props.getFdcpa().getWindowStart();
        LocalTime windowEnd = props.getFdcpa().getWindowEnd();
        if (localNow.isBefore(windowStart) || localNow.isAfter(windowEnd)) {
            ZonedDateTime next = ZonedDateTime.ofInstant(now, zone)
                    .plusDays(localNow.isAfter(windowEnd) ? 1 : 0)
                    .with(windowStart);
            notificationRepository.hold(req.notificationId(), next.toInstant());
            return ProcessOutcome.HELD;
        }

        // Step 3: render template.
        Template template = templateRepository.find(tenantId, req.templateId())
                .orElseThrow(() -> new TemplateNotFoundException(req.templateId()));
        String body = templateEngine.render(template.bodyTemplate(), req.variables());

        // Step 4: mini-Miranda (FDCPA mandatory) — throws so the message is never sent non-compliant.
        if (template.requiresMiranda() && !mirandaValidator.isValid(body)) {
            throw new MiniMirandaMissingException(template.id());
        }

        // Step 5: hand off to the (simulated) provider.
        MessageSender sender = senderRegistry.forChannel(req.channel());
        SendResult result = sender.send(consumerId, body);
        if (!result.accepted()) {
            deliveryLogRepository.insertIfAbsent(req.notificationId(), 1, "FAILED",
                    "rej-" + req.notificationId(), result.error());
            notificationRepository.updateStatus(req.notificationId(), NotificationStatus.FAILED);
            return ProcessOutcome.FAILED;
        }

        // Mark SENT + stamp sent_at in the same TX so the suppression check sees committed sends.
        notificationRepository.markSent(req.notificationId(), now);
        deliveryLogRepository.insertIfAbsent(req.notificationId(), 1, "SENT", result.providerMessageId(), null);
        outboxRepository.saveIfAbsent(req.notificationId(), "NOTIFICATION", "NOTIFICATION_SENT",
                toJson(Map.of(
                        "notificationId", req.notificationId().toString(),
                        "tenantId", tenantId.toString(),
                        "consumerId", consumerId.toString(),
                        "channel", req.channel().name())));

        // Simulated delivery receipt (normally arrives async via the provider webhook).
        simulateReceipt(req.notificationId(), result.providerMessageId());
        return ProcessOutcome.SENT;
    }

    private void simulateReceipt(UUID notificationId, String providerMessageId) {
        boolean delivered = ThreadLocalRandom.current().nextDouble() < props.getSender().getDeliveredProbability();
        if (delivered) {
            deliveryLogRepository.updateStatusByProviderId(providerMessageId, "DELIVERED", null);
            notificationRepository.updateStatus(notificationId, NotificationStatus.DELIVERED);
        } else {
            // Delivery failed, but we DID contact the consumer — leave notification status = SENT
            // so the 3-day suppression rule still counts this as a contact attempt.
            deliveryLogRepository.updateStatusByProviderId(providerMessageId, "FAILED", "simulated delivery failure");
        }
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
