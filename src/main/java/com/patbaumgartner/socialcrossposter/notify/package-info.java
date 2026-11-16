/**
 * Durable, deduplicated, redacted notification events and external delivery (UC-12,
 * ADR-004, ADR-007, ADR-013, ADR-015, ADR-016). The first slice persists workspace-scoped
 * events (Flyway V9 {@code notification_event}) emitted on failed/UNKNOWN/needs-attention
 * deliveries, exhausted retries, expired and needs-review schedules, and shows them on
 * the workspace dashboard. The second slice adds per-workspace webhook channels (Flyway
 * V12 {@code notification_channel} + {@code notification_delivery} outbox) with an SSRF
 * guard (arc42 section 8), sealed HMAC signing secrets (ADR-007), signed payloads, and
 * bounded retries. The third slice (Flyway V13) adds SMTP channels on the same outbox:
 * the same SSRF host rules, canonical port/TLS mapping, a hand-rolled minimal SMTP client
 * (no mail dependency), and the sealed SMTP password bound to its own AAD purpose.
 */
package com.patbaumgartner.socialcrossposter.notify;
