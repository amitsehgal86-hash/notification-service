package com.notification.api;

import com.notification.domain.DemoService;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.SeedRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.notification.model.Notification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Demo/admin endpoints (no tenant JWT required — tenantId is an explicit parameter, defaulting to
 * the demo tenant). These exist to make the app demoable at scale; they are not part of the
 * consumer-facing contract.
 */
@RestController
@RequestMapping("/internal")
public class AdminController {

    private final DemoService demoService;
    private final SeedRepository seedRepository;
    private final NotificationRepository notificationRepository;
    private final MeterRegistry meterRegistry;

    public AdminController(DemoService demoService, SeedRepository seedRepository,
                           NotificationRepository notificationRepository,
                           MeterRegistry meterRegistry) {
        this.demoService = demoService;
        this.seedRepository = seedRepository;
        this.notificationRepository = notificationRepository;
        this.meterRegistry = meterRegistry;
    }

    /** Bulk-generate consumers (server-side generate_series) + a default template + recent history. */
    @PostMapping("/seed")
    public Map<String, Object> seed(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "consumers", defaultValue = "1000000") long consumers,
            @RequestParam(value = "historyFraction", defaultValue = "0.3") double historyFraction) {
        UUID tid = tenantId != null ? tenantId : DemoService.DEMO_TENANT;
        return demoService.seed(tid, consumers, historyFraction);
    }

    /** Push N notifications through the pipeline and return the outcome split. */
    @PostMapping("/demo/load")
    public Map<String, Object> load(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "count", defaultValue = "50000") int count,
            @RequestParam(value = "threads", defaultValue = "16") int threads) {
        UUID tid = tenantId != null ? tenantId : DemoService.DEMO_TENANT;
        return demoService.runLoad(tid, count, threads);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(value = "tenantId", required = false) UUID tenantId) {
        UUID tid = tenantId != null ? tenantId : DemoService.DEMO_TENANT;
        return Map.of(
                "tenantId", tid,
                "consumers", seedRepository.countConsumers(tid),
                "notifications", notificationRepository.count(tid));
    }

    /**
     * Returns the most recently sent/delivered notifications with the raw DB query time.
     * Designed to demonstrate query performance against a large table.
     */
    @GetMapping("/recent-sends")
    public Map<String, Object> recentSends(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        UUID tid = tenantId != null ? tenantId : DemoService.DEMO_TENANT;
        int cappedLimit = Math.min(limit, 500);

        long startNs = System.nanoTime();
        List<Notification> rows = notificationRepository.listRecentSends(tid, cappedLimit);
        double queryMs = Math.round((System.nanoTime() - startNs) / 1_000_000.0 * 100.0) / 100.0;

        long tableTotal = notificationRepository.count(tid);

        List<Map<String, Object>> data = rows.stream().map(n -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.id());
            row.put("consumer_id", n.consumerId());
            row.put("channel", n.channel());
            row.put("status", n.status());
            row.put("sent_at", n.sentAt());
            return row;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_ms", queryMs);
        result.put("rows_fetched", rows.size());
        result.put("table_total_notifications", tableTotal);
        result.put("limit", cappedLimit);
        result.put("sql", "SELECT * FROM notifications WHERE tenant_id = ? AND status IN ('SENT','DELIVERED') ORDER BY sent_at DESC LIMIT ?");
        result.put("data", data);
        return result;
    }

    @GetMapping("/timing")
    public Map<String, Object> timing() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suppression_db_query", timerStats("notification.suppression.query.db"));
        result.put("suppression_check", timerStats("notification.suppression.check"));
        result.put("orchestrator", timerStats("notification.process"));
        return result;
    }

    private Map<String, Object> timerStats(String name) {
        Timer timer = meterRegistry.find(name).timer();
        if (timer == null || timer.count() == 0) {
            return Map.of("count", 0, "avg_ms", 0.0, "max_ms", 0.0);
        }
        double avgMs = Math.round(timer.mean(TimeUnit.MILLISECONDS) * 100.0) / 100.0;
        double maxMs = Math.round(timer.max(TimeUnit.MILLISECONDS) * 100.0) / 100.0;
        return Map.of("count", timer.count(), "avg_ms", avgMs, "max_ms", maxMs);
    }
}
