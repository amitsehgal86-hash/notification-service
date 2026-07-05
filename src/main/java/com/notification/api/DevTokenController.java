package com.notification.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Dev convenience: mint a tenant JWT for demos/tests. Disable in production via
 * {@code notification.dev.token-endpoint-enabled=false}.
 */
@RestController
@ConditionalOnProperty(prefix = "notification.dev", name = "token-endpoint-enabled",
        havingValue = "true", matchIfMissing = true)
public class DevTokenController {

    private final JwtService jwtService;

    public DevTokenController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/dev/token")
    public Map<String, Object> token(@RequestParam(value = "tenantId", required = false) UUID tenantId) {
        UUID tid = tenantId != null ? tenantId : UUID.randomUUID();
        return Map.of("tenantId", tid, "token", jwtService.mint(tid));
    }
}
