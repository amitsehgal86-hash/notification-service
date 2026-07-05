CREATE TABLE notifications (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  consumer_id      UUID NOT NULL,
  template_id      UUID NOT NULL,
  channel          VARCHAR(16) NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
  status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  idempotency_key  VARCHAR(255) NOT NULL,
  -- For agency portal requests: client-generated UUID header
  -- For Kafka-triggered: derived as aggregate_id + ":" + event_type + ":" + channel
  scheduled_at     TIMESTAMPTZ,           -- null = send now; set when status = HELD
  sent_at          TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_notifications_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_notifications_tenant_consumer ON notifications (tenant_id, consumer_id, created_at DESC);
CREATE INDEX idx_notifications_held ON notifications (scheduled_at ASC)
  WHERE status = 'HELD';   -- Scheduler query

-- status values: PENDING | SENT | DELIVERED | FAILED | HELD | SUPPRESSED
-- SUPPRESSED = dropped by the 3-day contact-suppression rule (this build's core rule).
