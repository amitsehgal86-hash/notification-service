package com.notification.infrastructure.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process stand-in for Kafka. The OutboxPoller publishes here; a real deployment would swap
 * this for a KafkaTemplate. Keeps a published counter for observability/tests.
 */
@Component
public class InProcessEventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);
    private final AtomicLong published = new AtomicLong();

    public void publish(String topic, String key, String payload) {
        published.incrementAndGet();
        log.debug("[EVENT-BUS] topic={} key={} payload={}", topic, key, payload);
    }

    public long publishedCount() {
        return published.get();
    }
}
