/**
 * Durable, deduplicated, redacted notification events and external webhook delivery
 * (UC-12, ADR-004, ADR-007, ADR-013, ADR-015, ADR-016). The first slice persists
 * workspace-scoped events (Flyway V9 {@code notification_event}) emitted on
 * failed/UNKNOWN/needs-attention deliveries, exhausted retries, expired and needs-review
 * schedules, and shows them on the workspace dashboard. The second slice adds
 * per-workspace webhook channels (Flyway V12 {@code notification_channel} +
 * {@code notification_delivery} outbox) with an SSRF guard (arc42 section 8), sealed HMAC
 * signing secrets (ADR-007), signed payloads, and bounded retries; SMTP channels are a
 * later slice.
 */
package com.patbaumgartner.socialcrossposter.notify;
