# 2. Constraints

## Technical

- Use latest stable/GA technologies. The dated [technology baseline](../technologies.md) owns exact versions and source links; reverify during implementation and releases. No hidden stale dependency pins, prereleases, or untested floating production tags.
- Spring Boot/Java monolith, Maven Wrapper, Thymeleaf + HTMX, local assets; no mandatory SPA/npm build.
- Cloud Native Buildpacks via Spring Boot and Paketo are the default image path. Dockerfiles require a demonstrated buildpack limitation and documented exception.
- Docker Compose on one NAS, tested amd64 and arm64 application images; PostgreSQL private sidecar, Flyway migrations, workspace media storage.
- Fixed production HTTPS origin for selected callbacks/provider media ingestion. Trust only configured reverse proxies.
- Encrypt workspace provider/AI/notification secrets; supply keys separately from DB. Compose passes configuration explicitly.
- Image-only initial scope: up to four still JPEG/PNG uploads with target validation and meaningful alt text. Extra formats/video require a later decision.

## Product and organizational

- One owner login, **multiple tenants/workspaces**, and multiple same-platform destinations are required from the foundation. Every person runs a separate installation; teams/roles/shared SaaS and cross-workspace campaigns/copies are excluded.
- One-time schedules only; similar announcements are independently copied/reviewed/scheduled posts in the same workspace.
- Published images are platform-hosted. Private originals support editing/retries; temporary import URLs are allowed only during provider ingestion, with no permanent public hosting.
- LinkedIn personal and company-page posting are initial-release requirements alongside Bluesky, Mastodon and Telegram.
- AI is optional per workspace and requires external disclosure/usage controls. Manual editing/publishing remains usable without it.
- WhatsApp Business is excluded. Personal WhatsApp Status and Signal are optional experiments behind feasibility and opt-in gates.
- This repository currently contains documentation, not a runnable product. Canonical source: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster).
- Apache-2.0 is intended; supply license text/notices before distributing code.

## Conventions

Use ADRs for decisions, arc42 for architecture views, one file per use case, and dated per-platform verification records. Pin current release inputs, inventory resolved dependencies/SBOM, and automate update PRs with compatibility checks.
