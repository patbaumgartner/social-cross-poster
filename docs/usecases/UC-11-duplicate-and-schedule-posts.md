# UC-11 — Duplicate a post and schedule an independent instance

- **Actor:** Owner
- **Trigger:** Duplicate a draft or historical post
- **Preconditions:** Logged in; source belongs to the active workspace
- **Related decisions:** ADR-003, ADR-004, ADR-013, ADR-015

## Main flow

1. Select Duplicate on a local draft/publication and show the source workspace.
2. Create a new draft ID and revision in that same workspace. Copy editable text, overrides, local destination choices, and private source media/alt text.
3. Clear approval, schedule, submission keys, external media/post IDs, delivery attempts and outcomes.
4. Edit the new instance as needed; validate destinations/media again. Source-media references remain private and reference-counted in that workspace.
5. Approve the new instance and choose its own one-time schedule through UC-10, or publish now through UC-05.
6. Show both posts independently in drafts/history/schedules.

## Alternative flows

- Repeat Duplicate to prepare several independent announcements; each has its own approval/time and can be edited/canceled separately.
- Archived/disabled destinations require repair or deselection; do not substitute another account.
- A missing source image requires replacement before image publication.
- Canceling or editing one post never changes another. Deleting one source reference does not remove images needed by the other.
- A different workspace cannot be chosen as the copy destination. Private content stays in the Private account set.

## Postconditions and UI

The new post has its own lifecycle and no authority to publish until approved. No repeat checkbox, recurrence rule, series linkage, cron job or automatic date replacement exists.

## Review log

- 2026-11-13 — Reviewed against ADR-003, ADR-004, ADR-013 and ADR-015. Independent copy with cleared approval/schedule/external identifiers, private reference-counted source media and same-workspace-only duplication match ADR-003 (revisions/overrides), ADR-004 (domain), ADR-013 (tenancy) and ADR-015 (immutable approval/lifecycle). No behavior change.
- 2026-11-15 — First slice implemented (main-flow steps 1–3 and 6, building on the UC-04/UC-09 slices): `PostService.duplicatePost` loads the source post and its current revision fail-closed (foreign post ids → "Unknown post for this workspace.", ADR-013) and creates a NEW post id in the same workspace whose first immutable revision copies the default text, selected destinations and layered overrides (destination/override targets are revalidated against the workspace); the title gets a `(copy)` suffix (step 2). Approval, schedules, submission keys, external ids and delivery outcomes are NOT copied — the new post starts as DRAFT with version 1 and no authority to publish until approved through UC-05/UC-10 (step 3, postconditions); the source post and all its revisions stay untouched, so canceling or editing one never changes the other (alternative flows 1 and 4). Web: `POST /w/{slug}/posts/{id}/duplicate` redirects to the new draft with a flash; "Duplicate" buttons on the post page and the drafts list; both posts show independently in the drafts list (step 6). No new tables — Flyway stays V8. Deviations closed by later slices: no source media/alt text copying or reference counting (step 2/4, UC-13), no duplicate button on history entries (step 1), no repeat/series linkage — explicitly out of scope (postconditions), and a different workspace can never be chosen as the copy destination (alternative flow 5, enforced by the workspace-scoped route). 187/187 tests green.
