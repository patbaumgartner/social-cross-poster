-- V18: per-workspace notification payload default (UC-15 fifth slice, main flow step 2
-- "notification channels" remainder; UC-12 alternative flow 4: text snippets require an
-- explicit workspace opt-in). notify_snippets BOOLEAN NOT NULL DEFAULT FALSE means
-- external notification payloads (webhook JSON, SMTP body) stay fully redacted — no
-- draft/post text. When the owner opts in, NotificationDeliveryService appends a bounded
-- text snippet (max 200 chars, whitespace collapsed) of the linked publication's
-- approved revision. In-app events are never enriched — they stay redacted either way.
-- Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE workspace ADD COLUMN notify_snippets BOOLEAN NOT NULL DEFAULT FALSE;
