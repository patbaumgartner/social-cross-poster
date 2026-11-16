# ADR-004 — Workspace-scoped editorial and publishing domain

- **Status:** Accepted; amended during the pre-implementation review; the `post`/`post_revision`/`revision_destination`/`revision_override` slice is implemented 2026-11-14 (UC-04 first slice, Flyway V4) and the `publication`/`delivery`/`delivery_attempt` slice is implemented 2026-11-14 (UC-05 first slice, Flyway V5); read-only history views over those tables are implemented 2026-11-15 (UC-06 first slice) and the `delivery_job` retry/reconcile slice is implemented 2026-11-15 (UC-07 first slice, Flyway V6), the `remote_action`/`remote_action_attempt` slice is implemented 2026-11-15 (UC-08 first slice, Flyway V7), the first UC-09 slice is implemented 2026-11-15 (restore an earlier revision as a new immutable revision + optimistic version-conflict guard on save — no new tables) and the `publication_schedule` slice is implemented 2026-11-15 (UC-10 first slice, Flyway V8 — one-time schedules with frozen revision + payload hash, explicit timezone/DST resolution, UTC instant and the +15 min dispatch cutoff); the first UC-11 slice is implemented 2026-11-15 (duplicate a post into a new independent DRAFT post in the same workspace, copying the current revision's text/destinations/overrides — no new tables) and the first UC-12 slice is implemented 2026-11-15 (Flyway V9 `notification_event` — durable, deduplicated, redacted in-app events emitted on delivery/schedule outcomes and shown on the workspace dashboard) and the first UC-13 slice is implemented 2026-11-15 (Flyway V10 `media_asset` + `revision_media` — private workspace media library with authenticated-only download, sha256 dedup, mandatory alt text, current-revision-only attach/detach, media links copied into new revisions; Flyway V11 `delivery_media` snapshots the approved attached images per delivery and telegram uploads them directly (multipart `sendPhoto`/`sendMediaGroup` of the private bytes, no public URL, ADR-008); the UC-13 third slice (outgoing image-metadata stripping into a fixed-at-upload `out-<sha256>` derivative, private originals untouched — no new tables) is implemented 2026-11-15 and the second UC-12 slice is implemented 2026-11-16 (Flyway V12 `notification_channel` + `notification_delivery` outbox — SSRF-guarded webhook channels per arc42 §8 with sealed HMAC signing secrets (ADR-007), signed payloads with stable event id/timestamp, deduplicated bounded delivery with a 3-attempt budget) and the third UC-12 slice is implemented 2026-11-16 (Flyway V13 — SMTP channel type on the same outbox: shared SSRF host rules + canonical port/TLS mapping via `OutboundSmtpGuard`, hand-rolled `SmtpSender` with RFC 5321 5xx-permanent semantics, sealed SMTP password with AAD `<slug>:notify-smtp:<label>`) and the fourth UC-12 slice is implemented 2026-11-16 (Flyway V14 `connection_id` FK on `notification_event`: connection/bridge-failure events emitted only on health-state transitions with episode-sequenced keys, same notification outbox); the fifth UC-12 slice is implemented 2026-11-16 (rate-limited shared-outage summaries: `PROVIDER_OUTAGE` events keyed `outage:<platform>:<UTC-hour>`, one per platform per hour per workspace, emitted on UNKNOWN-stuck deliveries; no new tables); the UC-12 test-notification polish (payload + flash name the workspace and exact recipient) is implemented 2026-11-16; the fourth UC-13 slice is implemented 2026-11-16 (Flyway V15 `media_import_grant` — short-lived revocable import grants scoped to workspace + delivery, minted at dispatch for import-capable platforms and revoked when the provider confirms the import); AI entities remain planned
- **Date:** 2026-11-13

## Context

Draft editing, AI suggestions, scheduled publications, and remote delivery are different lifecycles. A single mutable post and JSON attempts array cannot preserve approved content or recover reliably.

## Decision

Use PostgreSQL and Flyway. The logical model below defines responsibilities rather than final migration DDL.

| Entity | Responsibility / key fields |
|---|---|
| `workspace` | ID, unique slug, label, timezone, locales, brand voice, protected event facts/links, archive state, version |
| `connection` | Workspace, platform, provider principal and host/instance, credential references, scopes, expiry, health |
| `account` | A selectable destination: workspace, connection, platform, destination kind, provider resource ID, label, config, enabled, capability snapshot |
| `secret` | Workspace, typed owner/reference, authenticated ciphertext, nonce, key ID, version; never plaintext provider/AI/SMTP secrets |
| `contact` / `contact_handle` | Workspace person, display name, optional photo, platform/instance-specific identities |
| `post` | Workspace draft container, current revision, optimistic version, editorial state DRAFT/READY/ARCHIVED |
| `post_revision` | Immutable text, layered overrides, selected destination references, media selection/order/alt text, provenance |
| `media_asset` / `revision_media` | Workspace path, checksum, MIME/dimensions/size, derivative metadata; immutable revision references and alt text |
| `publication` | Workspace, revision, approval actor/time/hash, submission key, optional unique schedule reference, aggregate state |
| `delivery` | Workspace, publication, destination snapshot, approved semantic payload/hash, adapter version, state, external references |
| `delivery_attempt` | Workspace, delivery, operation/stage, lease owner, timings, redacted outcome, retry safety, remote upload/container IDs |
| `remote_action` | Workspace, delivery, requested edit/delete/reconciliation, action-specific payload/version/status; preserves original delivery outcome |
| `schedule` | Workspace, approved revision/targets and semantic payload snapshots, approval actor/time/hash and capability versions, one scheduled UTC instant plus chosen timezone/offset, DST resolution and lateness policy, status/version; linked publication resolved through its unique schedule reference |
| `job` | Workspace, kind/reference, due time, lease/token, attempt count, recovery state |
| `ai_config` / `ai_request` / `ai_usage` | Workspace provider/model/credential, opt-in/budget, base revision/action, suggestion/status, reserved/actual usage |
| `notification_channel` / `notification_delivery` | Workspace recipients/config/secret references, event ID, deduplicated deliveries, bounded retries |
| `oauth_state` / `media_grant` / `audit_event` | Workspace-bound temporary authorization, temporary provider-import access, redacted audit trail |

Every tenant-owned table has `workspace_id`. Composite foreign keys require the parent and child to have the same workspace; UUIDs alone are not isolation. Per-workspace uniqueness includes destination kind + provider resource + relevant host/instance. Neither platform nor display name alone is unique. Connections can supply several destinations inside one workspace; no implicit sharing across workspaces.

Enforce unique publication submission keys, one delivery per publication/destination, and at most one publication per one-time schedule. Claim due jobs transactionally, commit before network calls, and use leases/fencing to prevent stale workers updating a newer attempt.

## Feature scope

v1 includes workspace management, account CRUD, contacts, drafts/revisions, optional AI, preview/approval, publication/history, safe retry, verified edit/delete, one-time scheduling and independent same-workspace post duplication, notifications, and recoverable deployment. Phase gates and optional integrations live in [Plan.md](../../Plan.md).

Shared multi-user installations, teams/roles, public SaaS, cross-workspace copying, recurrence, videos, analytics and autonomous AI are excluded. WhatsApp Business is excluded; personal WhatsApp Status and Signal remain optional feasibility work.

## Consequences

The domain has more entities than the earlier sketch, but separates human intent from remote side effects. Relational attempt/job records support recovery and queries. Duplicating a post creates a new draft/revision in the same workspace, retains private source references, and clears approval, schedule and provider/delivery identifiers. There is no repeat-rule or occurrence entity. Revision retention and cleanup respect independent schedules, history, temporary import grants and audit requirements.
