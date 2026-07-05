package com.notification.api;

import com.notification.config.NotificationProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Dev-grade HS256 JWT minting/parsing. The only claim that matters here is {@code tenant_id}.
 * In production this would be replaced by the real IdP's tokens.
 */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(NotificationProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String mint(UUID tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(tenantId.toString())
                .claim("tenant_id", tenantId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(24, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** Returns the tenant_id claim, or throws if the token is invalid/expired. */
    public UUID parseTenantId(String token) {
        String tid = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("tenant_id", String.class);
        return UUID.fromString(tid);
    }
}
