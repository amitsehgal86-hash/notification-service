package com.notification.api;

import com.notification.domain.DemoService;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.SeedRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

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

    public AdminController(DemoService demoService, SeedRepository seedRepository,
                           NotificationRepository notificationRepository) {
        this.demoService = demoService;
        this.seedRepository = seedRepository;
        this.notificationRepository = notificationRepository;
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
}
