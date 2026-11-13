# 6. Runtime view

## R1 — Compose with AI, then approve (UC-04/16)

1. Owner opens a workspace draft and saves a base revision.
2. An explicit AI action reserves local usage and sends selected text/context only.
3. The asynchronous response is escaped and compared with the base revision. Owner accepts changes or rejects; conflicting revisions require fresh comparison.
4. Deterministic adapters render each selected destination. Owner resolves errors and confirms workspace, identities, and final payloads.
5. A publication and jobs are committed atomically; no AI step runs during dispatch.

## R2 — Deliver and recover (UC-05/07)

1. Worker claims a due workspace job with lease/fencing token and commits the claim.
2. Resolve original workspace/destination credentials; revalidate approval-compatible identity/capabilities. Before every post-create attempt, check dispatch pause and the scheduled instant plus 15 minutes where applicable; renew time approval after expiry. Read-only reconciliation remains possible while paused or expired.
3. Record stages, upload approved bytes or mint temporary provider-import grants, and call the provider under bounded deadlines/quotas.
4. Persist confirmed SUCCESS, safe failure/retry, or UNKNOWN remote outcome. Update aggregation and durable notification events.
5. On crash/lease expiry, fence stale writes and reconcile possible remote effects. Requeue only where safety is established or duplicate risk explicitly accepted.
6. Repeated browser submissions return the same publication; a changed draft cannot change an existing delivery.

## R3 — Add another same-platform account (UC-02)

Start provider-supported onboarding with owner/session/workspace-bound state. Callback consumes state once, verifies the provider principal, and encrypts credentials into that original workspace. Add selected local destinations; reconnect never silently substitutes a different principal. Changing tabs/workspaces during authorization cannot change ownership.

## R4 — Independent copies and one-time schedules (UC-10/11)

Duplicate only inside the original workspace into a fresh draft, clearing approval, schedule and external/delivery identifiers. Review and approve each instance separately. A DST gap requires the owner to choose a valid time; an overlap requires an explicit offset choice. Store the chosen UTC datetime with timezone/offset and approved payloads per schedule. Enforce one publication per schedule and the 15-minute creation cutoff on every attempt, including retries/resumed work. Atomically cancel before claim and report uncertainty in flight. Upload/import stages start near dispatch; editing one copy cannot change another.

## R5 — Transfer an image into platform-owned storage (UC-13)

Prefer direct byte upload and persist platform media IDs/readiness. A URL-fetch adapter may use a bounded signed grant binding workspace, derivative, delivery and purpose, with GET/HEAD signature/expiry/revocation checks and no-store caching. After confirmed import, retire the grant; the published image must remain available on the platform. Draft thumbnails and originals stay authenticated. No lasting app-hosted image reference is inserted.

## R6 — Alert and archive (UC-12/15)

Persist deduplicated workspace events with the result, then dispatch sanitized webhook/SMTP alerts independently. Archiving cancels unclaimed work and future schedules, while active/UNKNOWN effects remain visible for reconciliation. It never claims to undo remote publications or revoke unrelated media grants.
