package com.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Base for integration tests: boots the full app (which starts a real embedded PostgreSQL under
 * target/test-pg) with the "test" profile (schedulers off) and a controllable clock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            // Default: 2026-06-15 15:00 UTC — inside the 08:00-21:00 window for US timezones.
            return new MutableClock(Instant.parse("2026-06-15T15:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        public Clock clock(MutableClock mutableClock) {
            return mutableClock;
        }
    }
}
