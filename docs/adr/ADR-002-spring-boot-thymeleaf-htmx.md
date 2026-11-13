# ADR-002 — Spring Boot monolith with Thymeleaf + HTMX

- **Status:** Accepted; amended during the pre-implementation review
- **Date:** 2026-11-13

## Context

A NAS-hosted editorial application needs a workspace switcher, drafts, AI suggestion comparisons, destination previews, history, and operational settings without unnecessary deployment services.

## Decision

Use the latest stable Spring Boot, Java, Maven, Thymeleaf, and HTMX from the sourced [technology baseline](../technologies.md). Update and pin resolved versions during scaffolding and releases, with explicit compatibility evidence. Build application images with Cloud Native Buildpacks/Paketo under ADR-012.

Start with one module and explicit packages for `workspace`, `accounts`, `contacts`, `content`, `ai`, `publishing`, `platform`, `scheduling`, `media`, `notify`, `web`, and `persistence`. Business services enforce workspace authorization independently of controllers.

Use server-side previews, small local CSS/JS assets, accessible forms and dialogs, keyboard navigation, and text labels alongside status colors. AI diffs may use a small local script; there is no SPA or required npm build. Escape AI and platform text before rendering; sanitization is required for any permitted rich-text subset.

HTMX polling reflects persisted AI/publication jobs; browser disconnection does not cancel a committed publication. Disable HTMX evaluation features incompatible with the chosen CSP and verify actual flows under that policy.

## Consequences

One application artifact simplifies deployment, while package boundaries keep AI and provider failures separate. Local assets allow page rendering without a CDN; publishing and AI still require their external services. Memory/startup budgets must be measured with representative workspaces and image processing, not inferred from the framework choice.

## Alternatives

A SPA adds tooling and duplicate validation without a present requirement. CLI-only delivery does not meet the browser workflow. Neither is selected for v1.
