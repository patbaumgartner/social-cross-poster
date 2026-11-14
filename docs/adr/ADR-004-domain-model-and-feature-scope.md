# ADR-004 — Workspace-scoped editorial and publishing domain

- **Status:** Accepted; amended during the pre-implementation review; the `post`/`post_revision`/`revision_destination`/`revision_override` slice is implemented 2026-11-14 (UC-04 first slice, Flyway V4 — media, publication, delivery, schedule, AI and notification entities remain planned)
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
