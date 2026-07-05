package com.notification.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resolves tenant_id from the Bearer JWT for /v1/** endpoints and stashes it in {@link TenantContext}.
 * /internal/** (webhooks, admin) and /dev/** endpoints don't require a tenant token.
 */
@Component
@Order(1)
public class TenantContextFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public TenantContextFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean requiresTenant = path.startsWith("/v1/");

        if (!requiresTenant) {
            chain.doFilter(request, response);
            return;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Bearer token");
            return;
        }
        try {
            UUID tenantId = jwtService.parseTenantId(auth.substring("Bearer ".length()).trim());
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
        } finally {
            TenantContext.clear();
        }
    }
}
