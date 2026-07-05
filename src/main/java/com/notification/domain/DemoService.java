package com.notification.domain;

import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.SeedRepository;
import com.notification.infrastructure.repo.TemplateRepository;
import com.notification.model.Channel;
import com.notification.model.NotificationRequest;
import com.notification.model.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bulk seeding + a synchronous load-runner that pushes N notifications straight through the
 * orchestrator and reports the outcome split (SENT / SUPPRESSED / HELD / OPTED_OUT / FAILED).
 * This is the demo money-shot: it shows the 3-day rule filtering traffic at scale.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    /** Default demo tenant used when a caller doesn't supply one. */
    public static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String DEFAULT_TEMPLATE_NAME = "demo-collection-sms";
    private static final String DEFAULT_TEMPLATE_BODY =
            "This is an attempt to collect a debt and any information obtained will be used for that "
            + "purpose. Your balance is {{amount}}. Reply STOP to opt out.";

    private final SeedRepository seedRepository;
    private final TemplateRepository templateRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationOrchestrator orchestrator;
    private final Clock clock;

    public DemoService(SeedRepository seedRepository,
                       TemplateRepository templateRepository,
                       NotificationRepository notificationRepository,
                       NotificationOrchestrator orchestrator,
                       Clock clock) {
        this.seedRepository = seedRepository;
        this.templateRepository = templateRepository;
        this.notificationRepository = notificationRepository;
        this.orchestrator = orchestrator;
        this.clock = clock;
    }

    public UUID ensureDefaultTemplate(UUID tenantId) {
        return templateRepository.insertIfAbsent(tenantId, DEFAULT_TEMPLATE_NAME, Channel.SMS,
                DEFAULT_TEMPLATE_BODY, true);
    }

    public Map<String, Object> seed(UUID tenantId, long consumers, double historyFraction) {
        long start = clock.millis();
        UUID templateId = ensureDefaultTemplate(tenantId);
        int insertedConsumers = seedRepository.seedConsumers(tenantId, consumers);
        int history = historyFraction > 0
                ? seedRepository.seedRecentHistory(tenantId, templateId, historyFraction) : 0;
        long elapsed = clock.millis() - start;
        long total = seedRepository.countConsumers(tenantId);
        log.info("Seeded {} consumers ({} total) + {} history rows for tenant {} in {} ms",
                insertedConsumers, total, history, tenantId, elapsed);
        return Map.of(
                "tenantId", tenantId,
                "templateId", templateId,
                "consumersInserted", insertedConsumers,
                "consumersTotal", total,
                "historyRowsInserted", history,
                "elapsedMs", elapsed);
    }

    public Map<String, Object> runLoad(UUID tenantId, int count, int threads) {
        UUID templateId = ensureDefaultTemplate(tenantId);
        List<UUID> consumers = seedRepository.sampleConsumers(tenantId, count);
        if (consumers.isEmpty()) {
            throw new IllegalStateException("No consumers for tenant " + tenantId + " — run /internal/seed first");
        }

        Map<ProcessOutcome, LongAdder> tally = new ConcurrentHashMap<>();
        for (ProcessOutcome o : ProcessOutcome.values()) {
            tally.put(o, new LongAdder());
        }
        LongAdder errors = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        long start = clock.millis();
        try {
            for (UUID consumerId : consumers) {
                pool.submit(() -> {
                    try {
                        var ins = notificationRepository.insertIfAbsent(
                                tenantId, consumerId, templateId, Channel.SMS,
                                UUID.randomUUID().toString(), null);
                        ProcessOutcome outcome = orchestrator.process(new NotificationRequest(
                                ins.id(), tenantId, consumerId, templateId, Channel.SMS,
                                Map.of("amount", "$100.00")));
                        tally.get(outcome).increment();
                    } catch (Exception e) {
                        errors.increment();
                        tally.get(ProcessOutcome.FAILED).increment();
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        long elapsed = clock.millis() - start;

        long sent = tally.get(ProcessOutcome.SENT).sum();
        double perSec = elapsed > 0 ? (consumers.size() * 1000.0 / elapsed) : 0;
        log.info("Load run: {} processed in {} ms ({} SENT, {} SUPPRESSED, {} HELD, {} OPTED_OUT, {} FAILED)",
                consumers.size(), elapsed, sent,
                tally.get(ProcessOutcome.SUPPRESSED).sum(), tally.get(ProcessOutcome.HELD).sum(),
                tally.get(ProcessOutcome.OPTED_OUT).sum(), tally.get(ProcessOutcome.FAILED).sum());

        return Map.of(
                "tenantId", tenantId,
                "requested", count,
                "processed", consumers.size(),
                "sent", sent,
                "suppressed_3day", tally.get(ProcessOutcome.SUPPRESSED).sum(),
                "held_outside_window", tally.get(ProcessOutcome.HELD).sum(),
                "opted_out", tally.get(ProcessOutcome.OPTED_OUT).sum(),
                "failed", tally.get(ProcessOutcome.FAILED).sum(),
                "elapsedMs", elapsed,
                "throughputPerSec", Math.round(perSec));
    }
}
