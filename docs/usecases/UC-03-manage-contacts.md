# UC-03 — Manage workspace contacts and mention identities

- **Actor:** Owner
- **Trigger:** Open Contacts or use mention autocomplete
- **Preconditions:** Logged in; explicit workspace
- **Related decisions:** ADR-003, ADR-013

## Main flow

1. Create a contact with display name, optional photo, and private notes in the current workspace.
2. Add platform-specific handles and, where needed, instance/host or verified native identity. Keep raw handles distinct from resolved identifiers.
3. Type @ in the composer, search this workspace's contacts, and insert a neutral local contact token.
4. Destination previews resolve the token through that platform's verified mention contract. Show whether it is a native mention or plain display-name fallback.
5. Review fallbacks before approving publication.

## Alternative flows

- Missing/unresolvable native identity falls back visibly to the display name; never invent an identifier, suppress the name, or claim a notification will happen.
- A LinkedIn profile URL alone does not establish a usable person URN. Bluesky and Mastodon use different identity/address formats; per-platform identity rules are recorded in the [integration catalog](../platforms/README.md).
- Multiple handles require an explicit platform/instance choice where ambiguous.
- Deleting a contact used by drafts prompts replacement/fallback review; existing approved payload snapshots remain unchanged.
- Copies are restricted to the same workspace and retain local mention tokens. Another workspace’s contact ID is rejected.
- Manual entry is v1; contact import is a later convenience. AI receives only selected mention labels/tokens, never private contact notes by default.

## Postconditions and UI

Autocomplete and contact photos remain workspace-scoped. Explain native mention limitations directly in previews; do not use one generic mention rule for all providers.

## Review log

- 2026-11-13 — Reviewed against ADR-003 and ADR-013. Neutral mention tokens, verified per-platform mention contracts and the visible display-name fallback match ADR-003; workspace scoping of contacts/autocomplete matches ADR-013. Added a pointer to the integration catalog for per-platform identity formats. No behavior change.
- 2026-11-14 — **Implemented (first slice).** Flyway V3 adds workspace-scoped `contact` (unique neutral token `{{contact:<uuid>}}`, display name, optional photo, private notes, archived soft-delete) and `contact_handle` (platform from the integration catalog, raw handle kept distinct from `identity_kind`/`identity_value`/`verified`, `host` for federated instances, UNIQUE per contact+platform+handle+host). `GET/POST /w/{slug}/contacts` manages contacts; `GET /w/{slug}/contacts/search?q=` is the autocomplete (query = text after the last `@`; results never include private notes); `GET /w/{slug}/contacts/{id}/mentions?platform=&text=` resolves tokens through the platform's verified mention contract. Telegram is the only verified contract so far (`@username` → native mention); every other platform shows the visible display-name fallback and never invents an identifier; >1 handle per platform is AMBIGUOUS; unknown/foreign tokens are flagged UNKNOWN_TOKEN, never dropped; archived contacts still resolve so existing snapshots stay stable. Deviations from the plan text, to be revisited: photos are accepted as `data:` URIs only (ADR-008 — external URLs are never fetched, max ~256 KiB decoded); manual handle entry never sets `verified=true`; composer insertion lands with UC-04; contact import stays a later convenience. Client note: raw `{`/`}` in a query string is rejected by the servlet container (HTTP 400) — send mention tokens URL-encoded (e.g. `%7B%7Bcontact:…%7D%7D`). Verified by `ContactFlowTest` (10 tests) + `MentionResolverTest` (9 tests), 46/46 green, plus a live smoke run on H2 (create contact, add telegram handle, search without note leakage, encoded-token previews returning NATIVE/FALLBACK/UNKNOWN_TOKEN, Flyway V3 applied).
