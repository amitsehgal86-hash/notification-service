-- Supports the 3-day contact-suppression rule: for a (tenant, consumer), was there a
-- SENT/DELIVERED notification within the last N days? Partial + composite so the check
-- stays O(log n) even at 1M+ consumer rows and tens of thousands of sends per day.
CREATE INDEX idx_notifications_recent_contact
  ON notifications (tenant_id, consumer_id, sent_at DESC)
  WHERE status IN ('SENT', 'DELIVERED');
