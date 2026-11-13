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
