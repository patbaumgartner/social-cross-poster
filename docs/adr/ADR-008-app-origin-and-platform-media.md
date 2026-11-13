# ADR-008 — HTTPS origin, private sources, and platform-hosted images

- **Status:** Accepted; clarified by owner
- **Date:** 2026-11-13

## Context

Published images must live on the destination platform. Local files are private sources for editing, scheduling and recovery. The owner permits temporary public URLs only when a provider must import an image; permanent public image hosting is excluded.

## Decision

- Each installation configures its production HTTPS origin through `APP_BASE_URL`, supplied as deployment configuration. No owner's hostname is compiled into the app, image or migrations, and choosing a real hostname is not a prerequisite for implementation. Generate registered OAuth callback URLs, enabled webhook URLs, authenticated UI links and temporary import URLs from that configured origin. Keep it stable during authorization flows; changing it requires updating affected provider registrations. Trust forwarded headers only from explicitly configured proxies.
- Private source images/thumbnails require authenticated workspace access. Saving a draft never makes its assets public.
- Outgoing derivatives remove GPS/camera metadata; private originals may retain it. Approve the exact derivative bytes, with retention and dependency-aware cleanup under [ADR-016](ADR-016-operational-and-editorial-policies.md).
- Prefer a verified direct/binary upload to the platform. Persist returned upload/media IDs and readiness stages; the published post references platform-owned media.
- When a verified API requires fetching source bytes, mint a revocable signed `/media/{grantId}?exp=…&sig=…` URL for the exact approved derivative. Bind workspace, asset, delivery, purpose, expiry and key ID. Allow GET/HEAD, validate type/path, return Cache-Control: no-store, redact bearer URLs, and apply provider-compatible rate limits.
- Mint import grants near actual dispatch. TTL is bounded and based on verified processing/retry behavior, not a blanket container lifetime. Retire/revoke grants after confirmed import when the provider no longer needs the source; renew only for safe recovery of the same approved bytes.
- Confirm platform ingestion/readiness before the relevant publish stage. A successful API response does not by itself prove source independence: integration tests must show the published image remains available after the source grant is revoked.
- Never embed an app-hosted source URL as the lasting image reference in a published post. No public gallery, permanent image route, CDN/object-hosting service or durable-public-asset mode is part of the product.
- If a platform cannot retain its own image copy, mark image publication unsupported instead of self-hosting or silently dropping images.
- Source assets are reference-counted within their workspace and retained for drafts, independent duplicate posts, scheduled jobs, history and safe retries. Removing one post/grant cannot break another.
- A self-fetch tests local routing only. Provider-fetch/external-probe evidence is separate and needed only for adapters using temporary import grants.
- Route exceptions remain narrowly defined in ADR-005; local source cleanup never claims to delete remote media.

## Verified references and limits

LinkedIn's [Images API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api) provides upload initialization, an upload URL and a media URN for person/organization owners. Meta's [Threads API collection](https://www.postman.com/meta/threads/documentation/dht3nzz/threads-api?entity=request-34203612-b4339073-a559-4e15-9ffe-974d6f0451ec) documents source-URL image import. Verify the current API version, rights, source-fetch lifetime and platform-copy independence per integration; neither reference is a complete access certification.

## Consequences

The app temporarily transfers approved images and keeps private editable sources; platform delivery does not depend on permanent NAS image availability. Temporary URLs remain bearer capabilities until expiry/revocation, and downloaded bytes are retained under the provider's policies.

## Alternatives

Permanent public image hosting and claiming every API accepts binary images are rejected. Temporary import is an explicitly authorized transport exception, not an embedded-image hosting feature.
