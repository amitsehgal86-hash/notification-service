-- Transactional outbox for reliable event publishing.
-- Written in same transaction as business state change.
-- OutboxPoller reads pending rows and publishes to the in-process event bus.
CREATE TABLE outbox_events (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_id   UUID NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  event_type     VARCHAR(64) NOT NULL,
  payload        JSONB NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'pending',   -- pending | published
  published_at   TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_outbox_aggregate_event UNIQUE (aggregate_id, event_type)
);

-- Partial index - Poller only queries pending rows
CREATE INDEX idx_outbox_pending ON outbox_events (created_at ASC)
  WHERE status = 'pending';
