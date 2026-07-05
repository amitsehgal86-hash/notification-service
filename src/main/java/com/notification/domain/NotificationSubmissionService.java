package com.notification.domain;

import com.notification.infrastructure.queue.NotificationQueue;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.Channel;
import com.notification.model.NotificationRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Accepts a notification: writes the row idempotently, then enqueues it for async processing.
 * If it carries a future scheduled_at it is stored HELD and released later by the scheduler.
 */
@Service
public class NotificationSubmissionService {

    private final NotificationRepository notificationRepository;
    private final NotificationQueue queue;

    public NotificationSubmissionService(NotificationRepository notificationRepository, NotificationQueue queue) {
        this.notificationRepository = notificationRepository;
        this.queue = queue;
    }

    public record Submission(UUID id, String status, boolean created) {}

    public Submission submit(UUID tenantId, UUID consumerId, UUID templateId, Channel channel,
                             String idempotencyKey, Instant scheduledAt, Map<String, Object> variables)
            throws InterruptedException {

        var result = notificationRepository.insertIfAbsent(
                tenantId, consumerId, templateId, channel, idempotencyKey, scheduledAt);

        // Only enqueue on first creation and only when it should be sent now (no future schedule).
        if (result.created() && scheduledAt == null) {
            queue.enqueue(new NotificationRequest(
                    result.id(), tenantId, consumerId, templateId, channel, variables));
        }
        String status = scheduledAt != null ? "HELD" : "PENDING";
        return new Submission(result.id(), status, result.created());
    }
}
