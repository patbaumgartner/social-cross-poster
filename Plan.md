# Plan — Social Cross-Poster

**State:** reviewed 2026-11-13; P0 scaffolding, UC-01 (login + workspace picker), the first UC-02 slice (workspace-scoped connections/destinations, encrypted secrets, telegram/pasted probe), UC-03 (workspace contacts, handles, neutral mention tokens + mention previews), the first UC-04 slice (immutable revisions, destination selection, per-destination previews) and the first UC-05 slice (idempotent workspace-scoped submissions, telegram text publishing with SUCCESS/FAILED/UNKNOWN classification, ADR-015 delivery states + aggregate) implemented 2026-11-14, the first UC-06 slice (read-only publication history: filtered/paginated list + fail-closed detail), the first UC-07 slice (durable retry jobs/leases/worker with bounded backoff, owner retry/reconcile, UNKNOWN reconciliation) the first UC-08 slice (guarded remote edit/delete on confirmed deliveries as separate `remote_action` records via a Telegram remote actor; the original delivery is preserved), the first UC-09 slice (restore an earlier revision as a new immutable revision + optimistic version-conflict guard on save — never last-write-wins) and the first UC-10 slice (one-time schedules via Flyway V8 `publication_schedule`: explicit IANA timezone with owner-resolved DST gap/overlap, frozen revision + payload hash, at most one publication per schedule within the scheduled-instant + 15 min cutoff per ADR-016) the first UC-11 slice (duplicate a post into a new independent DRAFT post in the same workspace, copying the current revision's text/destinations/overrides; approval, schedules, submission keys and outcomes not copied) and the first UC-12 slice (in-app notification events via Flyway V9 `notification_event`: durable, deduplicated per workspace, redacted events emitted on failed/UNKNOWN/needs-attention deliveries, exhausted retries, expired and needs-review schedules, shown on the workspace dashboard) and the second UC-12 slice (webhook channels via Flyway V12 `notification_channel`/`notification_delivery`: SSRF guard per arc42 §8 with DNS re-resolution at send, sealed HMAC secrets (ADR-007), signed deduplicated outbox with bounded 3-attempt delivery and redacted details) and the third UC-12 slice (SMTP channels on the same outbox via Flyway V13: shared SSRF rules + canonical port/TLS mapping, hand-rolled SMTP client with RFC 5321 5xx-permanent semantics, sealed SMTP password AAD `notify-smtp`) and the first UC-13 slice (private media library via Flyway V10 `media_asset`/`revision_media`: local-disk sources, authenticated-only download, sha256 dedup, mandatory alt text, links copied into new revisions; publishing media-attached revisions blocked fail-closed for platforms without upload support; telegram direct upload of the private bytes (`sendPhoto`/`sendMediaGroup`) with a V11 per-delivery media snapshot and a media-aware payload hash; the third UC-13 slice strips outgoing image metadata into a fixed-at-upload derivative so only sanitized bytes are uploaded; the fourth UC-13 slice adds short-lived revocable import grants via Flyway V15 `media_import_grant` (HMAC-signed expiring `/media/grants/{id}` URLs served permit-all with `no-store`, revoked on confirmed import, per-grant fetch rate limit; `supportsMediaImport` flag)); the first UC-14 slice adds the ADR-010 experimental gate (opt-in flag `app.experimental.enabled` off by default, WhatsApp Business permanently excluded, `whatsapp-status`/`signal` unavailable without a passed feasibility probe, permanent “experimental / unofficial” label on the accounts page; no schema change; the second UC-14 slice adds the destination-kind gate — verified kinds only (`status` for whatsapp-status, `message` for signal), Signal story blocked with no message fallback; the third UC-14 slice adds config-bound startup-validated feasibility records (`app.experimental.feasibility`) plus a wired-bridge-adapter check — flag + record + adapter required; the fourth UC-14 slice adds bridge-health blocking of dependent experimental deliveries and the permanent experimental label on history views, the fifth UC-14 slice adds the locally protected pairing/setup path (`/w/{slug}/experiments` + `POST /w/{slug}/experiments/{platform}/pair`, owner login + CSRF on the loopback-only deployment, fail-closed default, no public pairing UI, sanitized instructions only, nothing persisted yet) — no schema change, the first UC-15 slice adds workspace creation from the picker (`POST /workspaces`, fail-closed slug/label/color/timezone validation with duplicate-slug rejection; no schema change) and the second UC-15 slice adds workspace settings (`GET/POST /w/{slug}/settings`, label/color/timezone editable, slug immutable, same fail-closed validation; no schema change) and the third UC-15 slice adds the workspace profile — languages csv + brand voice via Flyway V16 `workspace.languages`/`brand_voice`, same fail-closed validation, blank stores NULL)) and the fourth UC-12 slice (connection/bridge-failure events via Flyway V14 `connection_id` on `notification_event`: redacted CONNECTION_FAILED/CONNECTION_RECOVERED events on connection health-state transitions only, episode-sequenced keys, same webhook/SMTP outbox, dashboard accounts link; and the fifth UC-12 slice (rate-limited shared-outage summaries via `PROVIDER_OUTAGE` events keyed per platform and UTC hour, emitted on UNKNOWN-stuck deliveries; no new tables); and the final UC-12 polish (test notification names workspace and exact recipient)) implemented 2026-11-14…2026-11-17 — see the [UC-01](docs/usecases/UC-01-login.md), [UC-02](docs/usecases/UC-02-manage-accounts.md), [UC-03](docs/usecases/UC-03-manage-contacts.md), [UC-04](docs/usecases/UC-04-compose-post.md), [UC-05](docs/usecases/UC-05-post-now.md), [UC-06](docs/usecases/UC-06-view-history.md), [UC-07](docs/usecases/UC-07-retry-failed-target.md), [UC-08](docs/usecases/UC-08-delete-edit-published-post.md), [UC-09](docs/usecases/UC-09-drafts.md), [UC-10](docs/usecases/UC-10-schedule-post.md), [UC-11](docs/usecases/UC-11-duplicate-and-schedule-posts.md) and [UC-12](docs/usecases/UC-12-failure-notifications.md) and [UC-13](docs/usecases/UC-13-upload-media-to-platform.md) and [UC-14](docs/usecases/UC-14-experimental-targets.md) review logs. The remaining features and acceptance gates below are planned; no provider access is certified.

Source: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster). Follow the [latest-stable technology inventory and Buildpacks-first policy](docs/technologies.md).

## 1. Product goals

- One owner per installation; every other person runs their own installation.
- Separate Private, socrates-ch, Voxxed Days Zurich, Voxxed Days Ticino, and further account sets as workspaces within that installation.
- Connect several accounts of the same platform in each workspace, with independent destination selection and overrides.
- Publish only within the active post's workspace. Private stays within Private; no combined cross-workspace publication or copy workflow.
- Write once, override per platform/destination, optionally use AI for writing/review/correction/translation, then explicitly approve destination previews.
- Publish text and up to four still JPEG/PNG images with alt text. Published images belong to the platform; local source files remain private.
- Save drafts/revisions, schedule individual posts, inspect history, reconcile/retry failures, and edit/delete where verified APIs permit.
- Run a recoverable NAS deployment using current stable technologies and Buildpacks images on amd64 and arm64.

## 2. Scope and exclusions

The initial product includes one-owner login, workspace-local accounts/contacts/content/AI/settings, multiple same-platform destinations, drafts/revisions, optional AI assistance, final previews/approval, durable publishing/history, one-time scheduling, same-workspace post duplication, safe retry, capability-gated edit/delete, notifications, archive/export, emergency dispatch pause, and backup/restore. [ADR-016](docs/adr/ADR-016-operational-and-editorial-policies.md) owns the agreed timing, media, retention, AI and pause policies.

**Excluded:** shared multi-user installations, teams/roles/invitations, public SaaS registration/billing, cross-workspace campaigns/copies, recurring posts/cron/repeat templates, feeds/DM inboxes/analytics, autonomous AI publication, video/animation, automatic thread splitting, and permanent public image hosting. Repeated announcements are independent post instances, each separately reviewed and scheduled.

**WhatsApp Business is excluded:** no Business Cloud API, onboarding, messaging, or OAuth. Personal WhatsApp Status and Signal remain optional unofficial experiments.

## 3. Workspace and identity model

| Account set | Illustrative destinations | Local defaults |
|---|---|---|
| Private | personal Mastodon, Bluesky, LinkedIn profile; two Telegram channels | personal voice |
| socrates-ch | event Mastodon and LinkedIn company page | community voice |
| Voxxed Days Zurich | event LinkedIn page, X, Instagram | event facts and preferred languages |
| Voxxed Days Ticino | a different LinkedIn page, Instagram, two Mastodon accounts | independent facts and Italian/English variants |

These are examples, not claims about actual connected accounts. A workspace owns connections, secrets, destinations, contacts, media, revisions, publications, jobs, schedules, notifications, AI configuration, and usage. Services, queries, foreign keys, caches, callbacks, and jobs enforce that boundary ([ADR-013](docs/adr/ADR-013-workspace-tenancy-and-multiple-accounts.md)).

One connection represents a provider authorization; one account represents a selectable profile/page/chat. Several destinations can share a connection inside one workspace. No platform-only uniqueness constraint or implicit global credential fallback.

“Private” names an account set, not a promise that a social-network post is private. Show the real audience/visibility supported by each destination.

## 4. Integration and image policy

The [catalog](docs/platforms/README.md) owns candidate integrations and their dated verification records. API availability alone does not prove application access. Verify scopes, identity, media upload, counting rules, quotas, edit/delete, refresh, and reconciliation per destination.

LinkedIn personal and company pages are initial-release requirements alongside Bluesky, Mastodon, and Telegram channel/group posting. App approval/page-role obstacles are explicit release blockers for the affected LinkedIn requirement, not grounds to silently move it to later scope.

Images must be transferred into the destination platform and referenced by its media identifiers. Private source storage supports drafts and retries; permanent public image delivery is excluded. Direct upload is preferred; temporary public URLs are allowed only for platform import, then expire or are revoked. The published image must remain available from the platform afterward. The transport decision and sources live in [ADR-008](docs/adr/ADR-008-app-origin-and-platform-media.md).

## 5. Core design

- Stateless platform strategies receive explicit workspace/destination/credential context.
- Override precedence: destination → platform → default; absent fields inherit and explicit empty values clear.
- Shared deterministic adapters power preview and execution. No silent truncation, dropped images, audience changes, or invented native mentions.
- Versioned drafts produce immutable approved payloads per destination. A content/identity/media change requires review.
- Durable database jobs use short claims, leases, fencing, bounded parallelism, persisted provider-upload stages, and safe reconciliation.
- Ambiguous writes become UNKNOWN rather than triggering blind retries. Retries reuse approved content and only affected destinations.
- Optional AI is an isolated editorial service with workspace context, accepted diffs, usage controls, and no publishing tools.
- PostgreSQL/Flyway enforce workspace foreign keys and publication/schedule uniqueness. The [domain model](docs/adr/ADR-004-domain-model-and-feature-scope.md) defines responsibilities.

## 6. Delivery roadmap

| Phase | Deliverable | Acceptance gate |
|---|---|---|
| P0 — foundation | Latest stable stack, Buildpacks/Compose, DB/migrations, owner login, workspace/account/secret storage | Four account sets; multiple same-platform destinations; isolation tests; encrypted secrets |
| P1 — editorial and priority publishing | Drafts/media/contacts/overrides, optional AI, previews/approval, durable post-now/history; Bluesky, Mastodon, Telegram, LinkedIn personal and company pages | Authorized text/image publication for every initial destination kind; distinct LinkedIn page authors; AI outage preserves manual publishing; duplicate/UNKNOWN recovery tested |
| P2 — reliable v1 operations | One-time schedules, independent duplicate posts, safe retry, verified edit/delete, notifications, archive/export, restore, multi-arch CI | Copies stay in the original workspace and require fresh approval/time; cancellation/DST/misfire/restart tests; restored data, media and keys |
| P3 — additional official integrations | X, Instagram, Threads | Per-target access and media-policy gates; incompatible image modes remain unavailable without silently discarding images |
| P4 — optional extensions | Facebook Pages; personal WhatsApp Status and Signal feasibility; workspace-filtered calendar | Verified capabilities, isolated experiments, no WhatsApp Business or cross-workspace dispatch |

## 7. One-time scheduling and repeated announcements

Choose a local datetime with an explicit IANA timezone, store the chosen UTC instant, and show the offset before approval. A DST gap requires the owner to select a valid time; an overlap requires an explicit choice between the two offsets. Never shift or choose automatically.

A schedule references one approved post/payload set and creates at most one publication. Duplicate submissions/dispatches return that publication. Every remote post-create attempt, including retries and work resumed after downtime, reconnect or pause, must start no later than 15 minutes after the scheduled instant; later work requires renewed time approval. Preserve UNKNOWN outcomes and allow read-only reconciliation after expiry. Cancellation is atomic before claim and best effort in flight.

To announce again, copy the post into a new draft in the **same workspace**, preserving editable content/local targets and private source references but clearing approval, schedule, external IDs, attempts, and outcomes. Review changes and choose a new time. Edits/cancellation of one instance cannot change another. There is no recurrence engine, linked repeat series, or automatic date substitution.

## 8. Risks and mitigations

| Risk | Required response |
|---|---|
| Wrong brand/account | Workspace-local selection and ownership checks, persistent identity/audience preview |
| Duplicate remote post | Local submission/schedule uniqueness, provider idempotency where available, UNKNOWN reconciliation |
| AI changes event facts | Selected context, diff/anchor warnings, human acceptance and factual review |
| API access or media-policy mismatch | Early LinkedIn access/upload probes; per-target capability gates and clear unavailable modes |
| Credential expiry or rate limits | Connection-scoped repair/refresh and actual provider quota scopes |
| Delayed announcement | Explicit local time, bounded catch-up, review after stale schedule |
| NAS data/key loss | Consistent DB/private media backups, separately protected keys, isolated dispatch-disabled restore |

## 9. Definition of done

- P0–P2 pass, including LinkedIn personal and company-page text/image publishing, Bluesky, Mastodon, and Telegram, with architecture smoke tests.
- The owner can use multiple same-platform accounts in each workspace without any cross-workspace publication, autocomplete, media, AI, or notification leakage.
- AI accept/reject/conflict, immutable previews, independent copies/schedules, partial/UNKNOWN outcomes, safe retries and restart recovery are tested.
- Published images remain platform-hosted; no permanent app-hosted image endpoint is needed.
- Encrypted secrets and private source media survive a tested backup/restore; current-version/SBOM inventory and Buildpacks release evidence exist.
- Documentation and setup guides distinguish implemented behavior from candidates and access blockers.
