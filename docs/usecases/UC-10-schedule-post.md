# UC-10 — Approve and schedule a one-time publication

- **Actor:** Owner; scheduler executes
- **Trigger:** Schedule from a validated draft
- **Preconditions:** Active workspace; final destination payloads reviewed
- **Related decisions:** ADR-004, ADR-009, ADR-015, ADR-016

## Main flow

1. Choose local date/time and an explicit IANA timezone, defaulting to the workspace setting. Show offset and UTC instant.
2. Resolve DST before approval with an explicit owner choice: a gap requires a valid replacement time; an overlap offers both offsets. Never shift the time or select an offset automatically.
3. Confirm workspace, destinations, frozen revision/payloads, and time; persist the schedule, approved semantic payload snapshots, capability versions, and approval metadata.
4. The scheduler creates at most one publication for that schedule when due, claims durable work, and uses UC-05 without invoking AI.
5. Revalidate rights/capabilities. If semantic payload or identity would change, mark affected work NEEDS_REVIEW.
6. Show the linked publication, actual execution time, delay, and individual outcomes in history.

## Alternative flows

- Cancel/edit/run-now races are resolved transactionally against job claim/version. Cancel unclaimed work atomically; report possible remote effects for in-flight work.
- Editing content, targets, time, or policy requires renewed approval; there is no arbitrary one-minute edit window.
- Before every remote post-create attempt, including automatic/manual retries and work resumed after downtime, reconnect or pause, enforce the scheduled instant plus 15 minutes. Later work requires renewed time approval and notification. Read-only reconciliation remains possible; preserve confirmed success and UNKNOWN outcomes.
- Workspace/destination archive or revoked rights prevents dispatch; healthy independent destinations can still proceed according to their approved payloads.
- Temporary import grants and provider upload stages are created near actual dispatch, never while saving a distant schedule.

## Postconditions and UI

The approved intent survives restart. Scheduling means best-effort execution when dependencies are available, not guaranteed delivery at an exact instant. Show the timezone alongside every scheduled time.

## Review log

- 2026-11-13 — Reviewed against ADR-004, ADR-009, ADR-015 and ADR-016. Explicit IANA timezone with DST gap/overlap resolution, frozen payload snapshots at approval, durable one-shot scheduling and the scheduled-instant-plus-15-minutes dispatch cutoff match ADR-009/ADR-015/ADR-016; best-effort semantics match the ADR-004 domain scope. No behavior change.
