# UC-13 — Transfer approved images to the destination platform

- **Actor:** Publishing worker; platform upload/import service
- **Trigger:** Approved image publication or verified remote image edit
- **Preconditions:** Workspace-local delivery and validated approved source/derivative
- **Related decisions:** ADR-003, ADR-008, ADR-015

## Main flow

1. Verify workspace, destination identity, permissions and approved image bytes/order/alt text.
2. Prefer direct upload: initialize the destination's upload, send image bytes, persist remote IDs and readiness evidence.
3. For a verified source-fetch API, create a short-lived revocable import grant for the exact delivery/derivative. Source URLs are never permanent published image links.
4. Verify provider processing/import at the documented stage and publish using platform-owned media references.
5. Record returned media/post references and retire import grants when the provider no longer requires them. Keep private originals according to revision/retry retention.
6. Show the published platform post independently of local source availability.

## Alternative flows

- Expired import grant before processing: renew for the same approved bytes only where retry/reconciliation is safe.
- Failed direct upload/import/readiness: record the stage and safe recovery path; do not silently strip images or alter the audience.
- Uncertain upload/create acknowledgement: persist remote stage IDs and reconcile under UC-07.
- No platform-owned media mode: block image publishing for that destination; never substitute permanent public hosting.
- Local self-fetch is only a routing check. Provider import evidence is required when temporary URLs are used.
- Drafts, thumbnails and local copies stay authenticated; only an approved dispatch can mint an import grant.
- Copies scheduled for another date create fresh destination upload/import stages near that date. They do not inherit external IDs or expiring grants.

## Postconditions and UI

The provider stores the published image; revoking the temporary source URL must not break it. Private files support editing/scheduling/retries, not public hosting. Setup and preflight explain whether a destination uses direct upload or temporary import.
