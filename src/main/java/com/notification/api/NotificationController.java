package com.notification.api;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.domain.NotificationSubmissionService;
import com.notification.infrastructure.repo.DeliveryLogRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.Notification;
import com.notification.model.NotificationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationSubmissionService submissionService;
    private final NotificationRepository notificationRepository;
    private final DeliveryLogRepository deliveryLogRepository;

    public NotificationController(NotificationSubmissionService submissionService,
                                  NotificationRepository notificationRepository,
                                  DeliveryLogRepository deliveryLogRepository) {
        this.submissionService = submissionService;
        this.notificationRepository = notificationRepository;
        this.deliveryLogRepository = deliveryLogRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreateNotificationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
            throws InterruptedException {

        UUID tenantId = TenantContext.require();
        String idemKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString() : idempotencyKey;

        var submission = submissionService.submit(
                tenantId, req.consumerId(), req.templateId(), req.channel(),
                idemKey, req.scheduledAt(), req.variables());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "id", submission.id(),
                "status", submission.status()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        Notification n = notificationRepository.findById(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found"));
        Map<String, Object> body = toMap(n);
        body.put("delivery_log", deliveryLogRepository.findByNotification(id));
        return body;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "consumer_id", required = false) UUID consumerId,
            @RequestParam(value = "status", required = false) NotificationStatus status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        UUID tenantId = TenantContext.require();
        List<Notification> rows = notificationRepository.list(tenantId, consumerId, status, Math.min(limit, 1000));
        return Map.of("data", rows.stream().map(NotificationController::toMap).toList(), "total", rows.size());
    }

    private static Map<String, Object> toMap(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("consumer_id", n.consumerId());
        m.put("template_id", n.templateId());
        m.put("channel", n.channel());
        m.put("status", n.status());
        m.put("scheduled_at", n.scheduledAt());
        m.put("sent_at", n.sentAt());
        m.put("created_at", n.createdAt());
        return m;
    }
}
