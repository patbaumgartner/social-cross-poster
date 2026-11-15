# UC-12 — Deliver workspace failure and attention notifications

- **Actor:** System; owner configures recipients
- **Trigger:** Failed/UNKNOWN delivery, overdue schedule, capability review, connection/bridge failure, or exhausted retries
- **Preconditions:** Workspace exists; external channels optional
- **Related decisions:** ADR-004, ADR-007, ADR-013, ADR-015

## Main flow

1. Configure webhook and/or SMTP recipients per workspace, with encrypted credentials and event selection.
2. Persist a redacted event atomically with the relevant result; enqueue deduplicated notification deliveries.
3. Include workspace label/ID, publication/destination identity, safe error category, event ID, and authenticated detail link.
4. Deliver asynchronously with bounded backoff: at most three total delivery attempts. Webhooks carry a signature, timestamp, and stable event ID for receiver deduplication.
5. Record delivery success/failure and show the event in the workspace dashboard regardless of external delivery.

## Alternative flows

- No external channel: keep the in-app event; do not silently use another workspace's recipient.
- Endpoint failure: preserve delivery status and retry without blocking publishing or generating recursive notification storms.
- Default payload excludes draft text, images, prompts, tokens, raw provider bodies, and signed URLs. Text snippets require explicit workspace opt-in.
- Outbound webhook URLs reject private/link-local/metadata destinations and unsafe redirects by default. Intentional internal endpoints require a deployment-level allow-list; SMTP hosts are explicitly configured.
- A 401/403 credential problem is actionable and not automatically a retryable publishing error.
- Shared provider outages produce deduplicated/rate-limited summaries while retaining per-workspace visibility.

## Postconditions and UI

External delivery is best effort; persisted in-app events remain authoritative. A test notification names the workspace and exact recipient before sending.

## Review log

- 2026-11-13 — Reviewed against ADR-004, ADR-007, ADR-013 and ADR-015. Encrypted recipient credentials, redacted atomic events, deduplicated bounded delivery and authoritative in-app events match ADR-007/ADR-015; workspace scoping matches ADR-013. The SSRF/link-local/metadata rejection rules for outbound notification destinations are specified in [arc42 §8 — Cross-cutting concepts, Security](../arc42/08-cross-cutting-concepts.md#security). No behavior change.
- 2026-11-15 — First slice implemented (in-app events only — steps 2, 3 and 5): Flyway V9 `notification_event` persists one durable, deduplicated (`UNIQUE(workspace_id, event_key)`), redacted event per workspace with a category and optional publication/delivery/schedule links (step 2). `DeliveryJobService.settle` emits `DELIVERY_FAILED` (failure with retry budget left), `RETRIES_EXHAUSTED` (budget spent), `DELIVERY_NEEDS_ATTENTION` (NEEDS_ATTENTION/NEEDS_REVIEW outcomes) and `DELIVERY_UNKNOWN` (still UNKNOWN after reconciliation); `ScheduleService.processDue` emits `SCHEDULE_EXPIRED` (beyond the scheduled-instant + 15 min cutoff, ADR-016) and `SCHEDULE_NEEDS_REVIEW` (changed payload hash). The workspace dashboard lists the newest 50 events with category badge, redacted summary, timestamp and an authenticated link to the publication detail (steps 3 and 5). Summaries never contain draft text, images, tokens, raw provider bodies or signed URLs (alternative flow 4), and events are strictly workspace-scoped (ADR-013 — alternative flow 1 holds: no cross-workspace recipients). 199/199 tests green. Deviations closed by later slices: no webhook/SMTP recipient configuration or encrypted recipient credentials (step 1), no asynchronous external delivery with bounded backoff, signature, timestamp and stable event ID (step 4), no SSRF allow-list rules for outbound URLs (arc42 §8), no external delivery success/failure records, no rate-limited shared-outage summaries (alternative flow 6), no test-notification (postconditions), no connection/bridge-failure events (trigger list).
