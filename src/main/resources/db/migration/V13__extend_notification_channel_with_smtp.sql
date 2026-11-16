-- V13: SMTP notification channels (UC-12 third slice, main flow step 1 "webhook
-- and/or SMTP"; ADR-004, ADR-007, ADR-013, ADR-015; SSRF rules per arc42 section 8
-- apply to SMTP hosts exactly as to webhook URLs — see OutboundSmtpGuard).
-- notification_channel gains channel_type WEBHOOK|SMTP; for SMTP the url column
-- stays NULL and the smtp_* columns carry the target. The secret_* columns are
-- reused: for SMTP they hold the sealed SMTP password bound to AAD
-- "<slug>:notify-smtp:<label>" (ADR-007) — never stored or shown in plaintext.
-- Port/TLS mapping is enforced by OutboundSmtpGuard: 25=NONE (allow-listed hosts
-- only), 465=IMPLICIT_TLS, 587=STARTTLS; any other port requires an encrypted
-- mode unless the host is on the operator allow-list. DNS is re-resolved at send
-- time (rebinding protection), and localhost / cloud metadata endpoints are
-- hard-denied even when allow-listed.
-- Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE notification_channel ADD COLUMN channel_type VARCHAR(16) NOT NULL DEFAULT 'WEBHOOK';
ALTER TABLE notification_channel ALTER COLUMN url DROP NOT NULL;
ALTER TABLE notification_channel ADD COLUMN smtp_host VARCHAR(255);
ALTER TABLE notification_channel ADD COLUMN smtp_port INTEGER;
ALTER TABLE notification_channel ADD COLUMN smtp_tls_mode VARCHAR(16);
ALTER TABLE notification_channel ADD COLUMN smtp_from VARCHAR(320);
ALTER TABLE notification_channel ADD COLUMN smtp_to VARCHAR(1024);
ALTER TABLE notification_channel ADD COLUMN smtp_username VARCHAR(255);
ALTER TABLE notification_channel ADD CONSTRAINT notification_channel_type_check CHECK (channel_type IN ('WEBHOOK', 'SMTP'));
