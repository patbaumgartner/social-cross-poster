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
- 2026-11-15 — **Implemented (first slice: durable jobs, worker, owner retry/reconcile).** Flyway V6 adds `delivery_job` (UNIQUE per delivery; state PENDING/RUNNING/SUCCEEDED/EXHAUSTED/CANCELED, `next_run_at`, lease `lease_owner`/`lease_until`). The UC-05 synchronous initial attempt stays in the publish request via `DeliveryJobService.executeInitialAndSettle`, which then settles the durable outcome: a confirmed FAILED becomes RETRY_WAIT with a PENDING job on a bounded exponential backoff (`base*2^(n-1)` with 0.8–1.2 jitter, capped at the configured cap, floored at 1 s; default budget 4 attempts = initial + 3 automatic retries, matching step 4), UNKNOWN is reconciled first through the platform reconciler and never blindly retried (`TelegramReconciler` is always INCONCLUSIVE — Bot API `sendMessage` has no read-back — so it lands NEEDS_ATTENTION), SUCCESS/SKIPPED close the job SUCCEEDED, NEEDS_ATTENTION/NEEDS_REVIEW cancel it for manual review; the ADR-015 aggregate is re-derived after every settle (UNKNOWN/NEEDS_ATTENTION → NEEDS_ATTENTION, RETRY_WAIT → IN_PROGRESS). `DeliveryWorker` (scheduled on `app.worker.*`/`APP_WORKER_*`, disableable) claims due jobs exactly once via the conditional lease `claim()` — a crashed worker's RUNNING jobs are reclaimed once the lease expires — executes them and releases failures with the base backoff. Owner actions `POST /w/{slug}/history/{pub}/deliveries/{id}/retry` (guards: non-retryable state, exhausted attempt budget, live lease) and `.../reconcile` are workspace-scoped and fail closed on foreign ids (ADR-013); the history detail shows each delivery's job state/next run plus Retry now/Reconcile buttons. Deviations, closed by later slices: no provider `Retry-After` parsing yet (backoff is purely exponential), no explicit duplicate-risk retry acknowledgement (alternative flow 1), notifications on unresolved outcomes (UC-12), the scheduled-instant-plus-15-minutes recheck (UC-09/UC-10), remote edit/delete (UC-08). Verified by `DeliveryJobServiceTest` (18 tests) + `RetryFlowTest` (5 tests) + the adapted `PublishFlowTest`, 135/135 green.
