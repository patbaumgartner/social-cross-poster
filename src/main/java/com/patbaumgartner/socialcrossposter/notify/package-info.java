/**
 * Durable, deduplicated, redacted in-app notification events (UC-12, ADR-013, ADR-015,
 * ADR-016). The first slice persists workspace-scoped events (Flyway V9
 * {@code notification_event}) emitted on failed/UNKNOWN/needs-attention deliveries,
 * exhausted retries, expired and needs-review schedules, and shows them on the workspace
 * dashboard; external webhook/SMTP delivery with signatures, bounded retries and
 * SSRF-protected targets is a later slice.
 */
package com.patbaumgartner.socialcrossposter.notify;
