# UC-07 — Reconcile or retry an individual delivery

- **Actor:** Owner or durable retry worker
- **Trigger:** Safe retry is due, or owner opens a failed/UNKNOWN delivery
- **Preconditions:** Original workspace, approved payload, and destination remain valid
- **Related decisions:** ADR-001, ADR-009, ADR-015

## Main flow

1. Inspect the previous attempt/stage and classify retry safety. Never re-run successful destinations.
2. For UNKNOWN, query a verified provider reconciliation mechanism using persisted idempotency keys, remote upload/container IDs, or external references.
3. If publication is confirmed, record SUCCESS. If no write is confirmed and repeat is safe, queue a new attempt of the exact frozen payload.
4. For a safe transient rejection, persist RETRY_WAIT using bounded backoff/jitter and provider Retry-After. Default: at most three automatic retries after the initial attempt.
5. Record append-only attempts, update aggregate state, and notify on unresolved actionable outcomes.

## Alternative flows

- Inconclusive reconciliation stays UNKNOWN. Without a provider mechanism, owner can attach a verified found remote reference or explicitly acknowledge duplicate risk before requeueing the same payload; audit that choice.
- Auth/permission failures require repair; reconnect must preserve the destination's verified identity.
- New content is a new publication requiring approval. Retry never uses the current mutable draft.
- Temporary provider-import URLs may be refreshed for the same approved media; a changed image/text/identity requires review.
- Disabled/archived workspace or destination prevents dispatch; cancellation does not delete confirmed remote posts.
- Rate limits may be shared by a connection, provider app, or instance; honor the actual scope.
- For scheduled deliveries, recheck the scheduled instant plus 15 minutes before every remote post-create attempt. An already queued retry, reconnect or resumed worker cannot extend it; require renewed time approval after expiry. Read-only reconciliation remains available during dispatch pause and after the deadline; inconclusive earlier writes remain UNKNOWN ([ADR-016](../adr/ADR-016-operational-and-editorial-policies.md)).

## Postconditions and UI

No remote exactly-once guarantee is claimed. Distinguish “Retry safely”, “Reconcile”, and “Retry with duplicate risk” instead of one universal button. Show the payload and destination that will be reused.

## Review log

- 2026-11-13 — Reviewed against ADR-001, ADR-009, ADR-015 and ADR-016. UNKNOWN-before-retry, bounded retries (three automatic after the initial attempt), frozen payload reuse and the scheduled-instant-plus-15-minutes cutoff match ADR-015/ADR-016; durable claims, leases and fencing are owned by ADR-009 and were added to the related decisions. No behavior change.
