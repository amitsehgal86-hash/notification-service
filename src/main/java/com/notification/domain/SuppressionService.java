package com.notification.domain;

import com.notification.config.NotificationProperties;
import com.notification.infrastructure.repo.NotificationRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The core business rule: do NOT contact a consumer if we already sent them a notification within
 * the configured window (default 3 days), regardless of channel. Backed by a partial composite
 * index so the check stays fast at 1M+ rows.
 */
@Service
public class SuppressionService {

    private final NotificationRepository notificationRepository;
    private final NotificationProperties props;

    public SuppressionService(NotificationRepository notificationRepository, NotificationProperties props) {
        this.notificationRepository = notificationRepository;
        this.props = props;
    }

    public boolean isSuppressed(UUID tenantId, UUID consumerId, Instant now) {
        Instant since = now.minus(Duration.ofDays(props.getSuppression().getWindowDays()));
        return notificationRepository.wasContactedSince(tenantId, consumerId, since);
    }

    public int windowDays() {
        return props.getSuppression().getWindowDays();
    }
}
