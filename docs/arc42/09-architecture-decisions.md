# 9. Architecture decisions

The [ADR index](../adr/README.md) is authoritative. These records describe a pre-implementation design baseline. Current exact versions live in the [technology baseline](../technologies.md); optional feasibility policy is not a support claim.

| Decision | Scope |
|---|---|
| [ADR-001](../adr/ADR-001-strategy-pattern-per-platform.md) | Stateless platform strategies with explicit destination context |
| [ADR-002](../adr/ADR-002-spring-boot-thymeleaf-htmx.md) | Latest stable Spring Boot/Java monolith with Thymeleaf + HTMX |
| [ADR-003](../adr/ADR-003-text-model-overrides-mentions-adaptation.md) | Layered overrides and reviewed destination payloads |
| [ADR-004](../adr/ADR-004-domain-model-and-feature-scope.md) | Workspace-scoped editorial and publishing domain |
| [ADR-005](../adr/ADR-005-single-user-built-in-login.md) | One owner login with workspace authorization |
| [ADR-006](../adr/ADR-006-hybrid-credentials-oauth-or-paste.md) | Provider-specific OAuth and pasted credentials |
| [ADR-007](../adr/ADR-007-secrets-in-env-on-volume.md) | Encrypted workspace secrets with externally supplied keys |
| [ADR-008](../adr/ADR-008-app-origin-and-platform-media.md) | HTTPS origin, private sources and platform-hosted images |
| [ADR-009](../adr/ADR-009-postgresql-sidecar.md) | PostgreSQL for tenant data and durable work |
| [ADR-010](../adr/ADR-010-whatsapp-signal-experimental-unofficial.md) | Personal WhatsApp Status and Signal feasibility policy |
| [ADR-011](../adr/ADR-011-telegram-channel-and-group-posts.md) | Telegram channel and group announcements |
| [ADR-012](../adr/ADR-012-docker-multi-arch-compose-nas.md) | Buildpacks images and one multi-architecture Compose stack |
| [ADR-013](../adr/ADR-013-workspace-tenancy-and-multiple-accounts.md) | One-owner installations and workspace-local account sets |
| [ADR-014](../adr/ADR-014-ai-content-assistance.md) | Optional AI assistance before human approval |
| [ADR-015](../adr/ADR-015-publication-lifecycle-and-recovery.md) | Immutable approval, durable delivery, uncertain outcomes |
| [ADR-016](../adr/ADR-016-operational-and-editorial-policies.md) | Owner-approved timing, media, retention, AI and dispatch-pause policies |
