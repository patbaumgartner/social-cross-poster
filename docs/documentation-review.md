# Documentation review — 2026-11-13

This review covers the original README/plan, ADRs, use cases and arc42 views. The repository is a design, not a working implementation.

## Confirmed product decisions

- One owner per installation; every other person runs a separate installation.
- Private, socrates-ch, Voxxed Days Zurich and Voxxed Days Ticino are independent workspace/account sets. Each supports several accounts of the same platform.
- A publication and every selected destination belong to one workspace. No cross-workspace campaigns or copying.
- Initial integrations include Bluesky, Mastodon, Telegram channel/group posts, LinkedIn personal profiles and LinkedIn company pages. Both LinkedIn author kinds are release requirements.
- Similar announcements are separate copied posts within the same workspace, individually reviewed and scheduled. Recurring posts and repeat-rule machinery are removed.
- Published images live on the destination platform. Prefer direct uploads; temporary public URLs are explicitly allowed only for platform import. Private originals support drafting/retries. Permanent public hosting is excluded.
- AI writing/review/correction/translation remains optional and requires explicit acceptance before destination approval.
- Latest stable technologies, Buildpacks-first application images and the canonical [source repository](https://github.com/patbaumgartner/social-cross-poster) remain required.
- Confirmed reference deployment: Synology DS918+, Intel x86_64, 12 GB installed RAM shared with other containers, Docker Engine 24.0.2, Compose 2.20.1 and Traefik; public HTTPS without VPN is possible. These owner-reported inputs still require compatibility tests. Every installation configures its own `APP_BASE_URL`, networking and trusted proxy; neither the owner's hostname nor an exact Traefik version is needed to implement the product. Additional host/proxy version details are diagnostics; available capacity remains to be measured.
- The owner administers LinkedIn pages and nominates 42talents for app onboarding, but has no developer apps/API access configured. Legal-organization eligibility/details, app verification/registration, scopes and live probes remain open; see the [onboarding record](platforms/README.md#linkedin-onboarding-status).
- The owner accepted the timing, media, retention, AI-configuration and emergency-pause proposals in [ADR-016](adr/ADR-016-operational-and-editorial-policies.md). Other recommendations remain proposals; all scope/architecture decisions may still be challenged explicitly.
- WhatsApp Business and the previously removed integrations stay outside scope.

## Critical findings and resolutions

| Finding | Resolution |
|---|---|
| Single user was treated as no tenancy | One owner, workspace-local records/settings/secrets/jobs/media from the first schema |
| Multiple same-platform identities were only implied | Separate connections from independently selectable profiles/pages/chats |
| Earlier roadmap postponed important LinkedIn destinations | Personal profiles and company pages move into initial delivery, including access/image-upload evidence |
| Broad copy/campaign suggestions could mix private and event accounts | Restrict publication and post duplication to one owning workspace |
| Recurrence added unneeded complexity and stale-content risk | Replace with independent post copies and one-time schedules; remove recurrence schema/runtime/UI |
| Permanent image hosting conflicted with the desired publishing model | Platform media ownership; temporary import only; revoke source in tests without breaking published images |
| WhatsApp Business appeared in account/OAuth flows | Explicitly excluded; personal Status remains separate feasibility work |
| No editorial assistance before publication | Optional AI with scoped context, comparison, explicit acceptance and fresh previews |
| Silent truncation, media loss and fabricated mention rules | Block incompatible payloads; visible accepted changes and verified native mention contracts |
| Generic timeout/5xx was automatically retryable | UNKNOWN for possible remote writes; reconcile before safe retry or explicit duplicate-risk choice |
| Retries used mutable current content | Freeze approved destination payloads; changed content requires a new approval/publication |
| Aggregation confused partial and complete success | One state rule, with independent remote edit/delete action history |
| Public routes and credential storage contradicted security claims | Enumerate narrow callback/webhook/import routes, authenticated sources, encrypted DB secrets and separate keys |
| Resource, replica and backup claims had no evidence | Measure capacity; leased jobs, maintenance upgrades and dispatch-disabled restore drills |
| API matrix implied universal verified support | Per-target dated records, app access checks and authorized live probes |
| Outdated technology pins/default Dockerfiles | Sourced latest-stable inventory, tested update policy and Buildpacks per architecture |
| Quick start/scripts/license/image names were fictional | State documentation-only status; use the actual source repository and planned registry path |

Existing ADRs are amended before implementation. Accepted decisions describe intended behavior, not shipped capabilities.

## Useful additions and boundaries

| Idea | Disposition | Purpose |
|---|---|---|
| Workspace brand voice, languages and protected event facts/links | Core scope | Consistent writing without mixing event context |
| Final account/audience checklist and meaningful alt text | Core scope | Prevent wrong-identity publication and make limitations visible |
| Versioned drafts and accepted AI provenance | Core scope | Review, undo and reproducible retries |
| Same-workspace duplication and individual schedules | Core scope | Prepare separate announcements without repeat machinery |
| Workspace archive/content export | Operations scope | Keep event history and a usable exit path |
| Workspace-local calendar and campaign labels | Later convenience | Plan announcements without cross-workspace dispatch |
| Explicit link/UTM suggestions and saved text snippets | Later convenience | Reduce repetition while preserving reviewed links |
| Emergency dispatch pause; scheduled creation cutoff on every retry | Accepted in ADR-016 | Preserve recovery information; pause scope/other mutations and publish-now expiry still need design decisions |
| Shared users, team approval roles, automatic repeats and permanent image hosting | Excluded | Outside the clarified product |

## Remaining implementation evidence

Provider permissions, particularly LinkedIn company pages; exact supported API versions; image-upload/import source independence; optional personal Status/Signal feasibility; AI account/data requirements; and actual NAS/buildpack compatibility still need evidence.

The [technology baseline](technologies.md) records observed stable releases, not proof that the whole combination builds. The [catalog](platforms/README.md) defines per-target verification. These technical probes do not require more product scope assumptions.
