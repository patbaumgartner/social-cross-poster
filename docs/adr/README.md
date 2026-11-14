# Architecture decision records

Design baseline reviewed 2026-11-13. Existing records were amended before implementation; “Accepted” is a design decision, not a shipped feature. ADR-010 accepts a feasibility policy. As of 2026-11-14 the P0 scaffolding, UC-01, the first UC-02 slice, UC-03 and the first UC-04 slice are implemented (ADR-005 login, ADR-006 telegram/pasted probe, ADR-007 AES-256-GCM secret cipher, ADR-009 schema via Flyway V1–V4, ADR-012 Compose baseline, ADR-013 workspace table + tenancy, ADR-003 points 1–3 and 5–6 layered overrides/previews + contacts/handles/neutral mention tokens/verified mention contracts, ADR-004/ADR-015 post/revision slice via V4); the remaining ADRs describe planned behavior. [Plan.md](../../Plan.md) owns delivery gates; [technology baseline](../technologies.md) owns exact latest stable versions.

| ADR | Decision |
|---|---|
| [ADR-001](ADR-001-strategy-pattern-per-platform.md) | Stateless platform strategies with explicit destination context |
| [ADR-002](ADR-002-spring-boot-thymeleaf-htmx.md) | Latest stable Spring Boot/Java monolith with Thymeleaf + HTMX |
| [ADR-003](ADR-003-text-model-overrides-mentions-adaptation.md) | Layered overrides and reviewed destination payloads |
| [ADR-004](ADR-004-domain-model-and-feature-scope.md) | Workspace-scoped editorial and publishing domain |
| [ADR-005](ADR-005-single-user-built-in-login.md) | One owner login with workspace authorization |
| [ADR-006](ADR-006-hybrid-credentials-oauth-or-paste.md) | Provider-specific OAuth and pasted credentials |
| [ADR-007](ADR-007-secrets-in-env-on-volume.md) | Encrypted workspace secrets with externally supplied keys |
| [ADR-008](ADR-008-app-origin-and-platform-media.md) | HTTPS origin, private sources and platform-hosted images |
| [ADR-009](ADR-009-postgresql-sidecar.md) | PostgreSQL for tenant data and durable work |
| [ADR-010](ADR-010-whatsapp-signal-experimental-unofficial.md) | Personal WhatsApp Status and Signal feasibility policy |
| [ADR-011](ADR-011-telegram-channel-and-group-posts.md) | Telegram channel and group announcements |
| [ADR-012](ADR-012-docker-multi-arch-compose-nas.md) | Buildpacks images and one multi-architecture Compose stack |
| [ADR-013](ADR-013-workspace-tenancy-and-multiple-accounts.md) | One-owner installations and workspace-local account sets |
| [ADR-014](ADR-014-ai-content-assistance.md) | Optional AI assistance before human approval |
| [ADR-015](ADR-015-publication-lifecycle-and-recovery.md) | Immutable approval, durable delivery, uncertain outcomes |
| [ADR-016](ADR-016-operational-and-editorial-policies.md) | Owner-approved timing, media, retention, AI and dispatch-pause policies |
