CREATE TABLE templates (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  name             VARCHAR(255) NOT NULL,
  channel          VARCHAR(16) NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
  body_template    TEXT NOT NULL,
  requires_miranda BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_templates_tenant_name UNIQUE (tenant_id, name)
);

COMMENT ON COLUMN templates.body_template IS
  'Handlebars/Mustache-style template. Mini-Miranda must be hardcoded in body - never dynamic.
   Post-render validation checks for required phrases before any message is sent.';
