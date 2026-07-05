package com.notification.api;

import com.notification.infrastructure.repo.ConsumerPreferenceRepository;
import com.notification.model.ConsumerPreferences;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/consumers")
public class ConsumerController {

    private final ConsumerPreferenceRepository preferenceRepository;

    public ConsumerController(ConsumerPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /** Synchronous strong write — opt-out is FDCPA-critical, no eventual consistency. */
    @PostMapping("/{id}/opt-out")
    public Map<String, Object> optOut(@PathVariable("id") UUID consumerId) {
        UUID tenantId = TenantContext.require();
        Instant now = Instant.now();
        boolean updated = preferenceRepository.optOut(tenantId, consumerId, "PORTAL", now);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "consumer not found");
        }
        return Map.of("opted_out", true, "opted_out_at", now);
    }

    @GetMapping("/{id}/preferences")
    public Map<String, Object> preferences(@PathVariable("id") UUID consumerId) {
        UUID tenantId = TenantContext.require();
        ConsumerPreferences p = preferenceRepository.find(tenantId, consumerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consumer not found"));
        return Map.of(
                "opted_out", p.optedOut(),
                "timezone", p.timezone(),
                "sms_enabled", p.smsEnabled(),
                "email_enabled", p.emailEnabled());
    }
}
