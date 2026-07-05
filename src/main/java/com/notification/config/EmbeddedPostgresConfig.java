package com.notification.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boots a REAL PostgreSQL instance embedded in-process (Zonky). The binary is extracted
 * from the fat JAR on first run; the data directory persists across restarts so seeded
 * data travels with the app folder — true plug-and-play, no external Postgres to install.
 */
@Configuration
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(NotificationProperties props) throws Exception {
        Path dataDir = Path.of(props.getDb().getDataDir()).toAbsolutePath().normalize();
        Files.createDirectories(dataDir.getParent() == null ? dataDir : dataDir.getParent());

        boolean firstRun = !Files.exists(dataDir.resolve("PG_VERSION"));
        log.info("Starting embedded PostgreSQL 16 at {} (port {}), first-run={}",
                dataDir, props.getDb().getPort(), firstRun);

        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(props.getDb().getPort())
                .setDataDirectory(new File(dataDir.toString()))
                .setCleanDataDirectory(false)      // keep data across restarts
                .start();

        log.info("Embedded PostgreSQL ready: {}", pg.getJdbcUrl("postgres", "postgres"));
        return pg;
    }

    /**
     * Pooled DataSource over the embedded cluster. HikariCP ships with spring-boot-starter-jdbc.
     * Marked {@code @FlywayDataSource} so Flyway migrates this exact cluster.
     */
    @Bean
    @Primary
    @FlywayDataSource
    public DataSource dataSource(EmbeddedPostgres pg, NotificationProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
        cfg.setUsername("postgres");
        cfg.setMaximumPoolSize(props.getDb().getPoolSize());
        cfg.setPoolName("embedded-pg-pool");
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }
}
