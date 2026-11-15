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
- 2026-11-15 — First slice implemented (main-flow steps 1–4, building on the UC-04/UC-05 slices): Flyway V8 `publication_schedule` persists one PENDING row per approved schedule — frozen revision id, semantic payload hash (SHA-256 over the mention-rendered payloads), workspace-scoped `sched-` submission key, local wall-clock time, IANA timezone and the resolved UTC instant (step 1–3, ADR-009/ADR-016). POST `/w/{slug}/posts/{id}/schedule` approves a schedule: destinations and preview blockers are checked first, DST is resolved explicitly before approval — a gap is rejected with guidance and an overlap requires an explicit offset choice, never shifted or picked automatically (step 2, ADR-016) — and the time must be in the future; the post page shows the form (timezone defaulting to the workspace timezone) and lists the post's schedules with state, UTC instant and a cancel button. Cancel (alternative flow 1) atomically transitions PENDING → CANCELED via a conditional update; foreign post/schedule ids fail closed (ADR-013). The `ScheduleWorker` (`@Scheduled`, disabled by `APP_WORKER_ENABLED=false`) runs `runDueSchedules`, which dispatches due schedules: beyond the scheduled instant + 15 minutes the schedule becomes EXPIRED and needs renewed approval — never an automatic resend (alternative flow 3, ADR-016); a changed payload hash since approval becomes NEEDS_REVIEW instead of dispatching (step 5); otherwise the frozen revision is published through the UC-05 path under the schedule's submission key (idempotent per ADR-015), the conditional claim (PENDING → CREATED + `publication_id`) lets exactly one worker create at most one publication per schedule (step 4), and every delivery is executed and settled through the UC-07 path — no AI is invoked. Deviations closed by later slices: no capability-version revalidation beyond the payload hash (step 5), no run-now/edit-schedule endpoints (alternative flow 1), no recurrence engine (Plan section 7), and the linked publication shows in history via the existing UC-06 views rather than a dedicated schedule view (step 6). 182/182 tests green.
