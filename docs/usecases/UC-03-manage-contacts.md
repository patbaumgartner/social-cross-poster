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
