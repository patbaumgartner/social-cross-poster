# ADR-003 — Layered overrides and reviewed destination payloads

- **Status:** Accepted; amended during the pre-implementation review
- **Date:** 2026-11-13

## Context

One message may need different wording for a platform and different identities or languages within that same platform. Automatic truncation or image dropping can alter an event announcement.

## Decision

1. Store default text/media, platform overrides, and destination overrides in each content revision. Resolve fields by destination → platform → default. Absence inherits; explicit empty text or image selection clears that field and is then validated.
2. Use the same deterministic adapters for preview and execution. Show actual destination identity, text/counting algorithm, images/order/alt text, mention fallbacks, and any unavailable capability.
3. Block over-limit or unsupported payloads. Offer manual edits, explicit conversions, or AI shortening as suggestions. Never silently truncate, split into threads, drop media, change a link, or fall back from a story to a chat message.
4. Begin with up to four still JPEG/PNG originals per draft. Validate file signatures, decode limits, dimensions, and size. Strip GPS/camera metadata from outgoing derivatives while retaining private originals, and show the exact derivative before approval. Meaningful local alt text is required; if an API cannot transmit it, require an explicit destination-specific acknowledgement. Filenames are not alt text. These policies follow [ADR-016](ADR-016-operational-and-editorial-policies.md).
5. Contacts and handles are workspace-scoped. Store neutral mention tokens referencing local contacts; adapters resolve provider-specific identities and syntax. Preserve the distinction between a display label, handle, federated address, DID, and provider URN.
6. Native mention support requires a verified provider contract. Bluesky rich-text facets and LinkedIn commentary require specialized encoding; do not invent outgoing X mention entities or treat a literal URN as a mention. If resolution is unavailable, preview a plain display-name fallback without claiming it notifies anyone. Never omit a person silently.
7. AI suggestions preserve mention tokens and factual anchors where possible. Invalid tokens, changed dates/names/URLs, and stale draft versions are flagged before acceptance. Accepted content becomes a new revision and requires fresh previews ([ADR-014](ADR-014-ai-content-assistance.md)).
8. Approval freezes semantic payloads and adapter/capability versions. Revalidate at execution; incompatible drift requires review rather than automatic adaptation.

## Consequences

Identical defaults can yield several reviewed same-platform variants. Transport artifacts such as expiring upload URLs may change; text, audience, identity, media content, and formatting cannot change under an existing approval.

## Alternatives

Per-platform overrides alone cannot distinguish two accounts. Silent truncation and client-only validation make previews unreliable. Both are rejected. Automated thread splitting and additional image formats remain future work.
