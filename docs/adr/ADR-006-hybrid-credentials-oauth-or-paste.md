# ADR-006 — Provider-specific OAuth and pasted credentials per connection

- **Status:** Accepted; amended during the pre-implementation review
- **Date:** 2026-11-13

## Context

Providers differ in app registration, token acquisition, refresh access, and account/page discovery. One authorization may grant access to several destinations, while one workspace may need several separate authorizations of the same platform.

## Decision

- Offer only credential modes the provider actually supports; pasted tokens are not universally available and do not bypass app review or scopes.
- Persist a workspace-scoped `connection` for the authenticated provider principal; derive explicit selectable `account` destinations such as a LinkedIn page or Telegram chat.
- Bind OAuth state to initiating owner/session, workspace, platform, connection intent, fixed callback, and expiry. Use PKCE when supported, validate issuer/provider and redirect, consume state once, and reject replay or workspace mismatch.
- Provider callbacks may arrive after a browser workspace switch; finish only the stored original workspace flow and display that workspace before saving. Never use the currently active tab's workspace to assign tokens.
- Verify the provider principal on connect/reconnect. If it differs from the expected identity, require a new connection rather than silently retargeting existing drafts/schedules.
- Discovery (including LinkedIn organizations) uses only documented permitted endpoints and verified identity mappings. A denied discovery call does not imply write access.
- Serialize refresh by connection, store refreshed credentials atomically, and propagate health to dependent destinations. Refresh only when the provider/account grants it; otherwise offer reconnect. Do not promise universal refresh or a fixed token lifetime.
- Encrypt all provider secrets under [ADR-007](ADR-007-secrets-in-env-on-volume.md). AI keys use their own workspace configuration, not social OAuth.

**WhatsApp Business has no credential mode, callback, discovery, or onboarding.** Personal WhatsApp Status uses separate optional bridge pairing under ADR-010.

## Consequences

Multiple connections can coexist for one platform, and one connection can serve several destinations in its workspace. Account health is scoped and advisory; revalidate rights at execution. Shared upstream app quotas may still affect several connections.

## Alternatives

One token per platform or an installation-wide provider token mixes identities and is rejected. OAuth-only onboarding excludes token-based integrations; universal paste mode would falsely promise access.
