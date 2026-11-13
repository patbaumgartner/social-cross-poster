# ADR-016 — Owner-approved operational and editorial policies

- **Status:** Accepted by owner; implementation pending
- **Date:** 2026-11-13
- **Amends:** ADR-003, ADR-008, ADR-014 and ADR-015

## Context

The owner accepted the review's proposed policies for late publication, daylight-saving time, unsupported alt text, outgoing image metadata, retention, AI configuration and emergency dispatch pause. These policies describe intended behavior, not implemented safeguards.

## Decision

### Late scheduled publication and DST

An approved one-time schedule permits remote post creation until 15 minutes after its scheduled UTC instant, inclusive. Check this boundary immediately before every remote post-create attempt, including automatic/manual retries, work resumed after reconnect, and restored jobs. Starting an upload or claiming a job on time does not extend this allowance. Beyond it, require renewed time approval before another create attempt; never resend automatically because a retry was already queued.

Keep confirmed successes and UNKNOWN outcomes intact. Read-only reconciliation remains available after the deadline; expiry is not evidence that an earlier write failed. A provider can still finish a request already in flight, so this is an attempt-start boundary, not a guarantee of the platform's final publication time.

For an invalid local time in a DST gap, ask the owner to select a valid time. For an overlap, present both offsets and require an explicit choice. Do not automatically shift a gap or pick the earlier overlap offset. Persist the chosen timezone, offset and UTC instant with the approval.

### Alt text and outgoing image metadata

Require meaningful local alt text for every published image. If a verified destination API cannot transmit it, show the limitation and require explicit acknowledgement bound to that destination's approval. Local descriptions are not evidence that accessibility metadata reached the platform. Do not silently add descriptions to the visible message.

Remove GPS/camera metadata from outgoing image derivatives; private originals may retain it. Review and approve the exact derivative that will be uploaded. A metadata-policy change cannot silently replace bytes under an existing approval. Color-profile, transparency and compression behavior remain separate implementation decisions.

### Retention

Keep approved history and its required source media without automatic expiry under this policy. Unapproved autosave revisions become eligible for pruning after 30 days. Preserve the current draft revision and any revision/media still needed by an approval, schedule, independent copy, active AI request, delivery/recovery stage or media grant. Reclaim source files only when no retained dependency needs them; private originals remain available for retained content. This is revision cleanup, not permission to delete whole drafts, purge workspaces or shorten backup retention.

### AI and emergency dispatch pause

Keep AI disabled until its processing region and spending cap are explicitly agreed and configured, in addition to the workspace opt-in, credential and model requirements. Do not infer a region or budget from the owner's location or subscription.

Provide an emergency dispatch pause that prevents new publishing attempts while preserving jobs, approved content, attempts and recovery information. Already in-flight remote calls may still complete. Read-only reconciliation stays available, and resume must enforce the original schedule deadline; pause never resets lateness or authorizes a catch-up burst.

## Open implementation decisions

- A separate expiry rule for publish-now actions has not been selected; the 15-minute anchor above is an approved schedule instant.
- Pause scope (installation, workspace or both), handling of other remote mutations, durable storage and resume interaction need a concrete implementation design before coding.
- Backup retention, storage quotas, image color/compression rules, AI region and spending cap remain unselected. The 30-day autosave rule does not supply those values.

## Consequences

Retries remain bounded by human timing intent; scheduling asks rather than inventing a DST choice. Accessibility limitations and outgoing image privacy are explicit approval inputs. Dependency-aware cleanup bounds obsolete autosaves while preserving approved history. Platform access, NAS capacity and backup recovery still require separate evidence.
