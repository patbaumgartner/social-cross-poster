# 11. Risks and technical debt

## Risks

| Risk | Impact | Mitigation / residual limit |
|---|---|---|
| Tenant/brand confusion or missing workspace predicate | Wrong-brand publication or disclosure | Explicit context, composite constraints, negative tests, identity confirmation; one owner/host still trusts all workspaces |
| Remote write succeeds before response/crash | Duplicate publication on retry | UNKNOWN, staged references, provider idempotency/reconciliation, explicit duplicate-risk decision where unsupported |
| AI alters facts, token references, or brand context | Incorrect event communication | Minimal scoped context, comparisons, anchor warnings, human acceptance and factual review; no factual guarantee |
| AI provider retention or runaway usage | Privacy/cost exposure | Workspace opt-in, stateless requests, server-held keys, limits/reservations, current provider controls |
| API access, format, scope, or pricing changes, especially LinkedIn company-page rights | Initial required integration unavailable | Per-target dated record/probes and isolated adapters; mock tests alone do not establish access |
| Credentials expire or principal changes | Failed post or wrong identity | Connection health, verified refresh/reconnect, identity binding and reapproval |
| Public fetch/callback attack surface | Secret/media exposure | Narrow route contracts, fixed origin, trusted proxies, purpose-bound grants, SSRF and file validation |
| NAS data/key loss or inconsistent backup | Unrecoverable credentials/content | Maintenance backup, separate keys, isolated restore drill; ciphertext cannot recover a lost key |
| Latest release incompatibility or stale builder JVM | Failed build/runtime or false currency claim | Dated inventory, dependency/SBOM review, compatibility tests and explicit exception owner/date |
| Optional unofficial bridge instability/session leakage | Failed Status/story or account restriction | Feasibility gate, isolated pinned sessions, no audience fallback; outside core release |
| Single host/local media storage | Downtime | Accepted v1; restore-tested operations; multi-replica/HA needs a separate design |
| Provider still depends on temporary source after apparent import | Broken published image | Verify platform ingestion and source-independent media; revoke grant in integration tests; no permanent hosting fallback |

## Deferred capabilities and implementation gaps

The P0 scaffolding, UC-01 (login + workspace picker) and the first UC-02 slice (connections/destinations, AES-256-GCM secret storage, telegram/pasted probe) are implemented (2026-11-14, 27/27 tests green); the rest of UC-02…UC-16 and backup tooling remain required implementation work, not completed safeguards. The Compose baseline (`docker-compose.yml`, app + private db) exists; setup guides and a published image do not yet. Video/extra formats, workspace-local calendar conveniences, contact import and HA remain later decisions. Shared multi-user/team features, recurring posts, cross-workspace campaigns/copy and permanent public image hosting are excluded.

Exact latest dependency combinations, buildpack runtime/architecture/library support, provider app approvals, and optional bridge operations need implementation evidence. Current candidates and investigation criteria live in the [technology baseline](../technologies.md) and [platform catalog](../platforms/README.md).
