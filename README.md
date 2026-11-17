# Social Cross-Poster

[![CI](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/ci.yml)
[![Dependency Review](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/dependency-review.yml/badge.svg)](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/dependency-review.yml)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/patbaumgartner/social-cross-poster/security/dependabot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A planned self-hosted application for writing, reviewing, and publishing content across several brands and social accounts. Inspired by [crosspost](https://github.com/humanwhocodes/crosspost), with a browser UI and a Docker Compose deployment for a NAS.

**Status: P0 scaffolding landed 2026-11-14 — the application builds and runs, [UC-01 (login + workspace picker)](docs/usecases/UC-01-login.md) is implemented and tested, the first slice of [UC-02 (accounts)](docs/usecases/UC-02-manage-accounts.md) (telegram/pasted connections with AES-256-GCM-encrypted secrets + manual destinations) is in, [UC-03 (contacts)](docs/usecases/UC-03-manage-contacts.md) (workspace contacts, platform handles, neutral mention tokens, @-autocomplete, per-platform mention previews) is implemented, the first slice of [UC-04 (compose)](docs/usecases/UC-04-compose-post.md) (immutable post revisions, destination selection, per-destination previews with counters/mention resolution/blockers) is in, and the first slice of [UC-05 (publish now)](docs/usecases/UC-05-post-now.md) (idempotent workspace-scoped submissions, immutable per-destination payloads, telegram text publishing with SUCCESS/FAILED/UNKNOWN classification, ADR-015 aggregate states) is in, the first slice of [UC-06 (history)](docs/usecases/UC-06-view-history.md) (read-only publication history: filtered/paginated list + fail-closed detail) and the first slice of [UC-07 (retry/reconcile)](docs/usecases/UC-07-retry-failed-target.md) (durable retry jobs with a lease-guarded worker, bounded backoff, owner retry/reconcile actions, UNKNOWN reconciliation) the first slice of [UC-08 (remote edit/delete)](docs/usecases/UC-08-delete-edit-published-post.md) (guarded remote edit/delete on confirmed deliveries as separate action records via a Telegram actor; original delivery preserved), the first slice of [UC-09 (drafts)](docs/usecases/UC-09-drafts.md) (restore an earlier revision as a new immutable revision + optimistic version-conflict guard on save; never last-write-wins) and the first slice of [UC-10 (schedule)](docs/usecases/UC-10-schedule-post.md) (one-time schedules: explicit timezone with owner-resolved DST gap/overlap, frozen revision + payload hash, at most one publication per schedule within the +15 min cutoff, ADR-016) the first slice of [UC-11 (duplicate)](docs/usecases/UC-11-duplicate-and-schedule-posts.md) (duplicate a post into a new independent draft in the same workspace; approval/schedules/outcomes not copied, source untouched) and the first slice of [UC-12 (notifications)](docs/usecases/UC-12-failure-notifications.md) (in-app redacted, deduplicated, workspace-scoped events on delivery/schedule outcomes shown on the dashboard) and the second slice of [UC-12 (notifications)](docs/usecases/UC-12-failure-notifications.md) (webhook channels: SSRF-guarded URLs per arc42 §8, sealed HMAC signing secrets, signed bounded delivery with a 3-attempt budget via Flyway V12, dashboard channel management) and the third slice of [UC-12 (notifications)](docs/usecases/UC-12-failure-notifications.md) (SMTP channels on the same outbox via Flyway V13: shared SSRF guard + canonical port/TLS mapping, hand-rolled SMTP client with RFC 5321 semantics, sealed SMTP password) and the first slice of [UC-13 (media)](docs/usecases/UC-13-upload-media-to-platform.md) (private workspace media library with authenticated-only download and mandatory alt text; publishing media-attached revisions to platforms without upload support is blocked fail-closed; the second UC-13 slice adds telegram direct upload of the private bytes (`sendPhoto`/`sendMediaGroup`) with a V11 per-delivery media snapshot; the third UC-13 slice strips outgoing image metadata into a fixed-at-upload derivative so only sanitized bytes are uploaded; the fourth UC-13 slice adds short-lived revocable import grants (Flyway V15 `media_import_grant`: HMAC-signed expiring permit-all URLs with `no-store` serving only the stripped derivative, revoked on confirmed import, per-grant fetch limit, `supportsMediaImport` flag)) and the fourth slice of [UC-12 (notifications)](docs/usecases/UC-12-failure-notifications.md) (connection/bridge-failure events via Flyway V14 `connection_id` on `notification_event`: redacted CONNECTION_FAILED/CONNECTION_RECOVERED events emitted only on connection health-state transitions with episode-sequenced keys, enqueued to the same webhook/SMTP outbox and linked to the accounts page on the dashboard; and the fifth slice of UC-12 (rate-limited shared-outage summaries: `PROVIDER_OUTAGE` events keyed per platform and UTC hour, emitted when a delivery stays UNKNOWN after reconciliation); and the final UC-12 polish (test notification names the workspace and exact recipient)) landed 2026-11-15/2026-11-16, and the first UC-14 slice (ADR-010 experimental gate: opt-in flag off by default, WhatsApp Business permanently excluded, experimental targets unavailable without a passed feasibility probe, permanent “experimental / unofficial” label; no schema change; the second UC-14 slice adds the destination-kind gate — verified kinds only, Signal story blocked with no message fallback; the third UC-14 slice adds config-bound startup-validated feasibility records plus a wired-bridge-adapter check; the fourth UC-14 slice adds bridge-health blocking of dependent experimental deliveries plus the permanent experimental label on history views, the fifth UC-14 slice adds the locally protected pairing/setup path (`/w/{slug}/experiments`, fail-closed without a wired bridge, no public pairing UI) — no schema change, the first two slices of [UC-15 (workspaces)](docs/usecases/UC-15-manage-workspaces.md) (create a workspace from the picker via `POST /workspaces`, fail-closed validation, duplicate-slug rejection; edit label/color/timezone via `GET/POST /w/{slug}/settings`, slug immutable; no schema change)) landed 2026-11-17.** The rest of UC-02…UC-16 is still design only. A Compose baseline (`docker-compose.yml`, app + private PostgreSQL) exists, but no release image is published yet, so there is no packaged quick start.

## One owner per installation, several account sets

Each person runs their own installation with one owner login. **Private**, **socrates-ch**, **Voxxed Days Zurich**, and **Voxxed Days Ticino** are separate workspaces (tenants/account sets) within that installation.

Each workspace owns accounts, contacts, drafts, media, history, schedules, notification channels, and AI settings. Connect multiple accounts of the same platform, including several Mastodon identities, LinkedIn personal profiles and company pages, or Telegram channels.

A post publishes only to selected accounts within its workspace. Posts in Private stay within the Private account set; no publishing action spans workspaces and there is no cross-workspace copy or campaign workflow. Workspace separation does not determine the public/private visibility of the resulting platform post: its actual audience is shown in the destination preview.

## Intended workflow

1. Choose a workspace and write a draft with text and up to four still images with meaningful alt text.
2. Optionally ask the **AI assistant (ChatGPT-style, via the OpenAI API)** to write, review, correct, shorten, translate, or suggest variants using that workspace's language and brand voice.
3. Compare suggestions and accept changes explicitly.
4. Select workspace-local destinations and review the exact identity, audience, text, mentions, and images for each.
5. Publish now or approve a one-time schedule. To send similar content again, duplicate it into a separate draft in the same workspace and schedule that post individually.

AI never publishes or schedules. Manual editing and publishing remain available without AI. There are no recurring posts or automatic repeat rules.

## Platform scope

The initial delivery includes **Bluesky, Mastodon, Telegram channel/group posts, LinkedIn personal profiles, and LinkedIn company pages**. LinkedIn page access and required app permissions must be proven before the initial release is complete.

X, Instagram, and Threads follow through the [integration catalog](docs/platforms/README.md). Facebook Pages are optional later work. Every integration is currently planned rather than implemented.

**WhatsApp Business is not supported:** no Cloud API, Business onboarding, messaging, or OAuth. Personal WhatsApp Status and Signal remain separate optional unofficial feasibility work.

## Images and architecture

Published images must be stored by the destination platform. The app keeps private source files for drafts, previews, scheduling, and retries; it does not serve permanent public images or insert its own image URLs into published posts. Media transport must satisfy [ADR-008](docs/adr/ADR-008-app-origin-and-platform-media.md).

Use a Spring Boot/Java monolith with Thymeleaf + HTMX, PostgreSQL, workspace-scoped private storage, and a durable publishing queue. Approved revisions and delivery attempts remain auditable. Credentials are encrypted with keys supplied outside the database. Follow the [latest stable technology baseline](docs/technologies.md) and Cloud Native Buildpacks/Paketo image policy.

Source repository: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster).

## Development

Requires Java 27. The Maven Wrapper is pinned with a checksum.

```sh
# Fail-closed owner login (ADR-005): generate an adaptive hash, e.g.
# htpasswd -bnBC 10 "" "your-password" | cut -d: -f2   # -> $2a$10$...
export APP_USER=owner
export APP_PASSWORD_HASH='{bcrypt}$2a$10$...'
# Fail-closed workspace secret encryption key (ADR-007): 32 random bytes,
# base64-encoded. Generate once: openssl rand -base64 32
export APP_SECRETS_KEY="$(openssl rand -base64 32)"
export APP_SECRETS_KEY_ID=v1
./mvnw clean verify        # format check + build + tests (H2 in PostgreSQL mode)
./mvnw spring-boot:run     # serves :8080; actuator/health on :8081
```

Deploy baseline (no published image yet): `cp .env.example .env`, fill it in, then
`docker compose up -d` — see [docs/operations/README.md](docs/operations/README.md).

## Documentation

- [Product plan and delivery gates](Plan.md)
- [Latest technology versions and Buildpacks policy](docs/technologies.md)
- [Critical review and additional ideas](docs/documentation-review.md)
- [Use cases](docs/usecases/README.md)
- [Architecture decisions](docs/adr/README.md)
- [arc42 architecture](docs/arc42/00-index.md)
- [Integration catalog](docs/platforms/README.md)
- [Operations and recovery](docs/operations/README.md)

## Community

- [Contributing](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Support](SUPPORT.md)

## License

Licensed under the [Apache License 2.0](LICENSE).
