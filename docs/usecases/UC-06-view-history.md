# UC-06 — View workspace publication history

- **Actor:** Owner
- **Trigger:** Open History
- **Preconditions:** Logged in; explicit workspace
- **Related decisions:** ADR-004, ADR-013, ADR-015

## Main flow

1. List this workspace's publications with content summary, timestamps, aggregate state, and individual destination states.
2. Filter by account, platform, status, date, or text with pagination.
3. Open a detail showing the approved revision and actual destination payloads, media/alt text, provider identity, external IDs/URLs, redacted attempts, and subsequent edit/delete actions.
4. Inspect UNKNOWN outcomes and reconciliation options (UC-07).
5. Duplicate into a new draft in the same workspace (UC-11), then choose a new one-time schedule if needed. Approval, schedule and delivery IDs never carry over; there is no cross-workspace copy action.

## Alternative flows

- Scheduled items link to their schedule, timezone, rule, and approval; unsent drafts remain in Drafts.
- A remote link may be stale or manually deleted. State that last confirmed status is historical; no background health check is assumed to inspect every remote post.
- Archived destinations retain historical identity labels even after credentials are removed.
- AI provenance indicates accepted assistance without exposing full rejected prompts or other workspace context.
- A crafted cross-workspace detail URL fails ownership checks rather than returning content.

## Postconditions and UI

Browsing is read-only; retry/edit/delete each requires its own explicit action. Display delivery state separately from later remote action state, with clear labels for uncertain outcomes.

## Review log

- 2026-11-13 — Reviewed against ADR-004, ADR-013 and ADR-015. Read-only workspace-scoped history, archived-destination labels, cross-workspace URL rejection and the delivery-vs-remote-action state split match the domain model (ADR-004), tenancy rules (ADR-013) and lifecycle states (ADR-015). No behavior change.
- 2026-11-15 — **Implemented (first slice, read-only).** `GET /w/{slug}/history` lists the workspace's publications newest-first with content summary (post title + payload snippet), timestamp, aggregate state badge and per-destination state badges; filterable by destination, platform, aggregate state, case-insensitive text (title/payloads) and inclusive created-at date range, paginated (0-based pages, size clamped 1–100; in-memory pagination — small per-owner volumes and optional filters make derived queries impractical). `GET /w/{slug}/history/{id}` shows the approved revision text, submission key, payload hash and every delivery with provider identity, external ref, the immutable payload snapshot and the append-only redacted attempts. `HistoryService` is read-only and every lookup is workspace-scoped — unknown slugs redirect to `/`, and unknown or cross-workspace publication ids fail closed back to the list with an error (ADR-013), so a crafted cross-workspace URL never returns content (matches alternative flow). A topbar "History" link was added. Deviations, closed by later slices: step 3's media/alt text (UC-13) and subsequent edit/delete actions (UC-07/UC-08); step 4's UNKNOWN inspection/reconciliation options (UC-07); step 5's duplicate-into-draft (UC-11); scheduled-item links (UC-09/UC-10). No remote health check is performed — the displayed state is the last confirmed historical state, matching the alternative flow. Verified by `HistoryServiceTest` (9 tests) + `HistoryFlowTest` (8 tests), 107/107 green.
