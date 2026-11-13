# ADR-010 — Personal WhatsApp Status and Signal: optional experiments

- **Status:** Accepted as scope and feasibility policy; no bridge selected or support certified
- **Date:** 2026-11-13

## Context

The earlier design treated unspecified unofficial bridges as usable integrations and accidentally introduced WhatsApp Business onboarding. We need a precise boundary.

## Decision

**WhatsApp Business is not supported.** There is no Cloud API, Business account onboarding, Business messaging, Business OAuth, or Business credential type. An experimental failure never falls back to Business messaging.

Personal WhatsApp Status remains an optional candidate. Before choosing a bridge, prove the exact personal Status text/image operation with an authorized test identity, current upstream documentation, maintained versions for the intended architecture, session isolation, explicit audience behavior, recovery, and observable acknowledgement. Until that probe passes, no REST endpoint, pairing wizard, or successful Status posting is promised.

Signal evaluation may use [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api). Sending a message and publishing a story are separate capabilities. Lack of story support must block a story target, not send a chat message instead.

If accepted after feasibility:

- Deploy only in explicit opt-in profiles, with pinned versions, private networking, protected persistent sessions, and no public pairing/admin UI.
- Isolate sessions by workspace and connection/account. A single global bridge account is not multi-account support.
- Show “experimental / unofficial” on setup, destination selection, confirmation, and history.
- Apply the same workspace authorization, immutable payload, delivery/reconciliation, and notification rules as other targets.
- Explain instability and account restrictions before opt-in. Disable unsupported edit/delete/audience capabilities.
- An unlinked or unhealthy bridge blocks only dependent destinations. Do not bypass provider restrictions or silently substitute identities.

## Consequences

The core release has no experimental bridge dependency. This decision reserves scope without claiming feasibility or inventing an API.

## Alternatives

Treating WhatsApp Business as a substitute for personal Status, sharing a global session across brands, and sending normal messages when stories fail are rejected.
