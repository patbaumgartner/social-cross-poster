# ADR-009 — PostgreSQL for tenant data and durable work

- **Status:** Accepted; amended during the pre-implementation review
- **Date:** 2026-11-13

## Context

Workspace boundaries, immutable revisions, delivery attempts, schedules, and recovery require transactional constraints. The baseline is one NAS with one application instance.

## Decision

- Run PostgreSQL as a private Compose service with a persistent volume, readiness check, and no host port. Use the latest stable major/patch from the [technology baseline](../technologies.md), pin its image digest, and reverify before release.
- Use Flyway versioned migrations and conservative connection pooling. Store each one-time scheduled instant as a UTC-aware timestamp, with its selected timezone/offset and lateness policy separately.
- Enforce composite workspace foreign keys and relevant uniqueness constraints from [ADR-004](ADR-004-domain-model-and-feature-scope.md).
- Persist jobs with due time, lease expiry, fencing token, and retry classification. Claim via short transactions using `FOR UPDATE SKIP LOCKED`, commit, then perform network work outside the DB lock.
- Recover expired claims without assuming the remote call failed. Reconcile possible remote writes before re-sending; persist remote upload/container stages as they progress.
- Persist a publication and its initial jobs atomically. Notification events/deliveries are also durable and deduplicated so a crash does not lose the alert.
- Back up through a coordinated maintenance window plus `pg_dump` and immutable media manifest/copy. A raw live volume copy is not a valid DB backup. See [operations](../operations/README.md).

## Consequences

No Redis/RabbitMQ is required for v1. Row locks alone do not guarantee exactly-once remote effects, and PostgreSQL does not make local media storage multi-replica-safe. Multiple application replicas and HA require separate design and testing.

## Alternatives

Embedded databases were not selected. An external queue would add operations without solving remote idempotency or workspace scoping by itself.
