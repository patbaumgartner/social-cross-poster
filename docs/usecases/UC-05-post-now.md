# UC-05 — Approve and publish now

- **Actor:** Owner; system workers execute
- **Trigger:** Confirm publication after UC-04
- **Preconditions:** Logged in; active workspace; valid immutable revision and at least one local destination
- **Related decisions:** ADR-001, ADR-003, ADR-015

## Main flow

1. Present a final summary: workspace, exact destination identities/audiences, final texts/images/alt text, accepted adaptations, and any warning.
2. Bind approval to revision/payload hashes, destinations, and capability versions. Persist a publication, one delivery per destination, and jobs atomically under a workspace-scoped submission key.
3. Return the persisted result page immediately; publication continues if the browser closes.
4. Workers claim jobs, revalidate authorization/capability compatibility, and publish with bounded parallelism. Upload/container stages and acknowledgements are persisted.
5. Record each destination's state and redacted attempts/external references. An uncertain remote write becomes UNKNOWN; a safe transient failure may enter RETRY_WAIT.
6. Derive aggregate status using ADR-015 and send deduplicated workspace notifications ([UC-12](UC-12-failure-notifications.md)) for actionable failures/uncertainty.

## Alternative flows

- Double click or network resubmit with the same key returns the same publication; different content under that key is rejected.
- Timeout/connection loss after a possible write is UNKNOWN, requiring reconciliation before retry (UC-07).
- Authentication/identity/capability changes before dispatch block only affected deliveries, with review or repair details.
- Provider media fetch failure records the stage and asset/grant ID without exposing bearer URLs.
- Cancel before claim is definitive; in-flight cancellation cannot promise a remote post was prevented or deleted.
- Similar-content hashes can warn about accidental repetition; an intentional new publication remains possible.

## Postconditions and UI

Approved payloads and delivery history are immutable. Poll persisted destination states; show external links only when verified. PUBLISHED means every selected delivery succeeded; PARTIAL and NEEDS_ATTENTION have distinct meanings.

## Review log

- 2026-11-13 — Reviewed against ADR-001, ADR-003 and ADR-015. Atomic submission under a workspace-scoped key, immutable payload binding, UNKNOWN for ambiguous writes and the ADR-015 aggregate rule all match. The notification step now links UC-12 explicitly. No behavior change.
