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
