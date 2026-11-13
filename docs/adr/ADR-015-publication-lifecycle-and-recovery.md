# ADR-015 — Immutable approval, durable delivery, and uncertain outcomes

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

A provider may publish successfully before a response is lost. A browser retry, worker restart, or changed draft must not create a different message or an unacknowledged duplicate.

## Decision

### Content and approval

A draft has versioned immutable revisions. Approve a revision and exact destination payloads/identities/media transformations before creating a publication or schedule. Changes invalidate approval. A workspace-scoped submission key returns the existing publication for repeated submissions of the same approved action; changed parameters under the same key are rejected. A content hash can warn about similar posts, but cannot prove remote idempotency or prohibit intentional repeated announcements.

### Delivery states

| State | Meaning / next action |
|---|---|
| PENDING | Durable delivery exists but has not been claimed |
| RUNNING | Worker owns a time-bounded lease and may contact the provider |
| RETRY_WAIT | Confirmed safe-to-repeat failure; scheduled backoff, honoring provider limits |
| SUCCESS | Provider confirms the requested publication; store required external references |
| FAILED | Confirmed failure; retain error classification and repair/retry options |
| SKIPPED | Explicitly omitted before a remote write, with a reason |
| CANCELED | Canceled before a remote write |
| UNKNOWN | A write may have occurred; reconcile before repeating |
| NEEDS_REVIEW | Approved content/identity/capabilities are no longer valid |

Append attempts/stages rather than overwriting audit history. Reconciliation may move UNKNOWN to SUCCESS or a confirmed safe failure; inconclusive results stay UNKNOWN. If no provider reconciliation exists, the owner may record the located remote post or explicitly acknowledge duplicate risk before requeueing the **same** payload. Record that decision.

Automatic retry applies only when the provider/transport contract establishes safety (or enforces an idempotency key). A generic timeout/5xx after a possible write is not sufficient. Default maximum is three automatic retries after the initial attempt, with bounded exponential backoff and jitter; durable attempts store the count.

### Aggregate publication state

Derive state in this priority order:

1. Any PENDING/RUNNING/RETRY_WAIT → IN_PROGRESS.
2. Otherwise any UNKNOWN → NEEDS_ATTENTION.
3. Otherwise any NEEDS_REVIEW → NEEDS_REVIEW.
4. All SUCCESS → PUBLISHED.
5. At least one SUCCESS and at least one other final state → PARTIAL.
6. No successes, all CANCELED → CANCELED.
7. No successes, all SKIPPED/CANCELED with at least one SKIPPED → SKIPPED.
8. Remaining combinations with no success → FAILED.

The UI always displays per-destination states even while aggregation is IN_PROGRESS. Edit/delete outcomes are separate remote actions, never replacements for the original delivery result.

### Queue and schedule recovery

Persist publication/jobs atomically. Use short DB claims, leases, fencing tokens, bounded parallelism, and network deadlines. A lease expiring while a call may be in progress yields reconciliation/UNKNOWN, not blind re-send. Provider idempotency and staged remote IDs are used where verified; no exactly-once network guarantee is claimed.

Each one-time schedule can create at most one publication, enforced by a unique schedule reference. Claims respect the approved schedule version. Under [ADR-016](ADR-016-operational-and-editorial-policies.md), check the scheduled instant plus 15 minutes before every remote post-create attempt, including retries, reconnect and restored/resumed work. Older work requires renewed time approval; confirmed success and UNKNOWN outcomes remain intact, with read-only reconciliation still available. UC-10 requires explicit owner resolution of DST gaps/overlaps. UC-11 creates independent same-workspace copies, each with its own revision, approval and optional one-time schedule. There is no recurrence or linked repeat series.

Cancellation atomically prevents unclaimed work. For in-flight work it is best effort and cannot imply remote deletion; record confirmed or UNKNOWN outcomes before offering a separate delete.

Emergency dispatch pause prevents new publishing attempts while preserving jobs and recovery information. It cannot stop a request already in flight. Resume obeys original schedule deadlines rather than resetting them; scope and remote-action handling remain open in ADR-016.

## Consequences

Retries preserve approved content and unaffected destinations. Partial publication cannot be rolled back atomically across networks. Clear uncertainty is preferable to a false success/failure or duplicate.
