# UC-14 — Evaluate and opt into personal Status / Signal experiments

- **Actor:** Owner
- **Trigger:** Enable an experimental integration after its feasibility gate
- **Preconditions:** ADR-010 probe passed; current setup guide and compatible pinned bridge available
- **Related decisions:** ADR-010, ADR-013, ADR-015

## Main flow

1. Review the exact operation/audience proven by the feasibility record and acknowledge its unofficial limitations.
2. Enable the private optional bridge profile and pair a workspace-specific connection/session through a locally protected setup path.
3. Validate identity, audience, supported media, acknowledgement/reconciliation, and architecture support.
4. Add only the verified destination kind to that workspace, with a permanent experimental label.
5. Compose, review, approve, and publish through the normal durable pipeline. Several sessions/accounts require separate protected routing/storage.

## Alternative flows

- No proven personal WhatsApp Status bridge: show unavailable; do not fabricate an endpoint or ship a misleading wizard.
- **WhatsApp Business is excluded:** never offer Cloud API, Business credentials, OAuth, or Business messaging as a substitute.
- Signal message and story targets are separate capabilities; no story-to-message fallback.
- Bridge unlinked/down or rights unclear: block dependent delivery and notify; do not affect healthy official targets.
- Ambiguous bridge acknowledgement is UNKNOWN and follows UC-07.
- Upstream restriction or unsupported operation produces a sanitized error; no workaround attempts or audience changes.

## Postconditions and UI

No experiment is a core-release requirement. Pairing/admin interfaces remain private and credentials/session files isolated per workspace. Experimental opt-in does not imply guaranteed delivery, account safety, or official API support.
