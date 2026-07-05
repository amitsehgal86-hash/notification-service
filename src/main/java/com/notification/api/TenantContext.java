package com.notification.api;

import java.util.UUID;

/**
 * Holds the tenant id resolved from the JWT for the current request thread.
 * ARCHITECTURAL RULE: tenant_id always comes from the token, never from the request body.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID require() {
        UUID t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return t;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
