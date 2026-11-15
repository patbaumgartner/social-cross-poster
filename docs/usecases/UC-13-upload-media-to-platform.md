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

## Review log

- 2026-11-13 — Reviewed against ADR-003, ADR-008 and ADR-015. Direct upload vs. short-lived revocable import grants, platform-owned media references, no permanent public source URLs and UNKNOWN reconciliation under UC-07 match ADR-008 (private sources, platform-hosted images) and ADR-015 (delivery states); frozen payloads per ADR-003. No behavior change.
- 2026-11-15 — First slice implemented (private workspace media library + per-revision attachments): Flyway V10 `media_asset` (content-addressed per workspace via `UNIQUE(workspace_id, sha256)`, allow-listed image types png/jpeg/webp/gif, size cap `APP_MEDIA_MAX_BYTES` = 10 MB) and `revision_media` (ordered `UNIQUE(revision_id, media_id)` links with a mandatory local alt text, ADR-016). Sources are stored on local disk under `APP_MEDIA_DIR` (a compose volume in the baseline) and served only through the authenticated download route — no public hosting and no permanent public image links (ADR-008); uploads are deduplicated by SHA-256. Attach/detach are allowed only on the post's current revision so historical revisions stay immutable (ADR-015); every new revision inherits a copy of the superseded revision's media links, and UC-09 restore / UC-11 duplicate copy from their source revision. Publishing a revision with attached media is blocked fail-closed until the platform upload/import slice lands. All routes are workspace-scoped and fail closed; a foreign-workspace download is a 404 that leaks no existence (ADR-013). 209/209 tests green. Deviations closed by later slices: no direct platform upload (step 2), no short-lived revocable import grants or source-fetch API (step 3), no provider processing/import verification or platform-owned media references in published posts (step 4), no remote media reference recording/grant retirement in deliveries (step 5), no outgoing image-metadata stripping policy enforcement yet (ADR-016), and no retention/backup tooling (UC-16).
