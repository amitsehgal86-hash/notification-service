package com.notification.scheduler;

import com.notification.infrastructure.queue.NotificationQueue;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.Notification;
import com.notification.model.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Releases HELD notifications whose scheduled_at has arrived (e.g. a message held overnight until
 * 8am consumer-local). Uses FOR UPDATE SKIP LOCKED so multiple instances never double-release.
 */
@Component
@Profile("!test")
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final int BATCH = 500;

    private final NotificationRepository notificationRepository;
    private final NotificationQueue queue;
    private final Clock clock;

    public NotificationScheduler(NotificationRepository notificationRepository,
                                 NotificationQueue queue, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.queue = queue;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void releaseHeldNotifications() {
        List<Notification> claimed = notificationRepository.claimHeld(clock.instant(), BATCH);
        if (claimed.isEmpty()) {
            return;
        }
        for (Notification n : claimed) {
            try {
                // Variables aren't persisted (demo simplification); re-render uses template defaults.
                // The hardcoded mini-Miranda still renders, so the released message stays compliant.
                queue.enqueue(new NotificationRequest(
                        n.id(), n.tenantId(), n.consumerId(), n.templateId(), n.channel(), Map.of()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Released {} HELD notifications", claimed.size());
    }
}
