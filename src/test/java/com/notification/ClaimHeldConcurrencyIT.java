package com.notification;

import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.model.Channel;
import com.notification.model.Notification;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two threads racing to release the same HELD rows. FOR UPDATE SKIP LOCKED must guarantee each row
 * is claimed exactly once — no duplicates, none lost.
 */
class ClaimHeldConcurrencyIT extends AbstractIntegrationTest {

    @Autowired NotificationRepository notifications;
    @Autowired MutableClock clock;

    @Test
    void twoThreadsClaimEachRowExactlyOnce() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        int rows = 200;
        var scheduled = clock.instant().minus(Duration.ofMinutes(1));
        for (int i = 0; i < rows; i++) {
            notifications.insertIfAbsent(tenant, UUID.randomUUID(), UUID.randomUUID(), Channel.SMS,
                    "held-" + UUID.randomUUID(), scheduled);
        }

        ConcurrentLinkedQueue<UUID> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        Runnable claimer = () -> {
            try {
                start.await();
                List<Notification> batch;
                do {
                    batch = notifications.claimHeld(clock.instant(), 25);
                    batch.stream()
                            .filter(n -> n.tenantId().equals(tenant))
                            .forEach(n -> claimed.add(n.id()));
                } while (!batch.isEmpty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread t1 = new Thread(claimer);
        Thread t2 = new Thread(claimer);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        Set<UUID> distinct = new HashSet<>(claimed);
        assertEquals(rows, distinct.size(), "every HELD row should be claimed once");
        assertEquals(claimed.size(), distinct.size(), "no row should be claimed twice");
    }
}
