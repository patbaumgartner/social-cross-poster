# Social Cross-Poster

[![CI](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/ci.yml)
[![Dependency Review](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/dependency-review.yml/badge.svg)](https://github.com/patbaumgartner/social-cross-poster/actions/workflows/dependency-review.yml)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/patbaumgartner/social-cross-poster/security/dependabot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A planned self-hosted application for writing, reviewing, and publishing content across several brands and social accounts. Inspired by [crosspost](https://github.com/humanwhocodes/crosspost), with a browser UI and a Docker Compose deployment for a NAS.

**Status: P0 scaffolding landed 2026-11-14 — the application builds and runs, [UC-01 (login + workspace picker)](docs/usecases/UC-01-login.md) is implemented and tested, the first slice of [UC-02 (accounts)](docs/usecases/UC-02-manage-accounts.md) (telegram/pasted connections with AES-256-GCM-encrypted secrets + manual destinations) is in, [UC-03 (contacts)](docs/usecases/UC-03-manage-contacts.md) (workspace contacts, platform handles, neutral mention tokens, @-autocomplete, per-platform mention previews) is implemented, and the first slice of [UC-04 (compose)](docs/usecases/UC-04-compose-post.md) (immutable post revisions, destination selection, per-destination previews with counters/mention resolution/blockers) is in.** The rest of UC-02…UC-16 is still design only. A Compose baseline (`docker-compose.yml`, app + private PostgreSQL) exists, but no release image is published yet, so there is no packaged quick start.

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
