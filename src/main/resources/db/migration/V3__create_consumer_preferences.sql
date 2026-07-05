CREATE TABLE consumer_preferences (
  consumer_id   UUID NOT NULL,
  tenant_id     UUID NOT NULL,
  opted_out     BOOLEAN NOT NULL DEFAULT false,
  opted_out_at  TIMESTAMPTZ,
  opted_out_via VARCHAR(32),     -- SMS_STOP | PORTAL | AGENT
  timezone      VARCHAR(64) NOT NULL,   -- e.g. America/Chicago. NOT NULL - required for FDCPA window check.
  sms_enabled   BOOLEAN NOT NULL DEFAULT true,
  email_enabled BOOLEAN NOT NULL DEFAULT true,

  PRIMARY KEY (consumer_id, tenant_id)
);

COMMENT ON COLUMN consumer_preferences.opted_out IS
  'FDCPA-critical. Always read from primary DB - never from cache or replica.';
COMMENT ON COLUMN consumer_preferences.timezone IS
  'Required for FDCPA 8am-9pm contact window check. Import pipeline rejects records without it.';
