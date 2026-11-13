# ADR-012 — Buildpacks images and one multi-architecture Compose stack

- **Status:** Accepted; amended for latest-stable and Buildpacks requirements
- **Date:** 2026-11-13

## Context

A single NAS installation hosts several workspaces. The owner requires latest stable technologies and prefers Buildpacks over Dockerfiles. Source repository: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster).

The owner's reference installation is a Synology DS918+ with Intel x86_64 CPU and 12 GB installed RAM shared with other containers, Docker Engine 24.0.2, Compose 2.20.1 and Traefik. These are owner-reported deployment inputs, not compatibility evidence or requirements for every operator. Validate this deployment separately from current CI/build-tool versions. Exact DSM/Container Manager/proxy versions are diagnostic information for installation testing; selecting them or a hostname is not a product-design prerequisite. Available resource budgets remain to be measured through the [operations inventory](../operations/README.md).

## Decision

- Use Spring Boot Maven plugin Cloud Native Buildpacks support with a current compatible Paketo builder and matching run image. Follow the sourced [technology baseline](../technologies.md) for versions.
- No application Dockerfile by default. Investigate maintained builders first; a Dockerfile exception requires an evidenced missing capability and documented maintenance/testing plan.
- Pin resolved builder/buildpack/run-image digests and set the required Java major explicitly. Verify the latest patched JVM distribution from the SBOM rather than accepting a builder's default.
- Build one image per `linux/amd64` and `linux/arm64`, preferably on native runners. Verify runtime, image libraries/fonts, CA certificates, non-root behavior, signals, and smoke tests on each; publish a combined OCI index only after both pass.
- Use the planned registry path `ghcr.io/patbaumgartner/social-cross-poster` with immutable release tags/digests. Buildpacks builds per architecture do not automatically make a multi-platform manifest.
- Compose baseline is `app` plus private `db`; consume upstream PostgreSQL images. Experimental bridge profiles appear only after their own feasibility gates.
- Support an operator-configured TLS reverse proxy and external origin (`APP_BASE_URL`); no Traefik-specific application dependency or owner's hostname is required. Trust only configured proxies. Bind host application ports to loopback/private proxy networking. DB and management listeners are private; the running app never needs the Docker socket.
- Pass environment/secret files explicitly. Inventory DB, workspace media, config, encryption/signing keys, and optional bridge session volumes for recovery.
- Use buildpack memory settings consistent with container limits; measure total RSS, DB use, image buffers, and startup before setting NAS budgets.
- Upgrade through tested latest-stable update PRs and maintenance windows; schema rollback may require a matching backup. Restore drills and dispatch-disabled recovery are stable-release gates.

## Consequences

One stack hosts multiple logical tenants without claiming process isolation or HA. There is no runnable image, Compose file, or backup script yet. Buildpacks manage packaging; the project still owns version currency, reproducibility, architecture tests, and recovery.

## Alternatives

Default Dockerfile builds, untested floating `latest` deployment, raw live DB copies, and zero-downtime claims are rejected. Kubernetes/active-active replicas are outside v1.
