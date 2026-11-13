# ADR-001 — Stateless platform strategies with explicit destination context

- **Status:** Accepted; amended during the pre-implementation review
- **Date:** 2026-11-13

## Context

The [crosspost](https://github.com/humanwhocodes/crosspost) strategy approach suits publishing to heterogeneous APIs. One installation must also serve several workspaces and multiple accounts of the same platform without mixing credentials or content.

## Decision

- Use one stateless `PostingTarget` implementation per platform/destination kind and a separate `PostAdapter` for validation and payload preparation.
- Pass explicit execution context: workspace, destination/account ID, connection/credential reference, immutable approved payload, publication/delivery IDs, and idempotency key.
- Never store a “current account”, workspace, token, mutable request, or provider session in a shared Spring component.
- Resolve capabilities for the actual destination, credential permissions, API version, and instance. Unknown operations remain disabled.
- `CrossPostService` persists deliveries and durable jobs, then workers execute with bounded parallelism, per-connection/provider quota controls, deadlines, and isolated failures.
- Adapters prepare the same semantic payload for preview and delivery. Temporary transport URLs/tokens can be refreshed without changing approved content.
- Classify outcomes as confirmed success, safe failure, or ambiguous remote write. Reconciliation is a platform strategy responsibility where an upstream mechanism exists ([ADR-015](ADR-015-publication-lifecycle-and-recovery.md)).
- AI assistance is a separate editorial service, never a transport strategy.

## Consequences

New platforms require an adapter, transport, capability/identity verification, contract tests, and setup guide. A platform outage is isolated, but shared app credentials may impose shared quota limits across workspaces. Virtual threads do not remove the need for concurrency limits or durable jobs.

## Alternatives

A singleton strategy per account risks mutable state leaks. A generic configurable HTTP sender cannot reliably model staged media uploads and reconciliation. A synchronous browser request cannot provide restart-safe publication. These approaches are rejected.
