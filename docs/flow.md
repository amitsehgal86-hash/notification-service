# Notification Service — Flow

Message lifecycle, compliance gates, and background processes. (Render with any Mermaid viewer.)

```mermaid
flowchart TD
  %% ---------- Ingress (synchronous) ----------
  C["Client<br/>POST /v1/notifications<br/>Authorization: Bearer JWT"]
  F["TenantContextFilter<br/>tenant_id from JWT (401 if missing)"]
  CT["NotificationController"]
  SUB["NotificationSubmissionService<br/>INSERT notifications ON CONFLICT DO NOTHING"]
  Q(["NotificationQueue<br/>in-JVM, replaces SQS"])
  C --> F --> CT --> SUB -->|"returns 202 Accepted"| Q

  %% ---------- Async processing ----------
  Q --> W["QueueWorker x8"]
  W --> O{{"NotificationOrchestrator.process()  @Transactional"}}

  %% ---------- Compliance gate ladder ----------
  O --> G1{"opted_out?"}
  G1 -->|yes| FAIL1[["FAILED (drop)"]]
  G1 -->|no| G2{"contacted in last 3 days?<br/>SuppressionService"}
  G2 -->|yes| SUP[["SUPPRESSED"]]
  G2 -->|no| G3{"inside 08:00-18:00<br/>consumer-local time?"}
  G3 -->|no| HELD[["HELD<br/>scheduled_at = next 08:00"]]
  G3 -->|yes| G4{"render + mini-Miranda present?"}
  G4 -->|no| FAIL2[["FAILED -> dead_letter_messages"]]
  G4 -->|yes| SEND["SenderRegistry -> Simulated Twilio (SMS) / SendGrid (Email)"]

  %% ---------- Commit + receipt ----------
  SEND --> PERSIST["Same TX: status=SENT + sent_at<br/>delivery_log(SENT) + outbox_events(NOTIFICATION_SENT)"]
  PERSIST --> RCPT["Simulated delivery receipt -> status DELIVERED"]

  %% ---------- Background processes ----------
  HELD --> SCHED["NotificationScheduler @Scheduled 60s<br/>claimHeld FOR UPDATE SKIP LOCKED"]
  SCHED -->|re-enqueue| Q
  PERSIST --> POLL["OutboxPoller @Scheduled 5s<br/>publish pending outbox_events"]
  POLL --> BUS(["InProcessEventBus (Kafka stand-in)"])
  WH["POST /internal/webhooks/twilio|sendgrid"] --> WHC["WebhookController<br/>dedup by provider_message_id"]
  WHC --> RCPT

  %% ---------- Persistence ----------
  PERSIST -.-> DB[("Embedded PostgreSQL 16 (Zonky)<br/>notifications · consumer_preferences · templates<br/>delivery_log · outbox_events · dead_letter_messages")]
```

Entry points that drive the orchestrator: the REST API (JWT), the bundled TUI (`java -jar … tui`),
and the demo/admin endpoints (`/internal/seed`, `/internal/demo/load`).
