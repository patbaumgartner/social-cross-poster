# 8. Cross-cutting concepts

## Tenancy and authorization

Explicit workspace context flows through controllers, services, repository queries, caches, jobs, media, AI, callbacks, webhooks, and notifications. Fail closed on missing/mismatched context. Composite foreign keys prevent cross-workspace relationships; random IDs alone do not authorize access. One owner manages their account sets inside their installation; other people use separate installations. No operation spans workspaces. The host/DB/admin remains a common trust boundary within that installation.

## Content and external effects

Separate draft revisions, approved destination payloads, publication deliveries, append-only attempts, and remote edit/delete actions. Semantic content is immutable after approval. Capabilities are destination/account/API-specific; unknown operations are disabled. Retry/reconciliation and aggregate state follow ADR-015, including UNKNOWN after a possible remote write.

## Security

Use form login, secure cookies, CSRF on browser mutations, login throttling, escaped content, a tested CSP, and a fixed trusted HTTPS origin. Enumerate public login/assets, OAuth callbacks, enabled secret-validated Telegram webhooks, temporary provider-import grants; management stays private.

Encrypt provider/AI/notification secrets with workspace-bound authenticated data and externally supplied keys. Validate outbound provider instance URLs and notification destinations against SSRF, DNS rebinding, unsafe redirects, link-local/cloud metadata access, and credential forwarding across hosts. Explicit operator allow-lists cover intentional internal integrations; optional sidecars use fixed private service endpoints.

## AI and editorial safety

Send only explicitly selected workspace text/context. Treat prompts, drafts, and outputs as untrusted content; no publishing tools or arbitrary link fetching. Base-version checks, comparison/acceptance, factual-anchor warnings, deterministic previews, and final approval are separate gates. Budget reservations and usage reconciliation do not guarantee an exact provider invoice. Provider retention disclosures follow ADR-014.

AI stays disabled until the processing region and spending cap are explicitly agreed and configured under [ADR-016](../adr/ADR-016-operational-and-editorial-policies.md).

## Time, queue, and rate limits

Store each approved one-time UTC instant plus its IANA timezone/offset and DST/lateness policy. Independent same-workspace copies have separate revisions, approvals and schedules. Short DB claims, leases, fencing, and schedule/submission uniqueness protect local execution; they cannot make remote side effects exactly once. Bound concurrency fairly across workspace queues and provider/connection/app/instance quotas. Rate-limit responses guide retry timing only when repeating the write is safe.

Resolve DST gaps/overlaps through explicit owner choices. Before every scheduled remote post-create attempt, enforce the chosen instant plus 15 minutes, including retries/reconnect/restored/resumed work. An emergency dispatch pause preserves jobs and recovery records while blocking new publishing attempts; resume never resets deadlines. Preserve UNKNOWN and allow read-only reconciliation during pause or after expiry. ADR-016 records the agreed boundaries and remaining pause/publish-now decisions.

## Media and retention

Validate signatures/MIME, dimensions, decode limits, file size, alt text, and derivatives. Store immutable workspace paths; deduplication/reference counting never crosses tenants. Prefer direct uploads into platform storage; verified source-fetch APIs may receive a temporary grant tied to approved import stages. Revoke/expire it after processing; the platform image remains available independently. No permanent public hosting is supported. Cleanup respects private sources referenced by independent copies, revisions, schedules, active grants and recovery. Removing local content does not erase provider copies.

Strip GPS/camera metadata from outgoing derivatives and approve those exact bytes; originals stay private. Require explicit destination-specific acknowledgement when native alt text cannot be transmitted. Keep approved history/required sources without automatic expiry; unapproved autosave revisions are eligible for pruning after 30 days only when no current/retained dependency needs them (ADR-016).

## Observability and verification

Use correlation/workspace/job IDs without logging private bodies, prompts, secrets, or bearer URLs. Monitor queue age, UNKNOWN outcomes, expiry, AI usage, disk, and backup age. Contract mocks test our expected API shapes; they do not prove current provider access. Add authorized live probes, tenant-negative integration tests, crash/timeout/DST scenarios, and restore drills. Current dependency/SBOM inventory and image provenance accompany release evidence.
