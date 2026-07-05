package com.notification.api;

import com.notification.infrastructure.repo.DeliveryLogRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivery-receipt webhooks (Twilio/SendGrid). Real impls would verify the provider signature and
 * ack fast + process async; here we accept a normalized JSON body and update delivery state.
 * Idempotent because delivery_log rows are keyed by provider_message_id.
 */
@RestController
@RequestMapping("/internal/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final DeliveryLogRepository deliveryLogRepository;
    private final NotificationRepository notificationRepository;

    public WebhookController(DeliveryLogRepository deliveryLogRepository,
                             NotificationRepository notificationRepository) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.notificationRepository = notificationRepository;
    }

    @PostMapping("/twilio")
    public ResponseEntity<Void> twilio(@RequestBody Map<String, Object> body) {
        String pmid = firstNonNull(body, "MessageSid", "provider_message_id");
        String status = firstNonNull(body, "MessageStatus", "status");
        applyReceipt(pmid, status);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sendgrid")
    public ResponseEntity<Void> sendgrid(@RequestBody Map<String, Object> body) {
        String pmid = firstNonNull(body, "sg_message_id", "provider_message_id");
        String status = firstNonNull(body, "event", "status");
        applyReceipt(pmid, status);
        return ResponseEntity.ok().build();
    }

    @Transactional
    protected void applyReceipt(String providerMessageId, String rawStatus) {
        if (providerMessageId == null || rawStatus == null) {
            log.warn("Delivery receipt missing provider id or status");
            return;
        }
        boolean delivered = rawStatus.equalsIgnoreCase("delivered");
        String logStatus = delivered ? "DELIVERED" : "FAILED";
        deliveryLogRepository.updateStatusByProviderId(providerMessageId, logStatus,
                delivered ? null : "receipt: " + rawStatus);

        if (delivered) {
            Optional<UUID> notificationId = deliveryLogRepository.findNotificationIdByProviderId(providerMessageId);
            notificationId.ifPresent(id -> notificationRepository.updateStatus(id, NotificationStatus.DELIVERED));
        }
    }

    private static String firstNonNull(Map<String, Object> body, String... keys) {
        for (String k : keys) {
            Object v = body.get(k);
            if (v != null) {
                return v.toString();
            }
        }
        return null;
    }
}
