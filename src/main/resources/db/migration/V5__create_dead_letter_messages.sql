CREATE TABLE dead_letter_messages (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  queue_name       VARCHAR(255) NOT NULL,
  message_id       VARCHAR(255) NOT NULL,   -- queue envelope ID (not notification_id)
  notification_id  UUID,                    -- nullable: business ID parsed from payload
  payload          JSONB NOT NULL,
  error_message    TEXT,
  retry_count      INT NOT NULL DEFAULT 0,
  first_failed_at  TIMESTAMPTZ NOT NULL,
  last_failed_at   TIMESTAMPTZ NOT NULL,
  status           VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  resolved_at      TIMESTAMPTZ
);

CREATE INDEX idx_dlq_tenant_status ON dead_letter_messages (tenant_id, status);
CREATE INDEX idx_dlq_status_date ON dead_letter_messages (status, last_failed_at);

-- status values: PENDING_REVIEW | REPROCESSED | DISCARDED
