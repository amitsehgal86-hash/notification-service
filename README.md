# Notification Service

An FDCPA-compliant SMS/email notification service that is **self-contained and plug-and-play**:
one executable JAR, a **real embedded PostgreSQL** (no install, no cloud), and all external
providers (Twilio/SendGrid/SQS/Kafka) **simulated in-process**. Built to demo at **1M consumers**
and **50k+ notifications/day**.

## The core rule

> **Do not send a notification to any consumer we already contacted in the last 3 days**
> (per consumer, across all channels).

Implemented in `SuppressionService` + `NotificationOrchestrator`, backed by a partial composite
index (`idx_notifications_recent_contact`) so the check stays fast at 1M+ rows. The window is
configurable via `notification.suppression.window-days`.

## Why embedded PostgreSQL (not H2)

We use [Zonky `embedded-postgres`](https://github.com/zonkyio/embedded-postgres): a **real**
PostgreSQL 16 binary is shipped inside the JAR, extracted on first run, and run against a local
data directory (`./data/pg`). This keeps native Postgres SQL (JSONB, partial indexes,
`FOR UPDATE SKIP LOCKED`, `ON CONFLICT`, `gen_random_uuid()`) and scales to millions of rows —
where H2 would not. The data directory **persists across restarts and travels with the app folder**.

## Requirements

- **Build:** JDK 17+ and Maven (only needed to produce the JAR).
- **Run:** JRE 17+ only. No database, no Docker, no cloud accounts.

## Build

Dependencies are all public (Maven Central). A project-local `build-settings.xml` mirrors all
repositories straight to Maven Central so the build works anywhere and does not depend on a private
mirror:

```bash
mvn -s build-settings.xml clean package
```

Produces `target/notification-service.jar`.

## Run

```bash
./run.sh
# or:
java -jar target/notification-service.jar
```

- App:            http://localhost:8080
- Embedded PG:    localhost:54329 (db `postgres`, user `postgres`)
- Health:         http://localhost:8080/actuator/health

## Terminal UI (TUI)

A full-screen dashboard is bundled in the **same JAR** — no web server, no extra install. It drives
the embedded database and services directly and is the easiest way to demo the 3-day rule.

```bash
./tui.sh
# or:
java -jar target/notification-service.jar tui
```

Keys: **[s]** seed consumers · **[l]** run a load · **[b]** browse recent notifications ·
**[o]** opt-out a consumer · **[r]** refresh · **[q]** quit. The dashboard shows live consumer/
notification counts and a bar breakdown of the last load run (SENT / SUPPRESSED(3d) / HELD / OPTED_OUT).
Run a load twice to watch `SENT` collapse to 0 as the 3-day rule kicks in. (Logs go to `tui.log` so
they don't disturb the screen.)

```
┌─ Notification Service — 3-day contact suppression demo ─┐
│ Tenant: 00000000-0000-0000-0000-000000000001           │
│ Consumers: 1,000,000     Notifications: 399,493        │
│ Last load run:                                          │
│   SENT            ████████░░░░░░░░░░░░░░░░  13,397       │
│   SUPPRESSED(3d)  █████████░░░░░░░░░░░░░░░  14,712       │
│   HELD(window)    ████████████████████████  20,915      │
│   OPTED_OUT       ░░░░░░░░░░░░░░░░░░░░░░░░      976      │
│ [s]eed  [l]oad  [b]rowse  [o]pt-out  [r]efresh  [q]uit  │
└─────────────────────────────────────────────────────────┘
```

## Demo walkthrough (REST / curl)

```bash
# 1) Seed 1,000,000 consumers + a default (mini-Miranda) template + recent history (~30% of
#    consumers get a "sent 1 day ago" record, so they'll be suppressed). Server-side generate_series.
curl -sX POST "http://localhost:8080/internal/seed?consumers=1000000&historyFraction=0.3" | jq

# 2) Confirm scale
curl -s "http://localhost:8080/internal/stats" | jq

# 3) Push 50,000 notifications through the pipeline and see the outcome split:
#    sent vs suppressed_3day vs held_outside_window vs opted_out vs failed
curl -sX POST "http://localhost:8080/internal/demo/load?count=50000" | jq
```

Example load result:

```json
{
  "requested": 50000,
  "processed": 50000,
  "sent": 33210,
  "suppressed_3day": 14880,
  "held_outside_window": 990,
  "opted_out": 920,
  "failed": 0,
  "elapsedMs": 8123,
  "throughputPerSec": 6155
}
```

### Exercising the API directly (tenant from JWT)

```bash
# Mint a dev tenant token (tenant_id lives in the token, never the request body)
TENANT=00000000-0000-0000-0000-000000000001
TOKEN=$(curl -s "http://localhost:8080/dev/token?tenantId=$TENANT" | jq -r .token)

# Pick a seeded consumer id from stats/queries, then submit a notification:
curl -sX POST http://localhost:8080/v1/notifications \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"consumerId":"<CONSUMER_UUID>","templateId":"<TEMPLATE_UUID>","channel":"SMS","variables":{"amount":"$100.00"}}' | jq

curl -s http://localhost:8080/v1/notifications/<ID> -H "Authorization: Bearer $TOKEN" | jq
```

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/notifications` | `Idempotency-Key` header; tenant from JWT; returns 202 |
| GET  | `/v1/notifications/{id}` | includes `delivery_log` |
| GET  | `/v1/notifications?consumer_id=&status=&limit=` | filtered list |
| POST | `/v1/consumers/{id}/opt-out` | synchronous strong write |
| GET  | `/v1/consumers/{id}/preferences` | |
| POST | `/internal/webhooks/twilio` \| `/sendgrid` | delivery receipts (idempotent) |
| GET  | `/dev/token?tenantId=` | mint a tenant JWT (dev only) |
| POST | `/internal/seed` | bulk seed consumers/template/history |
| POST | `/internal/demo/load` | throughput demo with outcome summary |
| GET  | `/internal/stats` | row counts |

## Compliance / correctness features (from the spec, adapted)

- **3-day contact suppression** (the added core rule) — first gate after opt-out.
- **Opt-out** checked first, strong read from the primary DB.
- **FDCPA 8am–9pm window** in the consumer's local timezone → otherwise **HELD** and released by the
  scheduler at the next local 8am.
- **Mini-Miranda** validated post-render (`MiniMirandaValidator`); non-compliant messages are never sent.
- **Idempotency** everywhere via `ON CONFLICT` (`notifications`, `templates`, `delivery_log`,
  `outbox_events`, `consumer_preferences`).
- **Transactional outbox** — every send writes an `outbox_events` row in the same transaction;
  `OutboxPoller` publishes to the (in-process) event bus.
- **HELD release** uses `FOR UPDATE SKIP LOCKED` so multiple instances never double-release.

## Tests

```bash
mvn -s build-settings.xml test
```

Integration tests (`*IT`) boot the embedded Postgres on a separate port under `target/test-pg`:
mini-Miranda validation, the 3-day rule (inside/outside window), orchestrator gates (opted-out,
suppressed, HELD, missing-Miranda, happy path), idempotent insert, and a `claimHeld` concurrency test.

## Configuration (`application.yml`, prefix `notification.*`)

| Key | Default | Meaning |
|---|---|---|
| `db.data-dir` | `./data/pg` | embedded Postgres data dir (persistent) |
| `db.port` | `54329` | embedded Postgres port |
| `suppression.window-days` | `3` | the contact-suppression window |
| `fdcpa.window-start` / `window-end` | `08:00` / `21:00` | contact window |
| `sender.delivered-probability` | `0.9` | simulated delivery success rate |
| `queue.workers` | `8` | in-JVM async processor threads |
| `jwt.secret` | dev key | HS256 signing key (override in prod) |

## Going to production

Swap the simulated pieces for real ones — they sit behind interfaces:
`MessageSender` (Twilio/SendGrid), `NotificationQueue` (SQS), `InProcessEventBus` (Kafka), and point
the `DataSource` at a managed PostgreSQL instead of the embedded one.
