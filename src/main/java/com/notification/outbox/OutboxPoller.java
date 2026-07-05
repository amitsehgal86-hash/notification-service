package com.notification.outbox;

import com.notification.infrastructure.queue.InProcessEventBus;
import com.notification.infrastructure.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes pending outbox rows to the (in-process) event bus, then marks them published.
 * Publish-then-mark ordering: a crash after publish re-publishes (consumers are idempotent);
 * we never mark-before-publish (that would lose events).
 */
@Component
@Profile("!test")
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final InProcessEventBus eventBus;

    public OutboxPoller(OutboxRepository outboxRepository, InProcessEventBus eventBus) {
        this.outboxRepository = outboxRepository;
        this.eventBus = eventBus;
    }

    @Scheduled(fixedDelay = 5_000)
    public void poll() {
        List<Map<String, Object>> pending = outboxRepository.findPending(100);
        for (Map<String, Object> row : pending) {
            UUID id = (UUID) row.get("id");
            try {
                eventBus.publish(
                        resolveTopic((String) row.get("aggregate_type")),
                        String.valueOf(row.get("aggregate_id")),
                        String.valueOf(row.get("payload")));
                outboxRepository.markPublished(id);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}", id, e);
                // leave pending; retried next poll
            }
        }
    }

    private String resolveTopic(String aggregateType) {
        return "notification.events";
    }
}
