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
