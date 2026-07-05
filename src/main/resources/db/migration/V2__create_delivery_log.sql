-- Append-only per-attempt delivery record.
CREATE TABLE delivery_log (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id     UUID NOT NULL REFERENCES notifications(id),
  attempt_number      INT NOT NULL,
  status              VARCHAR(32) NOT NULL,   -- SENT | DELIVERED | FAILED | BOUNCED
  provider_message_id VARCHAR(255),           -- Twilio SID or SendGrid message ID
  error_message       TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_delivery_log_provider UNIQUE (provider_message_id)
);

CREATE INDEX idx_delivery_log_notification ON delivery_log (notification_id, created_at DESC);

COMMENT ON CONSTRAINT uq_delivery_log_provider ON delivery_log IS
  'provider_message_id is the idempotency key for delivery receipt webhooks.
   Twilio/SendGrid can retry delivery receipts - this prevents duplicate processing.';
