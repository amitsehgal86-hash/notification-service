package com.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * All tunables for the service. Prefix: {@code notification.*} (see application.yml).
 */
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private final Db db = new Db();
    private final Suppression suppression = new Suppression();
    private final Fdcpa fdcpa = new Fdcpa();
    private final Sender sender = new Sender();
    private final Queue queue = new Queue();
    private final Jwt jwt = new Jwt();
    private final Dev dev = new Dev();

    public Db getDb() { return db; }
    public Suppression getSuppression() { return suppression; }
    public Fdcpa getFdcpa() { return fdcpa; }
    public Sender getSender() { return sender; }
    public Queue getQueue() { return queue; }
    public Jwt getJwt() { return jwt; }
    public Dev getDev() { return dev; }

    /** Embedded PostgreSQL settings. */
    public static class Db {
        /** Data directory for the embedded Postgres cluster; persists across restarts. */
        private String dataDir = "./data/pg";
        /** Fixed port so the cluster is reachable/debuggable across runs. */
        private int port = 54329;
        /** JDBC connection pool size. */
        private int poolSize = 20;

        public String getDataDir() { return dataDir; }
        public void setDataDir(String dataDir) { this.dataDir = dataDir; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }

    /** The 3-day contact-suppression rule (this build's core rule). */
    public static class Suppression {
        /** Do not contact a consumer if we already did within this many days (any channel). */
        private int windowDays = 3;

        public int getWindowDays() { return windowDays; }
        public void setWindowDays(int windowDays) { this.windowDays = windowDays; }
    }

    /** FDCPA contact window, evaluated in the consumer's local timezone. */
    public static class Fdcpa {
        private LocalTime windowStart = LocalTime.of(8, 0);
        private LocalTime windowEnd = LocalTime.of(21, 0);

        public LocalTime getWindowStart() { return windowStart; }
        public void setWindowStart(LocalTime windowStart) { this.windowStart = windowStart; }
        public LocalTime getWindowEnd() { return windowEnd; }
        public void setWindowEnd(LocalTime windowEnd) { this.windowEnd = windowEnd; }
    }

    /** Simulated SMS/email provider behavior. */
    public static class Sender {
        /** Probability a simulated send is later marked DELIVERED (else FAILED). */
        private double deliveredProbability = 0.9;

        public double getDeliveredProbability() { return deliveredProbability; }
        public void setDeliveredProbability(double deliveredProbability) { this.deliveredProbability = deliveredProbability; }
    }

    /** In-JVM work queue (replaces SQS). */
    public static class Queue {
        private int workers = 8;
        private int capacity = 100_000;

        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
    }

    /** Dev JWT signing (tenant_id claim). Replace the secret in production. */
    public static class Jwt {
        private String secret = "dev-secret-please-change-me-0000000000000000000000";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    public static class Dev {
        /** Exposes GET /dev/token to mint tenant JWTs for demos. */
        private boolean tokenEndpointEnabled = true;

        public boolean isTokenEndpointEnabled() { return tokenEndpointEnabled; }
        public void setTokenEndpointEnabled(boolean tokenEndpointEnabled) { this.tokenEndpointEnabled = tokenEndpointEnabled; }
    }
}
