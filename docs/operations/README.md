# Operations and recovery design

**Planning only, with the Compose baseline landed 2026-11-14.** `docker-compose.yml` (app + private `db`, loopback-only published ports, digest-pinned `postgres:18.7`, `.env.example`) exists and validates with `docker compose config`, but no release image is published yet, and there is no backup script or tested recovery procedure. Implement and exercise the remaining requirements before a stable release.

Source: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster). Versions and Buildpacks policy: [technology baseline](../technologies.md).

## Confirmed deployment and unresolved inputs

The owner's reference installation is a **Synology DS918+**, an Intel x86_64 machine, with **12 GB installed RAM shared with other containers**, **Docker Engine 24.0.2**, **Docker Compose 2.20.1**, and **Traefik** as the reverse proxy. The model's CPU/architecture is documented by [Synology](https://kb.synology.com/en-us/DSM/tutorial/What_kind_of_CPU_does_my_NAS_have); installed RAM and runtime versions are owner-reported, not vendor-support or compatibility-test claims. Public HTTPS access without a VPN is possible. This reference installation does not restrict other operators to Synology, Traefik or a particular hostname.

Available memory/disk budget, workload, backup destination/frequency and recovery objectives remain to be established. Exact DSM, Container Manager and proxy versions are useful diagnostics when testing or troubleshooting an installation; they do not block the product design. The confirmed Engine/Compose versions replace the earlier approximate “1.42”. Validate released images and Compose configuration against **24.0.2 / 2.20.1** rather than requiring the current upstream CI-tooling versions on the NAS. Build images in CI and measure runtime behavior on this NAS; do not assign all installed RAM to the app. The existing amd64/arm64 release requirement remains unchanged.

## Deployment contract

- One TLS reverse proxy fronts a Buildpacks-built application image and a private PostgreSQL service. Management and DB ports are not published publicly.
- Operators configure their own HTTPS hostname with `APP_BASE_URL`, deployment networking and trusted proxies. Traefik is the owner's choice, not an application dependency or required proxy version. Keep these values outside the image; use the configured origin when registering each installation's provider callbacks/webhooks. A real hostname is needed for that installation's onboarding, not for implementing the app.
- Use tested immutable releases from the planned `ghcr.io/patbaumgartner/social-cross-poster` image path. Build with the latest stable compatible toolchain, pin resolved inputs, and verify both amd64 and arm64.
- Pass deployment properties explicitly through Compose environment or documented mounted files. Keep populated configuration and encryption/signing keys outside Git with restrictive permissions.
- One owner runs each installation; other people use separate installations. All of that owner’s workspaces share the deployment but own their application records, credentials, private media paths, jobs, and settings. Logical tenancy does not protect against a compromised host/admin.
- Use bounded workers, quotas, upload/decode limits, and disk alerts. Measure total memory, including buildpack JVM memory calculation, image processing, and DB.
- Experimental bridge sessions require separate workspace/connection storage and an explicit compatibility/restore inventory. They are not part of the baseline stack.

## Dispatch pause and retention

Implement the emergency dispatch pause agreed in [ADR-016](../adr/ADR-016-operational-and-editorial-policies.md): prevent new publishing attempts, preserve durable intent and recovery records, and allow read-only reconciliation. Requests already in flight may complete. On resume, recheck scheduled creation deadlines; never reset their 15-minute allowance. Pause scope and handling of other remote mutations still need an implementation decision.

Keep approved history and required private source media without automatic expiry. Prune unapproved autosave revisions only after 30 days and only when they are not current or needed by retained approvals, schedules, independent copies, active AI requests, delivery/recovery or grants. Delete media only after checking all retained dependencies. This revision policy does not define backup retention or authorize workspace purge. Outgoing image derivatives strip GPS/camera metadata; originals remain private.

## Backup contract

Back up PostgreSQL, private immutable source media and derivatives, deployment configuration, signing-key versions, encryption keys, and enabled bridge session data. A content export excludes secrets and cannot replace a disaster-recovery backup.

1. Enter maintenance mode: stop schedule dispatch, publication/AI/notification job claims, uploads, and edits; drain active work where possible. Mark ambiguous remote effects for later reconciliation.
2. Create a consistent `pg_dump` with a compatible client and copy the now-stable media set with a checksum manifest. Preserve schema/app versions, selected image digests, workspace counts, key IDs, and backup timestamp.
3. Back up encryption keys separately in a protected location. Protect config/DB/media backups too; encryption keys and DB together disclose secrets.
4. Verify checksums/completeness and resume service. A file copy of a running PostgreSQL volume is not a valid logical backup.

Keep a documented retention policy and an off-host copy. Do not delete private assets referenced by independent post copies, schedules, active delivery/import grants or history during backup/cleanup. Published images live on their platforms; no public gallery or permanent media host needs restoring.

## Restore drill and upgrade contract

Restore into an **isolated deployment with dispatch disabled** before reconnecting providers. Restoring production schedules into an active clone can create duplicate posts.

- Restore the matching schema/data, media manifest, configuration, and required key versions.
- Verify all four example workspaces, their same-platform destinations, revisions, media, credential decryption, schedules, and history without logging secrets.
- Reconcile RUNNING/UNKNOWN work against upstream state before resuming; do not blindly replay restored jobs. Keep AI/notification dispatch disabled until reviewed as well.
- After checks, select which future schedules to enable and record the test outcome, duration, and recovery point.
- For upgrades, take a verified backup and use a maintenance window. Flyway applies versioned migrations. Major PostgreSQL upgrades need an explicit supported upgrade procedure; changing an image tag is insufficient.
- Binary rollback is valid only with a compatible schema. Otherwise restore the matching backup, then reconcile any remote writes that happened after it.

## Operational signals

Expose internal readiness, queue age, expired leases, UNKNOWN counts, account expiry, rate limits, AI reserved/actual usage, notification delivery failures, disk usage, and backup age. Logs contain correlation IDs and workspace IDs rather than draft bodies, tokens, prompts, or signed URLs. Health of one platform is separate from app readiness.

## Required release evidence

A native smoke test per architecture; a restored backup with decrypted secrets and media; queue recovery after worker termination; safe overdue scheduling; key rotation/restore; and version/SBOM inventory. Record measured hardware, data size, memory, startup, and restore timings. Verify temporary-import expiry/revocation while published platform images remain available. There is no v1 HA or zero-downtime promise.
