# Notification Service — Demo & Code Guide

A reference for demoing the dashboard and walking through the codebase design.

---

## Part 1 — Dashboard Walkthrough

### How to start

```bash
java -jar target/notification-service.jar
```

Open **http://localhost:8080**

---

### Section 1 — Header (always visible, sticky)

**What's on screen:** `Notification Service v1.0.0 · FDCPA-compliant · embedded PostgreSQL` | green LIVE dot | **Consumers: 7,000,000 · Notifications: 4,000,000+**

**What to say:**
> "This is a live counter polling the embedded database every 3 seconds. We have 7 million consumers and 4 million notifications in a real PostgreSQL 16 instance running inside a single JAR. No Docker, no external database, no cloud account. The scale is real."

---

### Section 2 — Processing Pipeline

Four cards left to right. Step 2 is highlighted in orange with a "KEY DESIGN" badge.

#### Step 1 — Opt-out Check (blue)

> "First gate. One strong synchronous read from `consumer_preferences`. That single query loads both the opt-out flag AND the consumer's timezone — both reused downstream. No eventual consistency because FDCPA doesn't allow it. If a consumer opts out at 11:59pm, no notification goes out at midnight."

- **DB cost:** 1 read from `consumer_preferences`
- **Serves:** opt-out check AND timezone for Step 3

#### Step 2 — 3-Day Suppression (orange — KEY DESIGN)

> "The core design problem. Do not contact a consumer we already reached in the last 3 days, across any channel. The query is on the card: `SELECT EXISTS` with `status IN ('SENT','DELIVERED') AND sent_at > :since`. The interesting part is the index — partial composite, only indexing rows where status is SENT or DELIVERED. Postgres satisfies this EXISTS with an index-only scan."

- **DB cost:** 1 EXISTS query on `notifications`
- **Avg time:** 0.10ms at 4M+ rows
- **Why fast:** partial composite index `(tenant_id, consumer_id, sent_at DESC) WHERE status IN ('SENT', 'DELIVERED')`

#### Step 3 — FDCPA Window (violet)

> "Contact only between 8am and 6pm in the consumer's own timezone — not ours. The timezone came from the Step 1 read, so no extra DB call here. Pure arithmetic: convert the current instant to the consumer's local time, check if it falls in the window. If not, HELD until 8am local time the next day."

- **DB cost:** zero — uses timezone already loaded in Step 1
- **Logic:** `LocalTime.ofInstant(now, ZoneId.of(prefs.timezone()))`

#### Steps 4–5 — Render & Send (green)

> "Template render, mini-Miranda validation — FDCPA requires specific debt collection language — and a simulated send via Twilio/SendGrid. Every send commits an outbox event in the same database transaction so nothing is lost if the process crashes mid-flight."

- **DB cost:** 1 template read + 3 writes (status, delivery log, outbox) — all in one transaction

---

### Section 3 — Index Callout Box

```sql
CREATE INDEX idx_notifications_recent_contact
ON notifications (tenant_id, consumer_id, sent_at DESC)
WHERE status IN ('SENT', 'DELIVERED')
```

> "Three decisions in this index. First, it's **partial** — the WHERE clause means only SENT and DELIVERED rows are indexed. Failed, suppressed, and pending rows are excluded, so the index stays small relative to the total table. Second, **column order is deliberate**: tenant first (multi-tenant isolation), then consumer, then sent_at descending — exactly matching the query shape. Third, Postgres can satisfy the EXISTS with an **index-only scan**, never touching the main table heap."

---

### Section 4 — FDCPA Contact Window (Timezone Grid)

Five live timezone cards — ET, CT, MT, AZ, PT. Updates every 30 seconds.

> "Consumers are seeded across all 5 US time zones. This panel shows which zones are contactable right now. If you come back after 6pm ET, New York flips to CLOSED. Any notification queued for an East Coast consumer at that point gets HELD — you'll see that in the load test results as FDCPA Hold count."

**Demo tip:** Run the load test after 6pm ET to see East Coast consumers produce HELD notifications — that's the better demo because the rule fires in real time.

---

### Section 5 — Demo Controls + Query Timing

#### Smart Load Test (left panel)

> "This isn't a random sample. Before touching the orchestrator, it runs one query against `consumer_preferences` with a NOT EXISTS subquery on `notifications` — combining all three eligibility filters: not opted-out, timezone currently in the window, not contacted in the last 3 days. Only consumers who pass all three come through."

Click **Run** → wait ~4 seconds → results appear below.

#### Query Timing (right panel)

Three Micrometer timer cards:

| Metric | What it measures |
|--------|-----------------|
| **Suppression DB Query** (`notification.suppression.query.db`) | Raw JDBC round-trip for the EXISTS check |
| **Suppression Check** (`notification.suppression.check`) | Service layer including the DB call above |
| **Full Orchestrator** (`notification.process`) | End-to-end: preferences read + suppression + timezone + render + send |

> "The suppression DB query averages **0.10 milliseconds** against 4 million rows. That's what the partial composite index buys. The full orchestrator end-to-end averages under 1 millisecond."

---

### Section 6 — Smart Load Test Results

After clicking Run:

- **SENT** — consumers the DB pre-filter found eligible and the orchestrator confirmed
- **Suppressed** — caught by the 3-day rule inside the orchestrator (should be near 0 — smart filter already excluded them)
- **FDCPA Hold** — timezone closed between the pre-filter and orchestrator execution (rare edge case)
- **Opted Out** — opted out between pre-filter and orchestrator (race condition demonstration)
- **Failed** — send failure

Footer shows: `Eligible (DB pre-filter): N · Open zones: New York, Chicago…`

> "If you run the smart load twice in a row, the second run finds far fewer eligible consumers because the first run just suppressed them. That's the 3-day rule working."

---

### Section 7 — Recent Sends — DB Query Inspector

SQL shown: `SELECT * FROM notifications WHERE tenant_id = ? AND status IN ('SENT','DELIVERED') ORDER BY sent_at DESC LIMIT ?`

> "This query takes ~170ms for 50 rows from 4 million. Compare that to 0.10ms for the suppression check. The difference is index shape — not missing index."

**Why it's slow vs the suppression check:**

| | Suppression Query | Recent Sends Query |
|---|---|---|
| Filter | `tenant_id = X AND consumer_id = Y` | `tenant_id = X` only |
| Index columns | `(tenant_id, consumer_id, sent_at DESC)` | Same index |
| Index usage | Equality on col 1 & 2, range on col 3 — perfect | Equality on col 1, but `consumer_id` sits before `sent_at` — can't ORDER BY globally |
| Result | Index-only scan: **0.10ms** | Scans all consumers under tenant, sorts: **~170ms** |

> "To fix this you'd add a separate index: `(tenant_id, sent_at DESC) WHERE status IN ('SENT','DELIVERED')`. We don't add it because this is a low-frequency monitoring endpoint. Every extra index adds write overhead to every INSERT on the notifications table — for the high-frequency suppression check that cost is justified; for a dashboard endpoint it isn't."

---

## Part 2 — Code Walkthrough

### Package structure

```
com.notification
├── api/                  HTTP layer — controllers, JWT, tenant filter
├── config/               Spring config — embedded Postgres, properties
├── domain/               Business logic — orchestrator, suppression, validation
├── infrastructure/
│   ├── queue/            Async processing — in-memory queue, worker threads
│   ├── repo/             All database access
│   └── sender/           Provider abstraction — Twilio/SendGrid simulated
├── model/                Immutable records — Notification, Template, etc.
├── outbox/               Background outbox publisher
├── scheduler/            HELD notification release
└── tui/                  Terminal UI (bundled in same JAR)
```

---

### Layer 0 — Bootstrap & Database (`config/`)

#### `EmbeddedPostgresConfig.java`

Two beans:

1. **`embeddedPostgres`** — extracts the Postgres 16 binary from inside the JAR on first run, starts it on port 54329, data directory at `./data/pg`. `setCleanDataDirectory(false)` is the key line — data persists across restarts.
2. **`dataSource`** — HikariCP pool (20 connections) over the embedded cluster. `@FlywayDataSource` so Flyway migrates this exact instance.

> *"In production: remove this file, wire in a standard JDBC URL from environment variables. Nothing else changes."*

#### `NotificationProperties.java`

Typed `@ConfigurationProperties` binding for `application.yml`:

| Property | Default | Purpose |
|----------|---------|---------|
| `suppression.window-days` | `3` | The 3-day contact window |
| `fdcpa.window-start/end` | `08:00` / `18:00` | Contact hours |
| `db.pool-size` | `20` | HikariCP connections |
| `queue.workers` | `8` | Async worker threads |
| `sender.delivered-probability` | `0.9` | Simulated delivery rate |

#### DB Migrations (`db/migration/V1–V7`)

Flyway runs these at startup in order. Key design decisions embedded in schema:

- **V1** — `notifications` table. `idx_notifications_held` (partial on `status = 'HELD'`) powers the scheduler. `idx_notifications_tenant_consumer` powers list queries.
- **V3** — `consumer_preferences`. `timezone` is `NOT NULL` with a schema comment: *"Import pipeline rejects records without it."* `opted_out` has a comment: *"Always read from primary DB — never from cache or replica."* Design decisions documented at the schema level.
- **V7** — suppression index in its own migration file. The comment explains the O(log n) guarantee. Separate migration means you can trace exactly when and why it was added.

---

### Layer 1 — API (`api/`)

#### `TenantContextFilter.java`

`OncePerRequestFilter` that runs before every request. Only `/v1/**` paths require a JWT. Parses `Authorization: Bearer`, extracts `tenant_id` claim, stores it in a `ThreadLocal` via `TenantContext.set()`. Always clears in `finally` — no tenant leaks across requests on the same thread pool thread.

> *"Tenant isolation is enforced once at the filter, not in every controller. If you forget to check tenant in a new controller, the worst case is a 401, not data leaking between tenants."*

#### `NotificationController.java`

Handles `POST /v1/notifications` and `GET /v1/notifications/{id}`. Reads tenant from `TenantContext`, validates `Idempotency-Key` header, delegates to `NotificationSubmissionService`. Returns **202 Accepted** — persisted and queued but not yet processed.

#### `ConsumerController.java`

Handles opt-out and preferences reads. Opt-out is a synchronous write — response only returns after the DB row commits.

#### `AdminController.java`

`/internal/*` demo endpoints with no JWT. Seed, smart-load, stats, timing, recent-sends. Also reads Micrometer timers and returns them as JSON for the dashboard.

#### `GlobalExceptionHandler.java`

Maps `MiniMirandaMissingException`, `TemplateNotFoundException`, and validation errors to RFC 9457 `application/problem+json` responses.

---

### Layer 2 — Domain (`domain/`) — the heart of the service

#### `NotificationSubmissionService.java`

The ingestion gate. Two responsibilities only:
1. `notificationRepository.insertIfAbsent()` — idempotent insert via `ON CONFLICT DO NOTHING`
2. `queue.enqueue()` — only if it was a new record AND there's no future `scheduledAt`

Does **not** run compliance checks. Those happen async. This keeps 202 response fast.

#### `NotificationOrchestrator.java`

The most important class. The entire compliance gate ladder in one `@Transactional` method.

```
preferenceRepository.find()          → 1 DB read: opted_out + timezone
  ↳ if optedOut → OPTED_OUT
suppressionService.isSuppressed()    → 1 DB read: EXISTS query, partial index
  ↳ if suppressed → SUPPRESSED
timezone window check                → in-memory, uses prefs.timezone()
  ↳ if outside window → HELD (scheduled_at = next 08:00 local)
templateRepository.find()            → 1 DB read: template body
templateEngine.render()              → string interpolation, no DB
mirandaValidator.isValid()           → string check, no DB
sender.send()                        → simulated, no real network
notificationRepository.markSent()    ┐
deliveryLogRepository.insertIfAbsent() ├── committed in one transaction
outboxRepository.saveIfAbsent()      ┘
```

All three writes at the end commit atomically — that's the transactional outbox guarantee. The whole method is wrapped in `processTimer.record()` — that's the "Full Orchestrator" number on the dashboard.

#### `SuppressionService.java`

Deliberately thin. One method: computes `since = now - windowDays` and delegates to `notificationRepository.wasContactedSince()`. Business rule lives in domain; DB call lives in infrastructure. Independently testable with a mock repository.

#### `MiniMirandaValidator.java`

Checks two required FDCPA phrases in the rendered message body after template substitution. Hard-coded — these phrases are legally mandated, not a business setting. Post-render validation means a bad variable substitution can't accidentally strip the clause.

Required phrases:
- `"attempt to collect a debt"`
- `"information obtained will be used for that purpose"`

#### `TemplateEngine.java`

`{{variable}}` interpolation via regex replace over the variables map. No external library dependency.

---

### Layer 3 — Infrastructure / Queue (`infrastructure/queue/`)

#### `NotificationQueue.java`

A `LinkedBlockingQueue` with configurable capacity (default 100,000). Replaces SQS in-process. `enqueue()` calls `put()` which **blocks** if the queue is full — natural backpressure, the caller slows down.

#### `QueueWorker.java`

Spins up N daemon threads (`@PostConstruct`) that each loop on `queue.poll(1 second)`. Each item goes to `orchestrator.process()`. On exception: mark FAILED, write to `dead_letter_messages`. Graceful shutdown in `@PreDestroy` gives workers 5 seconds to finish in-flight items.

> *"This is the SQS listener replacement. In production swap `QueueWorker` for `@SqsListener`. The orchestrator doesn't know or care what calls it."*

#### `InProcessEventBus.java`

In-memory topic map standing in for Kafka. `OutboxPoller` publishes to it after committing outbox rows.

---

### Layer 4 — Background Processes

#### `NotificationScheduler.java` (`scheduler/`)

Runs every 60 seconds. Calls `notificationRepository.claimHeld(now, 500)` which executes:

```sql
SELECT ... FROM notifications
WHERE status = 'HELD' AND scheduled_at <= :now
LIMIT 500
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE SKIP LOCKED` — multiple JVM instances can run this simultaneously. Each instance only sees rows not locked by another. Zero coordination overhead, no duplicate releases.

> *"This is the detail Postgres-experienced CTOs notice. `SKIP LOCKED` is the production pattern for concurrent queue-like table processing."*

#### `OutboxPoller.java` (`outbox/`)

Runs every 5 seconds. Reads up to 100 pending outbox rows, publishes each to the event bus, marks it published. Publish-then-mark ordering is deliberate: a crash after publish re-publishes (consumers must be idempotent); mark-before-publish would lose events permanently.

---

### Layer 5 — Repositories (`infrastructure/repo/`)

| Repository | Owns |
|---|---|
| `NotificationRepository` | CRUD on `notifications`. Houses `wasContactedSince()` with Micrometer timer wrapping the raw JDBC call. |
| `ConsumerPreferenceRepository` | Read/write `consumer_preferences`. Also owns `findEligibleConsumerIds()` — the smart-load pre-filter. |
| `TemplateRepository` | Read templates, idempotent insert. |
| `DeliveryLogRepository` | Append-only delivery log. `insertIfAbsent` on `(notification_id, attempt_number)`. |
| `OutboxRepository` | Find pending / mark published. |
| `SeedRepository` | `generate_series` bulk inserts. Not used in production paths. |

**`wasContactedSince()`** — the 0.10ms query:
```sql
SELECT EXISTS (
    SELECT 1 FROM notifications
    WHERE tenant_id = :tenantId
      AND consumer_id = :consumerId
      AND status IN ('SENT', 'DELIVERED')
      AND sent_at > :since
)
```

**`findEligibleConsumerIds()`** — the smart-load pre-filter combining all three eligibility rules in one query:
```sql
SELECT cp.consumer_id
FROM consumer_preferences cp
WHERE cp.tenant_id = :tenantId
  AND cp.opted_out = false
  AND cp.timezone IN (:openTimezones)
  AND NOT EXISTS (
      SELECT 1 FROM notifications n
      WHERE n.tenant_id = cp.tenant_id
        AND n.consumer_id = cp.consumer_id
        AND n.status IN ('SENT', 'DELIVERED')
        AND n.sent_at > :since
  )
LIMIT :limit
```

---

### Layer 6 — Senders (`infrastructure/sender/`)

#### `MessageSender` (interface)

One method: `send(consumerId, body)` returns `SendResult`. Two implementations:
- `SimulatedTwilioSender` — SMS channel
- `SimulatedSendGridSender` — Email channel

Both generate a fake `providerMessageId` and return accepted with 90% probability (configurable).

#### `SenderRegistry`

Takes the list of `MessageSender` beans injected by Spring, builds an `EnumMap<Channel, MessageSender>`. `forChannel(SMS)` → Twilio, `forChannel(EMAIL)` → SendGrid.

> *"In production: replace `SimulatedTwilioSender` with a real Twilio client. The orchestrator, registry, and interface don't change."*

---

### Full request flow — end to end

```
POST /v1/notifications
  → TenantContextFilter       JWT → tenantId in ThreadLocal
  → NotificationController
  → NotificationSubmissionService
      → NotificationRepository.insertIfAbsent()    ON CONFLICT DO NOTHING
      → NotificationQueue.enqueue()
  → 202 Accepted

[async — QueueWorker thread]
  → NotificationOrchestrator.process()   @Transactional
      → ConsumerPreferenceRepository.find()              opted_out + timezone
      → SuppressionService.isSuppressed()
          → NotificationRepository.wasContactedSince()   0.10ms EXISTS
      → timezone window check                            in-memory
      → TemplateRepository.find()
      → TemplateEngine.render()
      → MiniMirandaValidator.isValid()
      → SenderRegistry → SimulatedTwilioSender.send()
      → NotificationRepository.markSent()           ┐
      → DeliveryLogRepository.insertIfAbsent()      ├── one transaction
      → OutboxRepository.saveIfAbsent()             ┘

[every 5s]
  → OutboxPoller → InProcessEventBus.publish()

[every 60s]
  → NotificationScheduler.claimHeld()   FOR UPDATE SKIP LOCKED
  → NotificationQueue.enqueue()         re-enters async flow above
```

---

## CTO Questions — Dashboard

**Q: How does the app know the consumer's timezone without a separate DB call?**
> "It does come from the DB — but not a separate query. Step 1 reads the entire `consumer_preferences` row: opted-out status and timezone together in one SELECT. By the time we reach the FDCPA window check, the timezone is already in memory. Two DB reads total per notification: consumer preferences and the suppression EXISTS."

**Q: Suppression averages 0.10ms but the max is 70ms. What causes that spike?**
> "The high max is the first query on a cold OS page cache — Postgres has to read index pages from disk. Once warm, all subsequent queries hit the buffer cache and stay sub-millisecond. In production with dedicated hardware and connection pool warming on startup, the cold spike is much lower."

**Q: Recent sends took 170ms for 50 rows. Is there an index problem?**
> "Yes — wrong index shape, not missing index. The suppression index is `(tenant_id, consumer_id, sent_at DESC)`. The recent sends query orders by sent_at across all consumers for a tenant, but consumer_id sits between tenant and sent_at in the index. Postgres can't walk it in sent_at order globally. You'd fix this with a separate `(tenant_id, sent_at DESC)` index, but this is a low-frequency monitoring endpoint — adding that write overhead to every INSERT isn't worth it."

**Q: Smart load returned 100% SENT. Is that realistic?**
> "For a fresh data set yes, because the pre-filter already excluded suppressed and opted-out consumers. Run it twice in a row and the second run finds far fewer eligible consumers — the first run just suppressed them all. That's the 3-day rule working as designed."

**Q: What happens to HELD notifications if the service restarts?**
> "They're safe. HELD notifications are persisted in Postgres with `status=HELD` and a `scheduled_at` timestamp. The scheduler picks them up every 60 seconds using `FOR UPDATE SKIP LOCKED`. Restarts just pause the 60-second window."

---

## CTO Questions — Code

**Q: Why is `@Transactional` on `process()` but not on submission?**
> "Submission inserts one row and enqueues — if enqueue fails, the row stays in PENDING and gets picked up on retry. But `process()` must commit the status update, delivery log, and outbox event atomically. Mark SENT but fail to write the outbox row and downstream consumers never know the send happened."

**Q: Why is `SuppressionService` a separate class from `NotificationOrchestrator`?**
> "Single responsibility. The orchestrator owns the gate sequence. `SuppressionService` owns what 'suppressed' means — the window calculation and DB delegation. Change the suppression rule from 3 days to 7 or add channel-specific overrides: you touch one class, not the orchestrator. Also independently unit-testable with a mock repository."

**Q: Why `ThreadLocal` for tenant context?**
> "Tenant ID is a cross-cutting concern resolved at the HTTP boundary. Passing it through every method signature would pollute every service and repository. The filter is the right place — that's where the JWT lives. The `finally` clear guarantees no leak across requests on the same thread pool thread."

**Q: What if the queue fills up?**
> "`queue.put()` blocks the calling HTTP thread. The request hangs until space frees, then returns 202. That's intentional backpressure — the caller slows down rather than the service accepting more than it can process. In production SQS handles this natively."

**Q: Why `FOR UPDATE SKIP LOCKED` in the scheduler?**
> "A plain `UPDATE WHERE status = 'HELD'` with multiple instances would race — two instances could claim the same row and send the notification twice. `SKIP LOCKED` means each row is only visible to one SELECT at a time. No coordination overhead, no duplicate sends."

**Q: What's the dead letter table for?**
> "Any unhandled exception in `QueueWorker` writes the full payload and error to `dead_letter_messages`. Audit trail for failures — inspect, fix, replay. In production this feeds an alert to on-call."

**Q: How would you take this to production?**
> "Three swaps behind existing interfaces: `EmbeddedPostgresConfig` → managed Postgres URL, `InProcessEventBus` → Kafka/SQS client, `SimulatedTwilioSender` → real Twilio SDK. Schema, indexes, FDCPA logic, outbox pattern, `FOR UPDATE SKIP LOCKED` — all production-ready as-is."
