# 1. Introduction and goals

Social Cross-Poster is a planned NAS-hosted editorial and publishing application for one owner operating several workspaces: Private, socrates-ch, Voxxed Days Zurich, Voxxed Days Ticino, and others. Each workspace can connect multiple accounts of the same platform. Other people run separate one-owner installations; publishing stays strictly within one workspace.

## Requirements

- Isolate workspace accounts/connections, contacts, drafts/revisions, media, history, schedules, AI settings/usage, and notifications.
- Write a shared message, override per platform or individual destination, and review exact destination identity/content before publication.
- Optionally use AI to write, review, correct, shorten, translate, and propose variants; human acceptance and deterministic validation precede publication.
- Publish text and up to four still JPEG/PNG images with meaningful alt text, track per-destination outcomes, and recover without silently duplicating posts.
- Support drafts, individual one-time schedules and same-workspace post duplication, capability-gated edit/delete, safe retry/reconciliation, notifications, and restore-tested operation.
- Prove the initial workflow with Bluesky, Mastodon, Telegram channel/group posts, LinkedIn personal profiles and LinkedIn company pages; expand through the gated [catalog](../platforms/README.md).
- Store published images on the platform, with direct upload preferred and temporary import URLs allowed; local source images stay private.
- Exclude recurring posts, cross-workspace campaigns and permanent public image hosting.
- Exclude WhatsApp Business entirely. Personal WhatsApp Status and Signal remain optional unofficial feasibility work.

## Quality priorities

| Priority | Goal | Evidence required |
|---|---|---|
| 1 | Correct workspace and identity | Negative isolation tests and final destination confirmation |
| 2 | Reliable, auditable publishing | Immutable approvals, durable jobs, safe retries, explicit UNKNOWN outcomes |
| 3 | Editorial control | AI comparison/acceptance, no silent truncation/media loss, accessible previews |
| 4 | Secure and recoverable operation | Encrypted secrets, constrained public routes, verified backup/restore |
| 5 | Maintainability and current technology | Latest stable version inventory, Buildpacks images, isolated adapters and sourced capabilities |

## Stakeholders

The owner needs clear brand separation and dependable event communication. Readers need accurate, accessible content. Platform/AI providers require compatible API use and access configuration. Maintainers need traceable decisions and realistic evidence rather than unsupported feature/version claims.
