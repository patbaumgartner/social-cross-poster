# Plan — Social Cross-Poster

**State:** design only, reviewed 2026-11-13. Features and acceptance gates below are planned; no implementation or provider access is certified.

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
