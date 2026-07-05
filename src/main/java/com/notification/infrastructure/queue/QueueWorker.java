package com.notification.infrastructure.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.config.NotificationProperties;
import com.notification.domain.NotificationOrchestrator;
import com.notification.infrastructure.repo.DeadLetterRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.NotificationRequest;
import com.notification.model.NotificationStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Pool of worker threads that drain {@link NotificationQueue} and run each item through the
 * orchestrator. This is the async processor (replaces an SQS @SqsListener). Failures are recorded
 * to the dead_letter_messages table.
 */
@Component
public class QueueWorker {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final NotificationQueue queue;
    private final NotificationOrchestrator orchestrator;
    private final NotificationRepository notificationRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final NotificationProperties props;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private ExecutorService pool;
    private volatile boolean running = true;

    public QueueWorker(NotificationQueue queue,
                       NotificationOrchestrator orchestrator,
                       NotificationRepository notificationRepository,
                       DeadLetterRepository deadLetterRepository,
                       NotificationProperties props,
                       ObjectMapper objectMapper,
                       Clock clock) {
        this.queue = queue;
        this.orchestrator = orchestrator;
        this.notificationRepository = notificationRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.props = props;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostConstruct
    public void start() {
        int workers = props.getQueue().getWorkers();
        pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r);
            t.setName("notif-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workers; i++) {
            pool.submit(this::runLoop);
        }
        log.info("Started {} notification queue workers", workers);
    }

    private void runLoop() {
        while (running) {
            try {
                NotificationRequest req = queue.poll(1, TimeUnit.SECONDS);
                if (req != null) {
                    handle(req);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Unexpected worker error", e);
            }
        }
    }

    private void handle(NotificationRequest req) {
        try {
            orchestrator.process(req);
        } catch (Exception e) {
            log.warn("Notification {} failed: {}", req.notificationId(), e.getMessage());
            safeMarkFailed(req);
            safeDeadLetter(req, e);
        }
    }

    private void safeMarkFailed(NotificationRequest req) {
        try {
            notificationRepository.updateStatus(req.notificationId(), NotificationStatus.FAILED);
        } catch (Exception ignore) {
            // best effort
        }
    }

    private void safeDeadLetter(NotificationRequest req, Exception cause) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "notificationId", req.notificationId().toString(),
                    "consumerId", req.consumerId().toString(),
                    "templateId", req.templateId().toString(),
                    "channel", req.channel().name()));
            deadLetterRepository.record(req.tenantId(), "notification-queue",
                    req.notificationId().toString(), req.notificationId(), payload,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(), clock.instant());
        } catch (Exception ignore) {
            // best effort
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }
}
